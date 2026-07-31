package io.github.kikin81.atproto.oauth

import io.github.kikin81.atproto.runtime.AuthProvider
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * [AuthProvider] implementation that attaches DPoP proof-of-possession
 * headers on every XRPC request and handles token refresh transparently.
 *
 * On each request, produces:
 * - `Authorization: DPoP <access_token>`
 * - `DPoP: <signed-jwt-proof>` (with `ath`, `htm`, `htu`, `nonce`)
 *
 * When the PDS responds with HTTP 401:
 * - If `DPoP-Nonce` header is present → stores the nonce, retries
 * - If the access token is expired → refreshes via the token endpoint
 *   with the DPoP-bound refresh token, retries
 * - If the refresh token is revoked (`error=invalid_grant`) → clears the
 *   session, throws [OAuthSessionExpiredException]
 * - Otherwise (network error, 5xx/429, unparseable/captive-portal body, or any
 *   non-`invalid_grant` error) → throws the retryable
 *   [OAuthRefreshFailedException] and LEAVES THE SESSION INTACT, so a flaky
 *   connection can't sign the user out
 *
 * Refresh operations are serialized with a [Mutex] to prevent concurrent
 * refreshes from invalidating the session.
 */
class DpopAuthProvider(
    // Written under refreshMutex, but read without it (authHeaders,
    // isAccessTokenExpired, snapshotting) — @Volatile guarantees those
    // unlocked reads observe the latest rotation across threads.
    @Volatile private var session: OAuthSession,
    private val signer: DpopSigner,
    private val sessionStore: OAuthSessionStore,
    private val refreshClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /**
     * Invoked when persisting a rotated session fails even after one retry.
     * By then the server has already consumed the old refresh token, so the
     * in-memory session is the only valid copy: the provider keeps serving it
     * and reports the durability gap here (e.g. to a crash reporter) instead
     * of throwing. If the process dies before a later save succeeds, the
     * persisted refresh token is stale and the next cold-start refresh will
     * be rejected as reuse (`invalid_grant`).
     */
    private val onPersistFailure: (Throwable) -> Unit = {},
) : AuthProvider {

    private val refreshMutex = Mutex()

    /**
     * Latches once a refresh is terminally rejected (`invalid_grant`) and the
     * session is cleared. Written and read under [refreshMutex].
     *
     * The in-memory [session] is deliberately NOT mutated on failure, so
     * neither dedup check in [refreshTokensSingleFlight] can tell a terminal
     * failure apart from "nothing has happened yet": `rotatedWhileWaiting`
     * sees unchanged tokens, and [adoptStoredSessionIfRotated] sees an empty
     * store and reports "no rotation". Without this latch every coalesced
     * waiter re-POSTs the dead refresh token and duplicate-clears, and any
     * later nonce rotation re-saves the dead session over the cleared store.
     */
    private var sessionTerminallyExpired = false

    @Volatile
    private var pdsNonce: String? = session.pdsNonce

    @Volatile
    private var authServerNonce: String? = session.authServerNonce

    override suspend fun authHeaders(method: String, url: String): Map<String, String> {
        val ath = DpopSigner.accessTokenHash(session.accessToken)
        val proof = signer.sign(
            method = method,
            url = url,
            accessTokenHash = ath,
            nonce = pdsNonce,
        )
        return mapOf(
            "Authorization" to "DPoP ${session.accessToken}",
            "DPoP" to proof,
        )
    }

    /**
     * Called by [XrpcClient] on HTTP 401. Recovers every recoverable cause in
     * one call so the single retry that [XrpcClient] performs always carries
     * fresh state. Control flow:
     *
     * 1. If the server rotated `DPoP-Nonce`, store and persist it eagerly.
     *    Persisting before any refresh attempt means a refresh that throws
     *    (e.g. transient network failure) won't lose the rotated nonce.
     * 2. If the bound access token is a JWT whose `exp` is past (or within a
     *    small skew window) — i.e. the next request would 401 with
     *    `invalid_token` regardless of nonce — refresh proactively.
     * 3. If only the nonce was recoverable (opaque/non-expired token, new
     *    nonce already persisted in step 1), return `true`.
     * 4. Otherwise (no nonce signal: same nonce, no nonce header) refresh.
     */
    override suspend fun onUnauthorized(responseHeaders: Map<String, String>): Boolean {
        // Snapshot the tokens this 401 was (at the latest) issued against,
        // before any suspension below — the single-flight check compares
        // against them to detect a rotation that lands while this call waits.
        val observedAccessToken = session.accessToken
        val observedRefreshToken = session.refreshToken

        val newNonce = responseHeaders["DPoP-Nonce"] ?: responseHeaders["dpop-nonce"]
        val dateHeader = responseHeaders["Date"] ?: responseHeaders["date"]
        if (dateHeader != null) signer.calibrateClockFromHeader(dateHeader)

        val nonceChanged = newNonce != null && newNonce != pdsNonce
        if (nonceChanged) {
            pdsNonce = newNonce
            persistNonces()
        }

        if (isAccessTokenExpired()) {
            return refreshTokensSingleFlight(observedAccessToken, observedRefreshToken)
        }

        if (nonceChanged) return true

        return refreshTokensSingleFlight(observedAccessToken, observedRefreshToken)
    }

    /**
     * Serializes refreshes AND coalesces redundant ones. AT Proto refresh
     * tokens are single-use: replaying an already-consumed token trips the
     * auth server's reuse detection and revokes the entire session
     * (`invalid_grant`), so every avoidable rotation is a logout risk avoided.
     *
     * Inside the lock, two checks run before any network call:
     * 1. **In-flight dedup** — if the in-memory tokens changed while this
     *    caller waited on the mutex, a prior waiter already rotated; adopt
     *    that result and skip the redundant POST.
     * 2. **Cross-instance dedup** — if the shared [sessionStore] holds a
     *    session (same DID) with a different refresh token, another provider
     *    instance already rotated. Adopt the stored session instead of
     *    replaying this instance's now-consumed token.
     */
    private suspend fun refreshTokensSingleFlight(
        observedAccessToken: String,
        observedRefreshToken: String,
    ): Boolean {
        refreshMutex.withLock {
            // A prior waiter already proved this refresh token dead. Replaying
            // it would re-trip reuse detection and duplicate the clear; the
            // terminal outcome is shared across the coalesced set, exactly as
            // a successful rotation is.
            if (sessionTerminallyExpired) throw OAuthSessionExpiredException(TERMINAL_MESSAGE)

            val rotatedWhileWaiting =
                session.accessToken != observedAccessToken ||
                    session.refreshToken != observedRefreshToken
            if (rotatedWhileWaiting) return true

            if (adoptStoredSessionIfRotated()) {
                pdsNonce = session.pdsNonce
                return true
            }

            return refreshTokens()
        }
    }

    /**
     * MUST be called with [refreshMutex] held. If the shared [sessionStore]
     * holds a same-DID session whose refresh token differs from the in-memory
     * one, another provider instance already rotated — adopt its tokens (and
     * its auth-server nonce) instead of ever re-using this instance's
     * now-consumed refresh token. The caller decides what happens to
     * [pdsNonce], which may be fresher locally than in the store.
     */
    private suspend fun adoptStoredSessionIfRotated(): Boolean {
        val stored = sessionStore.load() ?: return false
        val rotatedByOtherInstance =
            stored.did == session.did && stored.refreshToken != session.refreshToken
        if (!rotatedByOtherInstance) return false
        session = stored
        authServerNonce = stored.authServerNonce
        return true
    }

    /**
     * Returns `true` only when [session.accessToken] is a JWT whose `exp`
     * claim has passed (within [skewSeconds]). Returns `false` for opaque
     * tokens, malformed JWTs, or JWTs without an `exp` claim — the caller
     * must not refresh on positive evidence absent.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun isAccessTokenExpired(skewSeconds: Long = 30): Boolean = try {
        val parts = session.accessToken.split('.')
        if (parts.size != 3) {
            false
        } else {
            val payload = Base64.UrlSafe.decode(parts[1].padBase64()).toString(Charsets.UTF_8)
            val exp = json.parseToJsonElement(payload).jsonObject["exp"]?.jsonPrimitive?.long
            if (exp == null) {
                false
            } else {
                val now = (System.currentTimeMillis() / 1000) + signer.clockOffsetSeconds
                exp <= now + skewSeconds
            }
        }
    } catch (_: Exception) {
        false
    }

    private fun String.padBase64(): String = when (length % 4) {
        0 -> this
        2 -> "$this=="
        3 -> "$this="
        else -> this
    }

    private suspend fun refreshTokens(): Boolean {
        val response = postRefreshForm(failureContext = "")
        if (response.status == HttpStatusCode.OK) return applyRefreshResponse(response)

        // Classify before deciding to retry: an HTTP body can only be consumed
        // once, and both the nonce-retry decision and the terminal split need
        // the `error` field.
        val error = parseErrorField(response)

        // A rotated DPoP-Nonce on a 401/400 is a recoverable use_dpop_nonce
        // signal — retry once with the new nonce (not a token rejection).
        //
        // An explicit `invalid_grant` is terminal even when the server ALSO
        // rotated a nonce: retrying would re-POST the refresh token the server
        // just rejected, and replaying a single-use refresh token is precisely
        // what trips AT Proto reuse detection. Classify first, retry second.
        val nonceHeader = response.headers["DPoP-Nonce"]
        val isNonceRetryStatus =
            response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.BadRequest
        val canRetryWithNonce = error != INVALID_GRANT && isNonceRetryStatus && nonceHeader != null
        if (canRetryWithNonce) {
            signer.calibrateClockFromHeader(response.headers["Date"]?.toString())
            authServerNonce = nonceHeader
            return refreshTokensWithNonce()
        }

        failRefresh(response.status, error)
    }

    private suspend fun refreshTokensWithNonce(): Boolean {
        val response = postRefreshForm(failureContext = " (nonce retry)")
        if (response.status == HttpStatusCode.OK) return applyRefreshResponse(response)
        failRefresh(response.status, parseErrorField(response))
    }

    /**
     * Reads the response body and extracts the OAuth `error` field (RFC 6749
     * §5.2). Returns `null` for an unparseable body (proxy/captive-portal HTML),
     * which the caller must treat as transient rather than as a rejection.
     */
    private suspend fun parseErrorField(response: HttpResponse): String? {
        // bodyAsText() is a suspend call — must not sit inside runCatching,
        // which would swallow CancellationException. Only the parse is guarded.
        val body = response.bodyAsText()
        return runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
        }.getOrNull()
    }

    private suspend fun postRefreshForm(failureContext: String): HttpResponse {
        val proof = signer.sign(
            method = "POST",
            url = session.tokenEndpoint,
            nonce = authServerNonce,
        )
        return try {
            refreshClient.submitForm(
                url = session.tokenEndpoint,
                formParameters = Parameters.build {
                    append("grant_type", "refresh_token")
                    append("refresh_token", session.refreshToken)
                    append("client_id", session.clientId ?: "")
                },
            ) {
                headers.append("DPoP", proof)
            }
        } catch (e: CancellationException) {
            throw e // never swallow cooperative cancellation
        } catch (e: Exception) {
            // Transient network failure — the refresh token isn't necessarily
            // invalid. Retryable; do NOT clear the session. Pre-send failures
            // (DNS, connect) are flagged: the request never reached the server,
            // so the single-use refresh token was provably NOT consumed and a
            // retry with the same token is safe. Post-send failures (response
            // lost) stay ambiguous — the server may have already rotated.
            val notConsumed = if (isPreSendFailure(e)) " (request not sent; refresh token not consumed)" else ""
            throw OAuthRefreshFailedException("Refresh request failed$failureContext$notConsumed", e)
        }
    }

    private fun isPreSendFailure(e: Exception): Boolean = e is UnknownHostException ||
        e is ConnectException ||
        e is UnresolvedAddressException ||
        e is ConnectTimeoutException

    /** Callers guarantee [response] is HTTP 200; non-OK goes to [failRefresh]. */
    private suspend fun applyRefreshResponse(response: HttpResponse): Boolean {
        val tokenResponse = json.decodeFromString(TokenResponse.serializer(), response.bodyAsText())
        session = session.copy(
            accessToken = tokenResponse.access_token,
            refreshToken = tokenResponse.refresh_token ?: session.refreshToken,
            authServerNonce = authServerNonce,
            pdsNonce = pdsNonce,
        )
        persistRotatedSession()
        return true
    }

    /**
     * Persists the rotated session, retrying once. A rotation the server has
     * already performed must never be failed client-side: throwing here would
     * fail a request that holds a perfectly good token and push callers toward
     * replaying the consumed refresh token (reuse detection → session
     * revocation). A persist that fails both attempts is reported through
     * [onPersistFailure] and otherwise swallowed — the in-memory session
     * remains the source of truth for this provider's lifetime.
     */
    private suspend fun persistRotatedSession() {
        val firstFailure = try {
            sessionStore.save(session)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        try {
            sessionStore.save(session)
        } catch (e: CancellationException) {
            throw e
        } catch (retryFailure: Exception) {
            retryFailure.addSuppressed(firstFailure)
            onPersistFailure(retryFailure)
        }
    }

    /**
     * Terminates a non-OK refresh response. Clears the session and throws
     * [OAuthSessionExpiredException] ONLY when the token endpoint reports the
     * refresh token is revoked/expired (`error=invalid_grant`, RFC 6749 §5.2).
     *
     * Every other failure — 5xx, 429, 408, a proxy/captive-portal body, or any
     * non-`invalid_grant` error — is treated as transient: throw the retryable
     * [OAuthRefreshFailedException] and leave the session intact so a later
     * request with real connectivity can refresh cleanly. This is what keeps a
     * flaky connection from silently signing the user out.
     */
    private suspend fun failRefresh(status: HttpStatusCode, error: String?): Nothing {
        if (error == INVALID_GRANT) {
            // Latch BEFORE clearing so any waiter that acquires the mutex next
            // short-circuits instead of replaying this now-dead token.
            sessionTerminallyExpired = true
            sessionStore.clear()
            throw OAuthSessionExpiredException("Refresh token revoked (invalid_grant, HTTP $status)")
        }
        throw OAuthRefreshFailedException(
            "Refresh failed (HTTP $status${error?.let { ", error=$it" } ?: ""})",
        )
    }

    /**
     * Persists the freshly-rotated nonces under [refreshMutex]. Adoption runs
     * first: if another instance sharing the [sessionStore] already rotated
     * the tokens, saving this instance's in-memory session verbatim would
     * overwrite the store's only valid refresh token with a consumed one —
     * re-arming the replay → reuse-detection → `invalid_grant` logout. After
     * adoption the save is a pure nonce update on top of the latest tokens
     * ([pdsNonce] stays local: the nonce from this 401 is the freshest).
     */
    private suspend fun persistNonces() {
        refreshMutex.withLock {
            // Once the session is terminally expired the store has been cleared
            // and this instance's in-memory copy holds a dead refresh token.
            // Saving it would resurrect the cleared session — and because every
            // request 401s with a rotated nonce after a logout, that happens on
            // the very next request, re-arming the reuse-detection loop.
            if (sessionTerminallyExpired) return
            adoptStoredSessionIfRotated()
            session = session.copy(authServerNonce = authServerNonce, pdsNonce = pdsNonce)
            sessionStore.save(session)
        }
    }
}

// File-level rather than a `private companion object`: a `const val` in a
// companion still compiles to a PUBLIC static field on the enclosing class, so
// the companion form leaked both constants into the published ABI and failed
// binary-compatibility-validator. Top-level private consts land on the file
// class as private statics instead.
private const val INVALID_GRANT = "invalid_grant"
private const val TERMINAL_MESSAGE = "Refresh token revoked (invalid_grant) — terminal for this session"

@Serializable
internal data class TokenResponse(
    val access_token: String,
    val refresh_token: String? = null,
    val token_type: String? = null,
    val expires_in: Long? = null,
    val scope: String? = null,
    val sub: String? = null,
)
