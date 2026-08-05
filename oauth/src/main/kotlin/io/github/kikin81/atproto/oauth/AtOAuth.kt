package io.github.kikin81.atproto.oauth

import io.github.kikin81.atproto.runtime.XrpcClient
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * AT Protocol OAuth 2.0 flow orchestrator for public clients.
 *
 * Implements the full authorization flow: handle → DID → PDS →
 * authorization server discovery, PAR with PKCE + DPoP, browser-based
 * authorization, token exchange, and session management with transparent
 * refresh.
 *
 * Supports two entry points:
 *
 * - [beginLogin] — keyed on a user's handle/DID; full discovery chain.
 * - [beginSignup] — keyed on a known auth server (default `bsky.social`);
 *   short-circuits discovery and sends OIDC `prompt=create` so the auth
 *   server renders its signup UI. Identity is hydrated post-token-exchange.
 *
 * Both flows complete via [completeLogin] — the redirect handling is shared.
 *
 * ## Consumer usage
 *
 * ```kotlin
 * val oauth = AtOAuth(
 *     clientMetadataUrl = "https://example.app/oauth/client-metadata.json",
 *     redirectUri = "app.example:/oauth-redirect",
 *     sessionStore = mySessionStore,
 *     httpClient = myKtorClient,
 * )
 * // Login path:
 * val authUrl = oauth.beginLogin("alice.bsky.social")
 * // Signup path:
 * val signupUrl = oauth.beginSignup() // defaults to bsky.social
 * // Both paths: open the URL in a browser, then call:
 * oauth.completeLogin(redirectUri)
 * val client = oauth.createClient()
 * ```
 *
 * ## Requesting additional scopes
 *
 * To request umbrella scopes beyond the default `atproto transition:generic`
 * (e.g. `transition:chat.bsky` for Bluesky chat, `transition:email` for
 * email), pass them via [scope]. The value must be a subset of the `scope`
 * field declared in the hosted `client-metadata.json` document.
 *
 * ```kotlin
 * val oauth = AtOAuth(
 *     clientMetadataUrl = "...",
 *     redirectUri = "app.example:/oauth-redirect",
 *     sessionStore = ...,
 *     httpClient = ...,
 *     scope = "atproto transition:generic transition:chat.bsky",
 * )
 * ```
 *
 * The value must be a subset of the `scope` field declared in the hosted
 * client metadata document.
 */
class AtOAuth(
    private val clientMetadataUrl: String,
    private val redirectUri: String,
    private val sessionStore: OAuthSessionStore,
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val scope: String = "atproto transition:generic",
    /**
     * Forwarded to every [DpopAuthProvider] built by [createClient]: invoked
     * when persisting a rotated session fails even after a retry. See
     * [DpopAuthProvider]'s `onPersistFailure` for the durability implications.
     */
    private val onSessionPersistFailure: (Throwable) -> Unit = {},
    /**
     * Where the in-flight login lives between `beginLogin` / `beginSignup` and
     * [completeLogin].
     *
     * Defaults to memory, preserving this module's historical behaviour.
     * **Android consumers should supply a durable, encrypted store**: the
     * authorization step runs in a browser, and an OS kill during it otherwise
     * strands the login permanently — the callback returns to a process that
     * has no record of it. See [PendingAuthStore].
     */
    private val pendingStore: PendingAuthStore = InMemoryPendingAuthStore(),
    /** Injection seam for [PENDING_AUTH_TTL_MILLIS] expiry; overridden in tests. */
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Starts the OAuth login flow.
     *
     * 1. Resolves the handle/DID to an authorization server via the
     *    AT Protocol discovery chain.
     * 2. Generates PKCE verifier + challenge (S256).
     * 3. Generates a fresh DPoP EC P-256 keypair.
     * 4. Pushes a PAR request (handling the expected `use_dpop_nonce`
     *    401 → retry cycle transparently).
     * 5. Returns the authorization URL to open in a browser.
     *
     * @param handleOrDid The user's handle (e.g. "alice.bsky.social") or DID.
     * @return The authorization URL to open in a Custom Tab or system browser.
     */
    suspend fun beginLogin(handleOrDid: String): String {
        val discovery = DiscoveryChain(httpClient, json)
        val metadata = discovery.resolve(handleOrDid)
        return startAuthFlow(
            metadata = metadata,
            loginHint = handleOrDid,
            prompt = null,
            flowOrigin = FlowOrigin.Login,
        )
    }

    /**
     * Starts the OAuth signup flow against a known authorization server.
     *
     * Unlike [beginLogin], no handle or DID is required: the auth server
     * is named directly, the discovery chain is short-circuited to fetch
     * only its metadata, and PAR carries OIDC `prompt=create` so the
     * server renders its signup UI. After the user signs up in the
     * browser and the redirect lands, [completeLogin] derives the new
     * account's DID from the token-response `sub`, then resolves the
     * handle and PDS URL from the DID document.
     *
     * @param authServer The authorization server hostname or URL.
     *   Defaults to `bsky.social`. Accepts either a bare hostname or a
     *   full `https://` URL.
     * @param requirePromptCreateSupport When `true` (the default), the
     *   module verifies that the auth server's metadata advertises
     *   `"create"` in `prompt_values_supported` and throws
     *   [OAuthSignupNotSupportedException] otherwise. Pass `false` to
     *   bypass the check for non-conformant entryways.
     * @return The authorization URL to open in a Custom Tab or system browser.
     */
    suspend fun beginSignup(
        authServer: String = "bsky.social",
        requirePromptCreateSupport: Boolean = true,
    ): String {
        val discovery = DiscoveryChain(httpClient, json)
        val metadata = discovery.resolveKnownAuthServer(authServer)
        if (requirePromptCreateSupport && !metadata.promptValuesSupported.contains("create")) {
            throw OAuthSignupNotSupportedException(
                authServerUrl = metadata.issuer,
                advertisedPromptValues = metadata.promptValuesSupported,
            )
        }
        return startAuthFlow(
            metadata = metadata,
            loginHint = null,
            prompt = "create",
            flowOrigin = FlowOrigin.Signup,
        )
    }

    private suspend fun startAuthFlow(
        metadata: AuthServerMetadata,
        loginHint: String?,
        prompt: String?,
        flowOrigin: FlowOrigin,
    ): String {
        val signer = DpopSigner.generate()
        val codeVerifier = Pkce.generateVerifier()
        val codeChallenge = Pkce.computeChallenge(codeVerifier)
        val state = generateState()

        val parResult = pushAuthorizationRequest(
            metadata = metadata,
            signer = signer,
            codeChallenge = codeChallenge,
            state = state,
            redirectUri = redirectUri,
            loginHint = loginHint,
            prompt = prompt,
        )

        // Persisted BEFORE the caller gets the URL to open: once the browser is
        // foregrounded this process may be killed at any moment, and anything
        // written after that point is a race we would lose.
        val exported = signer.exportKeyPair()
        pendingStore.save(
            PendingAuth(
                state = state,
                codeVerifier = codeVerifier,
                redirectUri = redirectUri,
                flowOrigin = flowOrigin.name,
                authServerNonce = parResult.dpopNonce,
                dpopPrivateKey = exported.privateKeyEncoded,
                dpopPublicKey = exported.publicKeyEncoded,
                issuer = metadata.issuer,
                authorizationEndpoint = metadata.authorizationEndpoint,
                tokenEndpoint = metadata.tokenEndpoint,
                parEndpoint = metadata.parEndpoint,
                revocationEndpoint = metadata.revocationEndpoint,
                pdsUrl = metadata.pdsUrl,
                did = metadata.did,
                handle = metadata.handle,
                promptValuesSupported = metadata.promptValuesSupported,
                createdAtEpochMillis = nowMillis(),
            ),
        )

        return "${metadata.authorizationEndpoint}?client_id=$clientMetadataUrl&request_uri=${parResult.requestUri}"
    }

    /**
     * Completes the OAuth login flow after the browser redirects back.
     *
     * 1. Validates the `state` parameter matches.
     * 2. Validates the `iss` parameter matches the discovered auth server.
     * 3. Exchanges the authorization code for tokens with PKCE + DPoP.
     * 4. For the login flow, verifies the `sub` (DID) in the token
     *    response matches the resolved DID from discovery. For the signup
     *    flow, accepts `sub` as authoritative and hydrates handle + PDS
     *    URL from the new DID document.
     * 5. Persists the session.
     *
     * @param redirectUri The full redirect URI from the browser callback
     *   (e.g. `myapp://oauth/callback?code=...&state=...&iss=...`).
     */
    suspend fun completeLogin(redirectUri: String) {
        val stored = pendingStore.load()
            ?: throw OAuthException("No pending login — call beginLogin or beginSignup first")
        // Consume before validating: the record is single-use, and clearing it
        // up front means a malformed or replayed callback cannot be retried
        // against the same PKCE verifier. Matches the pre-persistence behaviour.
        pendingStore.clear()

        // A persisted record outlives the process, so it can also outlive the
        // authorization code's server-side lifetime. Past the TTL there is
        // nothing to complete — fail with a distinguishable message rather than
        // letting the token endpoint reject it as an opaque invalid_grant.
        val age = nowMillis() - stored.createdAtEpochMillis
        if (age > PENDING_AUTH_TTL_MILLIS) {
            throw OAuthException(
                "Pending login expired after ${age}ms (limit ${PENDING_AUTH_TTL_MILLIS}ms) — start the login again",
            )
        }

        val pending = stored.toPendingAuthState()

        val params = parseRedirectParams(redirectUri)
        val code = params["code"] ?: throw OAuthException("Missing 'code' in redirect URI")
        val returnedState = params["state"] ?: throw OAuthException("Missing 'state' in redirect URI")
        val returnedIss = params["iss"]

        if (returnedState != pending.state) {
            throw OAuthException("State mismatch: expected '${pending.state}', got '$returnedState'")
        }
        if (returnedIss != null && returnedIss != pending.metadata.issuer) {
            throw OAuthException(
                "Issuer mismatch: expected '${pending.metadata.issuer}', got '$returnedIss'. " +
                    "This may indicate a mix-up attack.",
            )
        }

        val tokenResponse = exchangeCode(
            metadata = pending.metadata,
            signer = pending.signer,
            code = code,
            codeVerifier = pending.codeVerifier,
            redirectUri = pending.redirectUri,
            initialNonce = pending.authServerNonce,
        )

        val exported = pending.signer.exportKeyPair()
        val refreshToken = tokenResponse.refresh_token
            ?: throw OAuthException("No refresh_token in token response")

        fun buildSession(did: String?, handle: String?, pdsUrl: String?): OAuthSession = OAuthSession(
            accessToken = tokenResponse.access_token,
            refreshToken = refreshToken,
            did = did,
            handle = handle,
            pdsUrl = pdsUrl,
            tokenEndpoint = pending.metadata.tokenEndpoint,
            revocationEndpoint = pending.metadata.revocationEndpoint,
            clientId = clientMetadataUrl,
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            clockOffsetSeconds = pending.signer.clockOffsetSeconds,
        )

        when (pending.flowOrigin) {
            FlowOrigin.Login -> {
                if (tokenResponse.sub != null && tokenResponse.sub != pending.metadata.did) {
                    throw OAuthAccountMismatchException(
                        "Token response sub '${tokenResponse.sub}' does not match resolved DID '${pending.metadata.did}'",
                    )
                }
                // Login already has fully resolved identity — one write is enough.
                sessionStore.save(
                    buildSession(
                        did = pending.metadata.did,
                        handle = pending.metadata.handle,
                        pdsUrl = pending.metadata.pdsUrl,
                    ),
                )
            }
            FlowOrigin.Signup -> {
                val signupDid = tokenResponse.sub
                    ?: throw OAuthException("Token response from signup flow has no 'sub' field")
                // Crash-resilience: persist tokens + DID before hydration runs. If
                // the process dies during the bounded backoff below, the session is
                // recoverable (the consumer can re-resolve identity off the DID).
                sessionStore.save(buildSession(did = signupDid, handle = null, pdsUrl = null))

                val hydrated = DiscoveryChain(httpClient, json).hydrateIdentityFromDid(signupDid)
                // Re-persist with whatever resolved. If both fields are still null
                // after the retry budget exhausts, this is effectively a no-op write
                // — kept for clarity that hydration completed.
                sessionStore.save(
                    buildSession(
                        did = signupDid,
                        handle = hydrated.handle,
                        pdsUrl = hydrated.pdsUrl,
                    ),
                )
            }
        }
    }

    /**
     * Creates an authenticated [XrpcClient] from the persisted session.
     * The client uses [DpopAuthProvider] for DPoP proof-of-possession
     * on every request.
     */
    suspend fun createClient(): XrpcClient {
        val session = sessionStore.load()
            ?: throw OAuthException("No session — call beginLogin/completeLogin first or restore a persisted session")
        val pdsUrl = session.pdsUrl
            ?: throw OAuthException(
                "Session has no pdsUrl. This usually means a signup-flow session whose " +
                    "post-signup DID resolution did not complete; the PDS endpoint is unknown.",
            )
        val signer = DpopSigner.fromExported(
            DpopSigner.ExportedKeyPair(session.dpopPrivateKey, session.dpopPublicKey),
        )
        signer.clockOffsetSeconds = session.clockOffsetSeconds
        val authProvider = DpopAuthProvider(
            session = session,
            signer = signer,
            sessionStore = sessionStore,
            refreshClient = httpClient,
            onPersistFailure = onSessionPersistFailure,
        )
        return XrpcClient(
            baseUrl = pdsUrl,
            httpClient = httpClient,
            authProvider = authProvider,
        )
    }

    /**
     * Revokes the current tokens on the authorization server, then clears
     * the persisted session. If revocation fails (network error, missing
     * endpoint), the local session is still cleared.
     */
    suspend fun logout() {
        val session = sessionStore.load()
        if (session != null) {
            revokeToken(session)
        }
        sessionStore.clear()
    }

    private suspend fun revokeToken(session: OAuthSession) {
        val endpoint = session.revocationEndpoint ?: return
        val clientId = session.clientId ?: return
        val signer = DpopSigner.fromExported(
            DpopSigner.ExportedKeyPair(session.dpopPrivateKey, session.dpopPublicKey),
        )
        signer.clockOffsetSeconds = session.clockOffsetSeconds
        try {
            val proof = signer.sign(method = "POST", url = endpoint, nonce = session.authServerNonce)
            val response = httpClient.submitForm(
                url = endpoint,
                formParameters = Parameters.build {
                    append("token", session.refreshToken)
                    append("token_type_hint", "refresh_token")
                    append("client_id", clientId)
                },
            ) {
                header("DPoP", proof)
            }
            // Revocation may require a nonce retry
            if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.BadRequest) {
                val nonce = response.headers["DPoP-Nonce"]
                if (nonce != null) {
                    signer.calibrateClockFromHeader(response.headers["Date"])
                    val retryProof = signer.sign(method = "POST", url = endpoint, nonce = nonce)
                    httpClient.submitForm(
                        url = endpoint,
                        formParameters = Parameters.build {
                            append("token", session.refreshToken)
                            append("token_type_hint", "refresh_token")
                            append("client_id", clientId)
                        },
                    ) {
                        header("DPoP", retryProof)
                    }
                }
            }
        } catch (_: Exception) {
            // Best-effort: if revocation fails, we still clear locally
        }
    }

    // --- Internal helpers ---

    private suspend fun pushAuthorizationRequest(
        metadata: AuthServerMetadata,
        signer: DpopSigner,
        codeChallenge: String,
        state: String,
        redirectUri: String,
        loginHint: String?,
        prompt: String?,
    ): ParResult {
        // First attempt: no nonce (expected to fail with use_dpop_nonce)
        val firstProof = signer.sign(method = "POST", url = metadata.parEndpoint)
        val firstResponse = httpClient.submitForm(
            url = metadata.parEndpoint,
            formParameters = parParams(codeChallenge, state, redirectUri, loginHint, prompt),
        ) {
            header("DPoP", firstProof)
        }

        if (firstResponse.status == HttpStatusCode.OK) {
            val body = json.decodeFromString(ParResponse.serializer(), firstResponse.bodyAsText())
            return ParResult(requestUri = body.request_uri, dpopNonce = firstResponse.headers["DPoP-Nonce"])
        }

        // Expected: 401 with use_dpop_nonce. Also calibrate the clock
        // from the server's Date header to fix iat drift on devices
        // whose clock is off.
        signer.calibrateClockFromHeader(firstResponse.headers["Date"])

        val nonce = firstResponse.headers["DPoP-Nonce"]
            ?: throw OAuthException("PAR failed (${firstResponse.status}) and no DPoP-Nonce header in response")

        // Retry with nonce + calibrated clock
        val retryProof = signer.sign(method = "POST", url = metadata.parEndpoint, nonce = nonce)
        val retryResponse = httpClient.submitForm(
            url = metadata.parEndpoint,
            formParameters = parParams(codeChallenge, state, redirectUri, loginHint, prompt),
        ) {
            header("DPoP", retryProof)
        }

        if (retryResponse.status != HttpStatusCode.OK && retryResponse.status != HttpStatusCode.Created) {
            throw OAuthException("PAR retry failed with HTTP ${retryResponse.status}: ${retryResponse.bodyAsText()}")
        }

        val body = json.decodeFromString(ParResponse.serializer(), retryResponse.bodyAsText())
        // Prefer the nonce echoed on the successful retry; the `?: nonce`
        // fallback is defense-in-depth — atproto auth servers echo DPoP-Nonce
        // on every response per the spec, so it should be unreachable.
        return ParResult(
            requestUri = body.request_uri,
            dpopNonce = retryResponse.headers["DPoP-Nonce"] ?: nonce,
        )
    }

    private fun parParams(
        codeChallenge: String,
        state: String,
        redirectUri: String,
        loginHint: String?,
        prompt: String?,
    ): Parameters = Parameters.build {
        append("client_id", clientMetadataUrl)
        append("response_type", "code")
        append("redirect_uri", redirectUri)
        append("scope", scope)
        append("state", state)
        append("code_challenge", codeChallenge)
        append("code_challenge_method", "S256")
        if (loginHint != null) append("login_hint", loginHint)
        if (prompt != null) append("prompt", prompt)
    }

    private suspend fun exchangeCode(
        metadata: AuthServerMetadata,
        signer: DpopSigner,
        code: String,
        codeVerifier: String,
        redirectUri: String,
        initialNonce: String?,
    ): TokenResponse {
        // The authorization code is single-use: bsky.social's auth server
        // consumes it even when it rejects the request for use_dpop_nonce.
        // Sign the first proof with the nonce we captured from PAR so the
        // first attempt succeeds; the retry below still covers the rare
        // case where the nonce has rotated since PAR.
        val proof = signer.sign(method = "POST", url = metadata.tokenEndpoint, nonce = initialNonce)
        val response = httpClient.submitForm(
            url = metadata.tokenEndpoint,
            formParameters = Parameters.build {
                append("grant_type", "authorization_code")
                append("code", code)
                append("code_verifier", codeVerifier)
                append("redirect_uri", redirectUri)
                append("client_id", clientMetadataUrl)
            },
        ) {
            header("DPoP", proof)
        }

        // Token endpoint returns 400 (not 401) for use_dpop_nonce.
        // Also handle 401 defensively in case the server behavior changes.
        val needsNonce = response.status == HttpStatusCode.BadRequest ||
            response.status == HttpStatusCode.Unauthorized
        if (needsNonce) {
            val nonce = response.headers["DPoP-Nonce"]
            if (nonce != null) {
                signer.calibrateClockFromHeader(response.headers["Date"])
                val retryProof = signer.sign(method = "POST", url = metadata.tokenEndpoint, nonce = nonce)
                val retryResponse = httpClient.submitForm(
                    url = metadata.tokenEndpoint,
                    formParameters = Parameters.build {
                        append("grant_type", "authorization_code")
                        append("code", code)
                        append("code_verifier", codeVerifier)
                        append("redirect_uri", redirectUri)
                        append("client_id", clientMetadataUrl)
                    },
                ) {
                    header("DPoP", retryProof)
                }
                if (retryResponse.status != HttpStatusCode.OK) {
                    throw OAuthException("Token exchange retry failed: HTTP ${retryResponse.status}")
                }
                return json.decodeFromString(TokenResponse.serializer(), retryResponse.bodyAsText())
            }
            throw OAuthException("Token exchange failed: HTTP ${response.status} without DPoP-Nonce: ${response.bodyAsText()}")
        }

        if (response.status != HttpStatusCode.OK) {
            throw OAuthException("Token exchange failed: HTTP ${response.status}: ${response.bodyAsText()}")
        }

        return json.decodeFromString(TokenResponse.serializer(), response.bodyAsText())
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun generateState(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.UrlSafe.encode(bytes).trimEnd('=')
    }

    private fun parseRedirectParams(uri: String): Map<String, String> {
        val queryStart = uri.indexOf('?')
        if (queryStart < 0) return emptyMap()
        return uri.substring(queryStart + 1)
            .split('&')
            .associate { param ->
                val eq = param.indexOf('=')
                if (eq < 0) {
                    param to ""
                } else {
                    param.substring(0, eq) to java.net.URLDecoder.decode(param.substring(eq + 1), "UTF-8")
                }
            }
    }

    /**
     * Rehydrate the in-memory working shape from its persisted form. The DPoP
     * keypair round-trips through PKCS8/X509 encoding, the same path
     * [OAuthSession] uses.
     */
    private fun PendingAuth.toPendingAuthState(): PendingAuthState = PendingAuthState(
        metadata = AuthServerMetadata(
            issuer = issuer,
            authorizationEndpoint = authorizationEndpoint,
            tokenEndpoint = tokenEndpoint,
            parEndpoint = parEndpoint,
            revocationEndpoint = revocationEndpoint,
            pdsUrl = pdsUrl,
            did = did,
            handle = handle,
            promptValuesSupported = promptValuesSupported,
        ),
        signer = DpopSigner.fromExported(
            DpopSigner.ExportedKeyPair(
                privateKeyEncoded = dpopPrivateKey,
                publicKeyEncoded = dpopPublicKey,
            ),
        ),
        codeVerifier = codeVerifier,
        state = state,
        redirectUri = redirectUri,
        // Not valueOf directly: this record round-trips through disk and can
        // outlive an app update mid-roundtrip, so a value written by a
        // different library version is reachable. Surface that as the SDK's
        // own error type rather than letting a raw IllegalArgumentException
        // escape past callers' OAuthException handling.
        flowOrigin = FlowOrigin.entries.firstOrNull { it.name == flowOrigin }
            ?: throw OAuthException("Unrecognized pending-login flow origin '$flowOrigin' — start the login again"),
        authServerNonce = authServerNonce,
    )

    companion object {
        /**
         * How long a persisted pending login stays completable.
         *
         * Bounded by the authorization code's own server-side lifetime (minutes
         * in practice), with generous headroom for a slow password entry on a
         * third-party PDS. Past this, the record is dead weight and its PKCE
         * verifier + DPoP private key are needless exposure at rest.
         */
        const val PENDING_AUTH_TTL_MILLIS: Long = 15 * 60 * 1000L
    }

    internal enum class FlowOrigin { Login, Signup }

    private data class PendingAuthState(
        val metadata: AuthServerMetadata,
        val signer: DpopSigner,
        val codeVerifier: String,
        val state: String,
        val redirectUri: String,
        val flowOrigin: FlowOrigin,
        val authServerNonce: String?,
    )

    private data class ParResult(
        val requestUri: String,
        val dpopNonce: String?,
    )
}

@kotlinx.serialization.Serializable
internal data class ParResponse(
    val request_uri: String,
    val expires_in: Long? = null,
)
