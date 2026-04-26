package io.github.kikin81.atproto.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
        // doesn't have to re-discover it (Copilot review on PR #34, comment
        // 2). Per refreshTokens(), a network-layer exception throws
        // OAuthSessionExpiredException without calling sessionStore.clear(),
        // so persisting the nonce is safe.
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

        assertFailsWith<OAuthSessionExpiredException> {
            provider.onUnauthorized(mapOf("DPoP-Nonce" to "fresh-nonce"))
        }

        // Refresh threw, but the rotated nonce must already be persisted.
        assertEquals("fresh-nonce", store.session?.pdsNonce)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun makeJwtWithExp(exp: Long): String {
        val header = Base64.UrlSafe.encode("""{"alg":"ES256","typ":"at+jwt"}""".toByteArray()).trimEnd('=')
        val payload = Base64.UrlSafe.encode("""{"exp":$exp}""".toByteArray()).trimEnd('=')
        return "$header.$payload.fakesignature"
    }
}
