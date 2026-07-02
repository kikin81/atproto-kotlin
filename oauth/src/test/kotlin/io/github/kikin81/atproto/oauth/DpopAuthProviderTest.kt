package io.github.kikin81.atproto.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DpopAuthProviderTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private class InMemorySessionStore : OAuthSessionStore {
        var session: OAuthSession? = null
        override suspend fun load(): OAuthSession? = session
        override suspend fun save(session: OAuthSession) {
            this.session = session
        }
        override suspend fun clear() {
            session = null
        }
    }

    @Test
    fun onUnauthorizedRecoversNonceAndRefreshesExpiredTokenInOneCall() = runTest {
        // Regression for kikin81/atproto-kotlin#33: a single 401 carrying both
        // a fresh DPoP-Nonce and (implied by the about-to-fail-anyway expired
        // access token) `invalid_token` must produce nonce update AND refresh
        // in one onUnauthorized call. Otherwise XrpcClient's single retry
        // sends new-nonce + still-expired-token and surfaces XrpcError.Unknown.
        var tokenCalls = 0
        val refreshClient = HttpClient(
            MockEngine { _ ->
                tokenCalls++
                respond(
                    ByteReadChannel(
                        """{"access_token":"at_new","refresh_token":"rt_new","token_type":"DPoP"}""",
                    ),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val expiredToken = makeJwtWithExp((System.currentTimeMillis() / 1000) - 3600)
        val store = InMemorySessionStore()
        val session = OAuthSession(
            accessToken = expiredToken,
            refreshToken = "rt_old",
            did = "did:plc:testuser",
            handle = "alice.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            pdsNonce = "old-nonce",
        )
        store.session = session
        val provider = DpopAuthProvider(session, signer, store, refreshClient)

        val recovered = provider.onUnauthorized(
            mapOf(
                "DPoP-Nonce" to "fresh-nonce",
                "WWW-Authenticate" to """DPoP error="use_dpop_nonce"""",
            ),
        )

        assertTrue(recovered, "onUnauthorized must report a successful recovery")
        assertEquals(1, tokenCalls, "Token endpoint must be hit exactly once for the in-band refresh")
        val saved = store.session
        assertNotNull(saved)
        assertEquals("at_new", saved.accessToken, "Refreshed access token must be persisted")
        assertEquals("fresh-nonce", saved.pdsNonce, "New PDS nonce must be persisted")
    }

    @Test
    fun onUnauthorizedSkipsRefreshForOpaqueAccessTokenWhenOnlyNonceChanged() = runTest {
        // Preservation: if the access token isn't a parseable JWT (opaque
        // token, or a server that doesn't bound exp into the JWT), we cannot
        // determine expiry — must NOT speculatively refresh just because the
        // nonce changed. Real expiry will surface on the next 401.
        var tokenCalls = 0
        val refreshClient = HttpClient(
            MockEngine { _ ->
                tokenCalls++
                respond(ByteReadChannel(""), HttpStatusCode.OK)
            },
        )

        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val store = InMemorySessionStore()
        val session = OAuthSession(
            accessToken = "opaque-not-a-jwt",
            refreshToken = "rt",
            did = "did:plc:x",
            handle = "x.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            pdsNonce = "old-nonce",
        )
        store.session = session
        val provider = DpopAuthProvider(session, signer, store, refreshClient)

        val recovered = provider.onUnauthorized(mapOf("DPoP-Nonce" to "fresh-nonce"))

        assertTrue(recovered)
        assertEquals(0, tokenCalls, "Refresh must not run when expiry is undetermined and nonce is the only signal")
        assertEquals("fresh-nonce", store.session?.pdsNonce)
    }

    @Test
    fun onUnauthorizedRefreshesWhenNonceUnchanged() = runTest {
        // Preservation: existing fall-through path. Same-nonce 401 (no
        // use_dpop_nonce signal) goes straight to refresh.
        var tokenCalls = 0
        val refreshClient = HttpClient(
            MockEngine { _ ->
                tokenCalls++
                respond(
                    ByteReadChannel("""{"access_token":"at_new","token_type":"DPoP"}"""),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val store = InMemorySessionStore()
        val session = OAuthSession(
            accessToken = "opaque",
            refreshToken = "rt",
            did = "did:plc:x",
            handle = "x.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            pdsNonce = "same-nonce",
        )
        store.session = session
        val provider = DpopAuthProvider(session, signer, store, refreshClient)

        val recovered = provider.onUnauthorized(mapOf("DPoP-Nonce" to "same-nonce"))

        assertTrue(recovered)
        assertEquals(1, tokenCalls)
    }

    @Test
    fun onUnauthorizedPersistsNewNonceEvenWhenRefreshFails() = runTest {
        // If the access token is expired AND the server rotates the nonce in
        // the same 401, refreshTokens() may still throw on a network failure.
        // The new nonce must already be persisted so the next cold start
        // doesn't have to re-discover it (Copilot review on PR #34, comment 2).
        // A network-layer failure is TRANSIENT (no signal, not a revoked
        // token): it must throw the retryable OAuthRefreshFailedException and
        // leave the session intact — never OAuthSessionExpiredException, which
        // would clear the session and sign the user out.
        val refreshClient = HttpClient(
            MockEngine { _ ->
                throw java.io.IOException("simulated network failure")
            },
        )

        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val expiredToken = makeJwtWithExp((System.currentTimeMillis() / 1000) - 3600)
        val store = InMemorySessionStore()
        val session = OAuthSession(
            accessToken = expiredToken,
            refreshToken = "rt",
            did = "did:plc:x",
            handle = "x.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            pdsNonce = "old-nonce",
        )
        store.session = session
        val provider = DpopAuthProvider(session, signer, store, refreshClient)

        assertFailsWith<OAuthRefreshFailedException> {
            provider.onUnauthorized(mapOf("DPoP-Nonce" to "fresh-nonce"))
        }

        // Refresh threw, but the session survives and the rotated nonce is persisted.
        assertNotNull(store.session, "a network failure must NOT clear the session")
        assertEquals("fresh-nonce", store.session?.pdsNonce)
    }

    @Test
    fun refreshOnTransient5xxKeepsSessionAndThrowsRetryable() = runTest {
        // The core bug: a transient 5xx from the token endpoint (flaky signal,
        // gateway/captive-portal upstream) must NOT be treated as a revoked
        // refresh token. Leave the session intact and surface a retryable error.
        val refreshClient = HttpClient(
            MockEngine { _ -> respond(ByteReadChannel("upstream unavailable"), HttpStatusCode.ServiceUnavailable) },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        assertFailsWith<OAuthRefreshFailedException> { provider.onUnauthorized(emptyMap()) }
        assertNotNull(store.session, "a transient 5xx must NOT clear the session")
    }

    @Test
    fun refreshOn400WithoutInvalidGrantKeepsSession() = runTest {
        // A 400 whose OAuth error is NOT invalid_grant (e.g. a proxy/portal
        // error, or invalid_request) is not proof the refresh token is revoked
        // — keep the session and surface a retryable error.
        val refreshClient = HttpClient(
            MockEngine { _ -> respond(ByteReadChannel("""{"error":"invalid_request"}"""), HttpStatusCode.BadRequest, jsonHeaders) },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        assertFailsWith<OAuthRefreshFailedException> { provider.onUnauthorized(emptyMap()) }
        assertNotNull(store.session, "a non-invalid_grant 400 must NOT clear the session")
    }

    @Test
    fun refreshOnInvalidGrantClearsSessionAndThrowsExpired() = runTest {
        // The genuine revoked/expired refresh token case (RFC 6749 §5.2): the
        // token endpoint returns 400 with error=invalid_grant. THIS is the only
        // case that should clear the session and sign the user out.
        val refreshClient = HttpClient(
            MockEngine { _ -> respond(ByteReadChannel("""{"error":"invalid_grant"}"""), HttpStatusCode.BadRequest, jsonHeaders) },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        assertFailsWith<OAuthSessionExpiredException> { provider.onUnauthorized(emptyMap()) }
        assertNull(store.session, "a revoked refresh token (invalid_grant) must clear the session")
    }

    @Test
    fun refreshAfterNonceRetryTransientKeepsSession() = runTest {
        // Same guarantee on the nonce-retry path: if the first refresh gets a
        // rotated auth-server nonce (use_dpop_nonce) and the retried refresh
        // then hits a transient 5xx, keep the session and surface a retryable
        // error — don't sign the user out.
        var calls = 0
        val refreshClient = HttpClient(
            MockEngine { _ ->
                calls++
                if (calls == 1) {
                    respond(
                        ByteReadChannel("""{"error":"use_dpop_nonce"}"""),
                        HttpStatusCode.BadRequest,
                        headersOf("DPoP-Nonce", "srv-nonce"),
                    )
                } else {
                    respond(ByteReadChannel("upstream unavailable"), HttpStatusCode.ServiceUnavailable)
                }
            },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        assertFailsWith<OAuthRefreshFailedException> { provider.onUnauthorized(emptyMap()) }
        assertEquals(2, calls, "the nonce-retry must have been attempted")
        assertNotNull(store.session, "a transient 5xx on the nonce retry must NOT clear the session")
    }

    @Test
    fun refreshNonceRetryNetworkFailureKeepsSession() = runTest {
        // The nonce-retry leg must also treat a network failure as transient —
        // wrap submitForm so it surfaces as retryable, not a raw escape that a
        // caller might mishandle, and never clear the session.
        var calls = 0
        val refreshClient = HttpClient(
            MockEngine { _ ->
                calls++
                if (calls == 1) {
                    respond(
                        ByteReadChannel("""{"error":"use_dpop_nonce"}"""),
                        HttpStatusCode.BadRequest,
                        headersOf("DPoP-Nonce", "srv-nonce"),
                    )
                } else {
                    throw java.io.IOException("simulated network failure on nonce retry")
                }
            },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        assertFailsWith<OAuthRefreshFailedException> { provider.onUnauthorized(emptyMap()) }
        assertEquals(2, calls, "the nonce-retry must have been attempted")
        assertNotNull(store.session, "a network failure on the nonce retry must NOT clear the session")
    }

    @Test
    fun refreshDoesNotWrapCancellation() = runTest {
        // A cancelled coroutine must propagate CancellationException untouched —
        // wrapping it as OAuthRefreshFailedException would swallow cancellation
        // and break structured concurrency.
        val refreshClient = HttpClient(
            MockEngine { _ -> throw CancellationException("refresh cancelled") },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        assertFailsWith<CancellationException> { provider.onUnauthorized(emptyMap()) }
        assertNotNull(store.session, "cancellation must not clear the session")
    }

    /**
     * Builds a [DpopAuthProvider] whose access token is already expired, so
     * `onUnauthorized` routes straight to the token-refresh path. Returns the
     * provider + its store for post-assertions on session survival.
     */
    private fun fixtureWithExpiredToken(refreshClient: HttpClient): Pair<DpopAuthProvider, InMemorySessionStore> {
        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val store = InMemorySessionStore()
        val session = OAuthSession(
            accessToken = makeJwtWithExp((System.currentTimeMillis() / 1000) - 3600),
            refreshToken = "rt",
            did = "did:plc:x",
            handle = "x.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            pdsNonce = "old-nonce",
        )
        store.session = session
        return DpopAuthProvider(session, signer, store, refreshClient) to store
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun makeJwtWithExp(exp: Long): String {
        val header = Base64.UrlSafe.encode("""{"alg":"ES256","typ":"at+jwt"}""".toByteArray()).trimEnd('=')
        val payload = Base64.UrlSafe.encode("""{"exp":$exp}""".toByteArray()).trimEnd('=')
        return "$header.$payload.fakesignature"
    }
}
