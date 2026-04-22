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
 * ## Consumer usage
 *
 * ```kotlin
 * val oauth = AtOAuth(
 *     clientMetadataUrl = "https://app.example.com/oauth/client-metadata.json",
 *     sessionStore = mySessionStore,
 *     httpClient = myKtorClient,
 * )
 * // Step 1: get the authorization URL
 * val authUrl = oauth.beginLogin("alice.bsky.social")
 * // Step 2: open authUrl in a browser (Custom Tabs on Android)
 * // Step 3: capture the redirect URI
 * oauth.completeLogin(redirectUri)
 * // Step 4: use the authenticated client
 * val client = oauth.createClient()
 * FeedService(client).getTimeline()
 * ```
 */
class AtOAuth(
    private val clientMetadataUrl: String,
    private val sessionStore: OAuthSessionStore,
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    // Transient state during the login flow (between beginLogin and completeLogin)
    private var pendingState: PendingAuthState? = null

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
        val signer = DpopSigner.generate()
        val codeVerifier = Pkce.generateVerifier()
        val codeChallenge = Pkce.computeChallenge(codeVerifier)
        val state = generateState()
        val redirectUri = extractRedirectUri()

        val requestUri = pushAuthorizationRequest(
            metadata = metadata,
            signer = signer,
            codeChallenge = codeChallenge,
            state = state,
            redirectUri = redirectUri,
            loginHint = handleOrDid,
        )

        pendingState = PendingAuthState(
            metadata = metadata,
            signer = signer,
            codeVerifier = codeVerifier,
            state = state,
            redirectUri = redirectUri,
        )

        return "${metadata.authorizationEndpoint}?client_id=$clientMetadataUrl&request_uri=$requestUri"
    }

    /**
     * Completes the OAuth login flow after the browser redirects back.
     *
     * 1. Validates the `state` parameter matches.
     * 2. Validates the `iss` parameter matches the discovered auth server.
     * 3. Exchanges the authorization code for tokens with PKCE + DPoP.
     * 4. Verifies the `sub` (DID) in the token response matches the
     *    resolved DID from discovery.
     * 5. Persists the session.
     *
     * @param redirectUri The full redirect URI from the browser callback
     *   (e.g. `myapp://oauth/callback?code=...&state=...&iss=...`).
     */
    suspend fun completeLogin(redirectUri: String) {
        val pending = pendingState ?: throw OAuthException("No pending login — call beginLogin first")
        pendingState = null

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
        )

        if (tokenResponse.sub != null && tokenResponse.sub != pending.metadata.did) {
            throw OAuthAccountMismatchException(
                "Token response sub '${tokenResponse.sub}' does not match resolved DID '${pending.metadata.did}'",
            )
        }

        val exported = pending.signer.exportKeyPair()
        val session = OAuthSession(
            accessToken = tokenResponse.access_token,
            refreshToken = tokenResponse.refresh_token ?: throw OAuthException("No refresh_token in token response"),
            did = pending.metadata.did,
            handle = pending.metadata.handle,
            pdsUrl = pending.metadata.pdsUrl,
            tokenEndpoint = pending.metadata.tokenEndpoint,
            revocationEndpoint = pending.metadata.revocationEndpoint,
            clientId = clientMetadataUrl,
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            clockOffsetSeconds = pending.signer.clockOffsetSeconds,
        )
        sessionStore.save(session)
    }

    /**
     * Creates an authenticated [XrpcClient] from the persisted session.
     * The client uses [DpopAuthProvider] for DPoP proof-of-possession
     * on every request.
     */
    suspend fun createClient(): XrpcClient {
        val session = sessionStore.load()
            ?: throw OAuthException("No session — call beginLogin/completeLogin first or restore a persisted session")
        val signer = DpopSigner.fromExported(
            DpopSigner.ExportedKeyPair(session.dpopPrivateKey, session.dpopPublicKey),
        )
        signer.clockOffsetSeconds = session.clockOffsetSeconds
        val authProvider = DpopAuthProvider(
            session = session,
            signer = signer,
            sessionStore = sessionStore,
            refreshClient = httpClient,
        )
        return XrpcClient(
            baseUrl = session.pdsUrl,
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
        loginHint: String,
    ): String {
        // First attempt: no nonce (expected to fail with use_dpop_nonce)
        val firstProof = signer.sign(method = "POST", url = metadata.parEndpoint)
        val firstResponse = httpClient.submitForm(
            url = metadata.parEndpoint,
            formParameters = parParams(codeChallenge, state, redirectUri, loginHint),
        ) {
            header("DPoP", firstProof)
        }

        if (firstResponse.status == HttpStatusCode.OK) {
            val body = json.decodeFromString(ParResponse.serializer(), firstResponse.bodyAsText())
            return body.request_uri
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
            formParameters = parParams(codeChallenge, state, redirectUri, loginHint),
        ) {
            header("DPoP", retryProof)
        }

        if (retryResponse.status != HttpStatusCode.OK && retryResponse.status != HttpStatusCode.Created) {
            throw OAuthException("PAR retry failed with HTTP ${retryResponse.status}: ${retryResponse.bodyAsText()}")
        }

        val body = json.decodeFromString(ParResponse.serializer(), retryResponse.bodyAsText())
        return body.request_uri
    }

    private fun parParams(
        codeChallenge: String,
        state: String,
        redirectUri: String,
        loginHint: String,
    ): Parameters = Parameters.build {
        append("client_id", clientMetadataUrl)
        append("response_type", "code")
        append("redirect_uri", redirectUri)
        append("scope", "atproto transition:generic")
        append("state", state)
        append("code_challenge", codeChallenge)
        append("code_challenge_method", "S256")
        append("login_hint", loginHint)
    }

    private suspend fun exchangeCode(
        metadata: AuthServerMetadata,
        signer: DpopSigner,
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): TokenResponse {
        val proof = signer.sign(method = "POST", url = metadata.tokenEndpoint)
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

    private fun extractRedirectUri(): String {
        // For now, the redirect URI is derived from the client metadata URL's scheme.
        // In a real app, this would come from the client metadata JSON.
        // Placeholder: consumers should override or configure this.
        return "io.github.kikin81:/oauth-redirect"
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

    private data class PendingAuthState(
        val metadata: AuthServerMetadata,
        val signer: DpopSigner,
        val codeVerifier: String,
        val state: String,
        val redirectUri: String,
    )
}

@kotlinx.serialization.Serializable
internal data class ParResponse(
    val request_uri: String,
    val expires_in: Long? = null,
)
