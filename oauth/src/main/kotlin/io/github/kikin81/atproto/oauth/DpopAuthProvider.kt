package io.github.kikin81.atproto.oauth

import io.github.kikin81.atproto.runtime.AuthProvider
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.ConnectException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.util.concurrent.ConcurrentHashMap
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
 * - If the access token is expired, or the 401 challenges it with
 *   `WWW-Authenticate: DPoP error="invalid_token"` → refreshes via the token
 *   endpoint with the DPoP-bound refresh token, retries. Any other 401 does
 *   not refresh: every rotation spends a single-use token, and a rotation
 *   whose response is lost ends the session on the next replay
 * - If the refresh token is revoked (`error=invalid_grant`) → clears the
 *   session, throws [OAuthSessionExpiredException]
 * - Otherwise (network error, 5xx/429, unparseable/captive-portal body, or any
 *   non-`invalid_grant` error) → throws the retryable
 *   [OAuthRefreshFailedException] and LEAVES THE SESSION INTACT, so a flaky
 *   connection can't sign the user out
 *
 * Refresh operations are serialized with one [Mutex] per account (DID),
 * shared by every provider in the process, so two clients built from the
 * same session cannot spend the same refresh token at once.
 */
class DpopAuthProvider(
    // Written under refreshMutex, but read without it (authHeaders,
    // isAccessTokenExpired, snapshotting) — @Volatile guarantees those
    // unlocked reads observe the latest rotation across threads.
    @Volatile private var session: OAuthSession,
    private val signer: DpopSigner,
    private val sessionStore: OAuthSessionStore,
    /**
     * How the refresh request reaches the token endpoint. See
     * [TokenEndpointTransport].
     *
     * Whatever sends the request should not silently re-send one whose
     * connection dropped (OkHttp: `retryOnConnectionFailure(false)`): a
     * re-sent refresh replays a token the server may already have consumed.
     *
     * It should also be bounded by a request timeout — Ktor's `HttpTimeout`
     * plugin, or timeouts configured on the engine. This library installs
     * neither and cannot enforce it, so it is the caller's responsibility.
     *
     * It matters more here than for an ordinary request: the refresh runs under
     * `NonCancellable`, because once the token endpoint has been POSTed the
     * refresh token is consumed server-side and abandoning the call would
     * strand the rotation. That removes cancellation as an escape hatch, so a
     * timeout is what bounds a hung socket. An unbounded transport will hold
     * the calling coroutine until the socket resolves on its own.
     */
    private val refreshTransport: TokenEndpointTransport,
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

    /**
     * Refreshes by posting directly to the token endpoint with [refreshClient]
     * — the behaviour of a public client. Equivalent to passing a
     * [DirectTokenEndpointTransport] built from that client, so the same
     * requirements apply to it: no silent re-sends, and a request timeout.
     */
    constructor(
        session: OAuthSession,
        signer: DpopSigner,
        sessionStore: OAuthSessionStore,
        refreshClient: HttpClient,
        json: Json = Json { ignoreUnknownKeys = true },
        onPersistFailure: (Throwable) -> Unit = {},
    ) : this(
        session = session,
        signer = signer,
        sessionStore = sessionStore,
        refreshTransport = DirectTokenEndpointTransport(refreshClient),
        json = json,
        onPersistFailure = onPersistFailure,
    )

    // A signup session can lack a DID until resolution completes; with no
    // account key to share, it keeps a lock of its own.
    private val refreshMutex = session.did?.let(RefreshLocks::forDid) ?: Mutex()

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

    /**
     * `true` when an earlier attempt with the current refresh token ended
     * without a definitive answer. Starts from what was persisted, so it
     * survives a process restart. Written and read under [refreshMutex].
     */
    private var priorRefreshOutcomeUnknown = session.refreshInDoubt

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
     * 2. Refresh if the token is provably dead: a JWT whose `exp` is past
     *    (or within a small skew window), or an explicit
     *    `WWW-Authenticate: ... error="invalid_token"` challenge.
     * 3. If only the nonce was recoverable (new nonce already persisted in
     *    step 1), return `true`.
     *    Also return `true` for a `use_dpop_nonce` challenge whose nonce is
     *    unchanged: a concurrent request stored it first, and this one was
     *    still sent with the stale nonce.
     * 4. With no challenge header and an opaque token there is no evidence
     *    either way, so refresh, as before. Any other challenge
     *    (`use_dpop_nonce` without a nonce, a rejected proof, scope),
     *    or a bare 401 against a JWT that has not expired, says nothing
     *    about the token: return `false` and keep the refresh token unspent.
     */
    override suspend fun onUnauthorized(responseHeaders: Map<String, String>): Boolean {
        // Snapshot the tokens this 401 was (at the latest) issued against,
        // before any suspension below — the single-flight check compares
        // against them to detect a rotation that lands while this call waits.
        val observedAccessToken = session.accessToken
        val observedRefreshToken = session.refreshToken

        val newNonce = responseHeaders.header("DPoP-Nonce")
        val dateHeader = responseHeaders.header("Date")
        val challenge = responseHeaders.header("WWW-Authenticate")
        if (dateHeader != null) signer.calibrateClockFromHeader(dateHeader)

        val nonceChanged = newNonce != null && newNonce != pdsNonce
        if (nonceChanged) {
            pdsNonce = newNonce
            persistNonces()
        }

        if (isAccessTokenExpired() || challenge?.hasChallengeError(INVALID_TOKEN) == true) {
            return refreshTokensSingleFlight(observedAccessToken, observedRefreshToken)
        }

        if (nonceChanged) return true

        // Requests sent together with a stale nonce all get a 401 with the same
        // new nonce. Only the first sees it as changed, so the rest must still
        // retry. This retries the request only and never spends the refresh token.
        if (newNonce != null && challenge?.hasChallengeError(USE_DPOP_NONCE) == true) return true

        val noEvidenceEitherWay = challenge == null && accessTokenExpiry() == null
        if (noEvidenceEitherWay) {
            return refreshTokensSingleFlight(observedAccessToken, observedRefreshToken)
        }
        return false
    }

    private fun Map<String, String>.header(name: String): String? = entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /**
     * Whether any challenge in a `WWW-Authenticate` value carries this `error`
     * auth-param (RFC 6750 §3). A header can list several challenges, so every
     * `error` counts, not only the first.
     */
    private fun String.hasChallengeError(error: String): Boolean = CHALLENGE_ERROR.findAll(this).any { match ->
        val (quoted, token) = match.destructured
        quoted.ifEmpty { token } == error
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

            // NonCancellable from here down, and NOT a line earlier. Everything
            // above is either a cheap local check or a wait on the mutex, where
            // a cancelled caller has caused no server-side effect and should be
            // free to give up. Once the token endpoint is POSTed the refresh
            // token is CONSUMED and a new one issued, so abandoning the call
            // does not undo anything — it only loses the replacement, leaving
            // the store holding a token the server will reject as reuse
            // (RFC 9700) on the next attempt. In the field the canceller was a
            // WorkManager CoroutineWorker being stopped mid-poll, and the user
            // was silently signed out minutes later.
            //
            // Bounded by the refresh client's request timeout, not by
            // withTimeout: a timeout wrapper here would reintroduce the very
            // cancellation this exists to prevent. See [refreshTransport].
            return withContext(NonCancellable) { refreshTokens() }
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
        priorRefreshOutcomeUnknown = stored.refreshInDoubt
        return true
    }

    /**
     * Returns `true` only when [session.accessToken] is a JWT whose `exp`
     * claim has passed (within [skewSeconds]). Returns `false` for opaque
     * tokens, malformed JWTs, or JWTs without an `exp` claim — the caller
     * must not refresh on positive evidence absent.
     */
    private fun isAccessTokenExpired(skewSeconds: Long = 30): Boolean {
        val exp = accessTokenExpiry() ?: return false
        val now = (System.currentTimeMillis() / 1000) + signer.clockOffsetSeconds
        return exp <= now + skewSeconds
    }

    /** The access token's JWT `exp`, or `null` for an opaque or malformed token. */
    @OptIn(ExperimentalEncodingApi::class)
    private fun accessTokenExpiry(): Long? = try {
        val parts = session.accessToken.split('.')
        if (parts.size != 3) {
            null
        } else {
            val payload = Base64.UrlSafe.decode(parts[1].padBase64()).toString(Charsets.UTF_8)
            json.parseToJsonElement(payload).jsonObject["exp"]?.jsonPrimitive?.long
        }
    } catch (_: Exception) {
        null
    }

    private fun String.padBase64(): String = when (length % 4) {
        0 -> this
        2 -> "$this=="
        3 -> "$this="
        else -> this
    }

    private suspend fun refreshTokens(): Boolean {
        val response = sendRefresh(failureContext = "")
        if (response.isSuccess()) return applyRefreshResponse(response)

        // Classify before deciding to retry: both the nonce-retry decision and
        // the terminal split need the `error` field.
        val error = parseErrorField(response)

        // A rotated DPoP-Nonce on a 401/400 is a recoverable use_dpop_nonce
        // signal — retry once with the new nonce (not a token rejection).
        //
        // An explicit `invalid_grant` is terminal even when the server ALSO
        // rotated a nonce: retrying would re-POST the refresh token the server
        // just rejected, and replaying a single-use refresh token is precisely
        // what trips AT Proto reuse detection. Classify first, retry second.
        //
        // Only the authorization server issues nonce challenges. A nonce on an
        // answer from anything in between is not one to sign with.
        val nonceHeader = response.dpopNonce
        val isNonceRetryStatus = response.status == HTTP_UNAUTHORIZED || response.status == HTTP_BAD_REQUEST
        val canRetryWithNonce =
            response.isFromAuthorizationServer() && error != INVALID_GRANT && isNonceRetryStatus && nonceHeader != null
        if (canRetryWithNonce) {
            signer.calibrateClockFromHeader(response.serverDate)
            authServerNonce = nonceHeader
            return refreshTokensWithNonce()
        }

        failRefresh(response, error)
    }

    private suspend fun refreshTokensWithNonce(): Boolean {
        val response = sendRefresh(failureContext = " (nonce retry)")
        if (response.isSuccess()) return applyRefreshResponse(response)
        failRefresh(response, parseErrorField(response))
    }

    /** Tokens only ever come from the authorization server, whatever a status says. */
    private fun TokenEndpointResponse.isSuccess(): Boolean = status == HTTP_OK && isFromAuthorizationServer()

    private fun TokenEndpointResponse.isFromAuthorizationServer(): Boolean = origin == TokenEndpointResponseOrigin.AuthorizationServer

    /**
     * Extracts the OAuth `error` field (RFC 6749 §5.2) from a response body.
     * Returns `null` for an unparseable body (proxy/captive-portal HTML),
     * which the caller must treat as transient rather than as a rejection.
     */
    private fun parseErrorField(response: TokenEndpointResponse): String? = runCatching {
        json.parseToJsonElement(response.body).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    private suspend fun sendRefresh(failureContext: String): TokenEndpointResponse {
        val proof = signer.sign(
            method = "POST",
            url = session.tokenEndpoint,
            nonce = authServerNonce,
        )
        val outcomeInDoubt = priorRefreshOutcomeUnknown
        markRefreshInDoubt()
        val response = try {
            refreshTransport.send(
                TokenEndpointRequest(
                    kind = TokenEndpointRequestKind.Refresh,
                    endpointUrl = session.tokenEndpoint,
                    issuer = session.issuer ?: issuerFromEndpoint(session.tokenEndpoint),
                    formParameters = mapOf(
                        "grant_type" to "refresh_token",
                        "refresh_token" to session.refreshToken,
                        "client_id" to (session.clientId ?: ""),
                    ),
                    dpopProof = proof,
                    clientAuthKid = session.clientAuthKid,
                    outcomeInDoubt = outcomeInDoubt,
                ),
            )
        } catch (e: CancellationException) {
            throw e // never swallow cooperative cancellation
        } catch (e: Exception) {
            // Transient network failure — the refresh token isn't necessarily
            // invalid. Retryable; do NOT clear the session. Pre-send failures
            // (DNS, connect) are flagged: the request never reached the server,
            // so the single-use refresh token was provably NOT consumed and a
            // retry with the same token is safe. Post-send failures (response
            // lost) stay ambiguous — the server may have already rotated.
            val notSent = e is TokenRequestNotSentException || isPreSendFailure(e)
            // A request that never left adds no doubt of its own: whatever was
            // known about this token before the attempt still holds.
            if (notSent) priorRefreshOutcomeUnknown = outcomeInDoubt
            val notConsumed = if (notSent) " (request not sent; refresh token not consumed)" else ""
            throw OAuthRefreshFailedException("Refresh request failed$failureContext$notConsumed", e)
        }
        // The authorization server answered, so this token's state is known
        // again: either it was rotated (success) or it was not consumed.
        if (response.isFromAuthorizationServer()) priorRefreshOutcomeUnknown = false
        return response
    }

    /**
     * Records, durably and BEFORE the request leaves, that this refresh
     * token's outcome is about to become unknown. If the response is then
     * lost — or the process dies waiting for it — the next attempt can tell
     * the transport the token may already be spent, so it is not sent by a
     * route that would replay it. A no-op for a transport that does not ask
     * for this, and when the mark is already on disk (a nonce retry).
     */
    private suspend fun markRefreshInDoubt() {
        if (!refreshTransport.tracksRefreshOutcome) return
        priorRefreshOutcomeUnknown = true
        if (session.refreshInDoubt) return
        session = session.copy(refreshInDoubt = true, authServerNonce = authServerNonce, pdsNonce = pdsNonce)
        try {
            sessionStore.save(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The refresh itself is still worth attempting: a store that
            // cannot take this write is reported, not allowed to strand the
            // session on an expired access token.
            onPersistFailure(e)
        }
    }

    /**
     * Pre-send only when every attempt failed before reaching the server.
     * OkHttp re-sends a request whose connection dropped mid-flight and keeps
     * the earlier failure as suppressed; a DNS failure on that retry says
     * nothing about the first POST, which may already have consumed the token.
     * kotlinx.coroutines can re-create an exception with the original as its
     * cause, so a same-type cause's suppressed list counts too, but not the
     * rest of the chain: a genuine DNS failure has a platform cause of its own.
     */
    private fun isPreSendFailure(e: Exception): Boolean {
        val original = e.cause?.takeIf { it::class == e::class }
        val earlier = e.suppressed.toList() + (original?.suppressed?.toList() ?: emptyList())
        return e.isPreSendType() && earlier.all { it.isPreSendType() }
    }

    private fun Throwable.isPreSendType(): Boolean = this is UnknownHostException ||
        this is ConnectException ||
        this is UnresolvedAddressException ||
        this is ConnectTimeoutException

    /** Callers guarantee [response] is a success from the authorization server; anything else goes to [failRefresh]. */
    private suspend fun applyRefreshResponse(response: TokenEndpointResponse): Boolean {
        val tokenResponse = json.decodeFromString(TokenResponse.serializer(), response.body)
        session = session.copy(
            accessToken = tokenResponse.access_token,
            refreshToken = tokenResponse.refresh_token ?: session.refreshToken,
            authServerNonce = authServerNonce,
            pdsNonce = pdsNonce,
            refreshInDoubt = false,
            clientAuthMethod = response.clientAuthMethod ?: session.clientAuthMethod,
            // Recorded once, and only when the client is reported confidential:
            // while it is public the server binds the session to nothing. Never
            // replaced, because the server deletes a session refreshed with a
            // different key.
            clientAuthKid = session.clientAuthKid
                ?: response.clientAuthKid.takeIf { response.clientAuthMethod == CLIENT_AUTH_PRIVATE_KEY_JWT },
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
     * Terminates a refresh that did not succeed. Clears the session and throws
     * [OAuthSessionExpiredException] ONLY when the authorization server itself
     * reports the refresh token is revoked/expired (`error=invalid_grant`,
     * RFC 6749 §5.2).
     *
     * Every other failure — 5xx, 429, 408, a proxy/captive-portal body, any
     * non-`invalid_grant` error, or ANY answer that did not come from the
     * authorization server — is treated as transient: throw the retryable
     * [OAuthRefreshFailedException] and leave the session intact so a later
     * request can refresh cleanly. This is what keeps a flaky connection, or
     * a misbehaving backend in front of the server, from silently signing the
     * user out.
     */
    private suspend fun failRefresh(response: TokenEndpointResponse, error: String?): Nothing {
        val status = response.status
        if (!response.isFromAuthorizationServer()) {
            throw OAuthRefreshFailedException(
                "Refresh not answered by the authorization server (HTTP $status${error?.let { ", error=$it" } ?: ""})",
            )
        }
        if (error == INVALID_GRANT) {
            // Latch BEFORE clearing so any waiter that acquires the mutex next
            // short-circuits instead of replaying this now-dead token.
            sessionTerminallyExpired = true
            sessionStore.clear()
            throw OAuthSessionExpiredException("Refresh token revoked (invalid_grant, HTTP $status)")
        }
        clearRefreshInDoubt()
        throw OAuthRefreshFailedException(
            "Refresh failed (HTTP $status${error?.let { ", error=$it" } ?: ""})",
        )
    }

    /**
     * The authorization server rejected the request without consuming the
     * refresh token, so the doubt recorded by [markRefreshInDoubt] is over.
     * Best effort: a mark left on disk only makes the next attempt more
     * cautious than it needs to be.
     */
    private suspend fun clearRefreshInDoubt() {
        if (!session.refreshInDoubt) return
        session = session.copy(refreshInDoubt = false)
        try {
            sessionStore.save(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onPersistFailure(e)
        }
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
private const val INVALID_TOKEN = "invalid_token"
private const val USE_DPOP_NONCE = "use_dpop_nonce"
private const val HTTP_OK = 200
private const val HTTP_BAD_REQUEST = 400
private const val HTTP_UNAUTHORIZED = 401

// RFC 7235 auth-param: a case-insensitive name, optional whitespace around
// `=`, and a quoted-string or token value. `\b` before `error` and `=` after
// it keep `error_description` from matching.
private val CHALLENGE_ERROR = Regex("""\berror\s*=\s*(?:"([^"]*)"|([^\s,"]+))""", RegexOption.IGNORE_CASE)

/**
 * One refresh lock per account for the whole process, keyed by DID as the
 * reference client keys its lock. A lock per provider let two clients built
 * from the same session POST the same single-use refresh token at once.
 */
private object RefreshLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun forDid(did: String): Mutex = locks.computeIfAbsent(did) { Mutex() }
}
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
