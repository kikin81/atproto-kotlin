package io.github.kikin81.atproto.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sign-in and sign-out requests of [AtOAuth] when they travel through a
 * supplied [TokenEndpointTransport] rather than straight to the server.
 */
class AtOAuthTransportTest {

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

    /**
     * Serves discovery only. Any PAR, token or revoke request reaching this
     * client fails the test: those must go through the transport.
     */
    private fun discoveryOnlyClient(): HttpClient = HttpClient(
        MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("/.well-known/atproto-did") ->
                    respond(ByteReadChannel("did:plc:testuser"), HttpStatusCode.OK)
                url.contains("plc.directory/did:plc:testuser") ->
                    respond(
                        ByteReadChannel(
                            """{"id":"did:plc:testuser","alsoKnownAs":["at://alice.test"],"service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"https://pds.test"}]}""",
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                url.contains("/.well-known/oauth-protected-resource") ->
                    respond(ByteReadChannel("""{"authorization_servers":["https://auth.test"]}"""), HttpStatusCode.OK, jsonHeaders)
                url.contains("/.well-known/oauth-authorization-server") ->
                    respond(
                        ByteReadChannel(
                            """{"issuer":"https://auth.test","authorization_endpoint":"https://auth.test/authorize","token_endpoint":"https://auth.test/token","pushed_authorization_request_endpoint":"https://auth.test/par","revocation_endpoint":"https://auth.test/revoke"}""",
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                else -> error("Unexpected direct request to $url — it must go through the transport")
            }
        },
    )

    private class FakeTransport(
        private val kid: String? = "k1",
        private val method: String? = "private_key_jwt",
        private val parNonceChallenge: Boolean = true,
    ) : TokenEndpointTransport {
        val requests = mutableListOf<TokenEndpointRequest>()

        override suspend fun send(request: TokenEndpointRequest): TokenEndpointResponse {
            requests += request
            val parAttempts = requests.count { it.kind == TokenEndpointRequestKind.PushedAuthorization }
            return when (request.kind) {
                TokenEndpointRequestKind.PushedAuthorization ->
                    if (parNonceChallenge && parAttempts == 1) {
                        TokenEndpointResponse(
                            status = 400,
                            body = """{"error":"use_dpop_nonce"}""",
                            dpopNonce = "nonce-1",
                            clientAuthKid = kid,
                            clientAuthMethod = method,
                        )
                    } else {
                        TokenEndpointResponse(
                            status = 201,
                            body = """{"request_uri":"urn:ietf:params:oauth:request_uri:abc","expires_in":300}""",
                            dpopNonce = "nonce-2",
                            clientAuthKid = kid,
                            clientAuthMethod = method,
                        )
                    }
                TokenEndpointRequestKind.CodeExchange ->
                    TokenEndpointResponse(
                        status = 200,
                        body = """{"access_token":"at_test","refresh_token":"rt_test","token_type":"DPoP","sub":"did:plc:testuser"}""",
                        clientAuthKid = kid,
                        clientAuthMethod = method,
                    )
                TokenEndpointRequestKind.Revoke -> TokenEndpointResponse(status = 200, body = "")
                TokenEndpointRequestKind.Refresh -> error("not used here")
            }
        }

        fun of(kind: TokenEndpointRequestKind) = requests.filter { it.kind == kind }
    }

    private fun oauth(
        transport: TokenEndpointTransport,
        sessionStore: OAuthSessionStore = InMemorySessionStore(),
        pendingStore: PendingAuthStore = InMemoryPendingAuthStore(),
    ) = AtOAuth(
        clientMetadataUrl = "https://app.test/meta.json",
        redirectUri = "https://app.test/cb",
        sessionStore = sessionStore,
        httpClient = discoveryOnlyClient(),
        pendingStore = pendingStore,
        tokenTransport = transport,
    )

    private suspend fun redirectFor(pendingStore: PendingAuthStore): String = "https://app.test/cb?code=code-1&state=${pendingStore.load()?.state}&iss=https%3A%2F%2Fauth.test"

    @Test
    fun parGoesThroughTheTransportTwiceOnANonceChallenge() = runTest {
        val transport = FakeTransport()

        val url = oauth(transport).beginLogin("alice.test")

        val par = transport.of(TokenEndpointRequestKind.PushedAuthorization)
        assertEquals(2, par.size, "One challenged attempt, one retry")
        assertEquals("https://auth.test/par", par[0].endpointUrl)
        assertEquals("https://auth.test", par[0].issuer)
        assertEquals("https://app.test/meta.json", par[0].formParameters["client_id"])
        assertEquals("S256", par[0].formParameters["code_challenge_method"])
        assertTrue(par[0].dpopProof != par[1].dpopProof, "The retry carries a new proof")
        assertTrue(url.contains("request_uri="))
    }

    @Test
    fun parRetryNamesTheKeyTheFirstAttemptReported() = runTest {
        val transport = FakeTransport(kid = "k1")

        oauth(transport).beginLogin("alice.test")

        val par = transport.of(TokenEndpointRequestKind.PushedAuthorization)
        assertNull(par[0].clientAuthKid)
        assertEquals("k1", par[1].clientAuthKid)
    }

    @Test
    fun pendingLoginRecordsTheKeyOfItsPushedRequest() = runTest {
        val pendingStore = InMemoryPendingAuthStore()

        oauth(FakeTransport(kid = "k1"), pendingStore = pendingStore).beginLogin("alice.test")

        assertEquals("k1", pendingStore.load()?.clientAuthKid)
    }

    @Test
    fun codeExchangeNamesThePendingKeyAfterAProcessRestart() = runTest {
        val pendingStore = InMemoryPendingAuthStore()
        val sessionStore = InMemorySessionStore()
        oauth(FakeTransport(kid = "k1"), sessionStore, pendingStore).beginLogin("alice.test")

        // A different AtOAuth and transport, sharing only what was persisted.
        val afterRestart = FakeTransport(kid = "k2")
        oauth(afterRestart, sessionStore, pendingStore).completeLogin(redirectFor(pendingStore))

        val exchange = afterRestart.of(TokenEndpointRequestKind.CodeExchange).single()
        assertEquals("k1", exchange.clientAuthKid, "The exchange must use the key the pushed request was bound to")
        assertEquals("https://auth.test/token", exchange.endpointUrl)
        assertEquals("https://auth.test", exchange.issuer)
        assertEquals("authorization_code", exchange.formParameters["grant_type"])
        assertEquals("code-1", exchange.formParameters["code"])
    }

    @Test
    fun confidentialSignInStoresIssuerMethodAndKey() = runTest {
        val pendingStore = InMemoryPendingAuthStore()
        val sessionStore = InMemorySessionStore()
        val transport = FakeTransport(kid = "k1", method = "private_key_jwt")
        val oauth = oauth(transport, sessionStore, pendingStore)
        oauth.beginLogin("alice.test")

        oauth.completeLogin(redirectFor(pendingStore))

        val session = assertNotNull(sessionStore.session)
        assertEquals("https://auth.test", session.issuer)
        assertEquals("private_key_jwt", session.clientAuthMethod)
        assertEquals("k1", session.clientAuthKid)
    }

    @Test
    fun publicSignInStoresNoKey() = runTest {
        val pendingStore = InMemoryPendingAuthStore()
        val sessionStore = InMemorySessionStore()
        val oauth = oauth(FakeTransport(kid = "k1", method = "none"), sessionStore, pendingStore)
        oauth.beginLogin("alice.test")

        oauth.completeLogin(redirectFor(pendingStore))

        val session = assertNotNull(sessionStore.session)
        assertNull(session.clientAuthKid, "Nothing is bound while the client is public")
        assertEquals("none", session.clientAuthMethod)
    }

    @Test
    fun mediatorRefusalOfTheCodeExchangeFailsTheLogin() = runTest {
        val pendingStore = InMemoryPendingAuthStore()
        val sessionStore = InMemorySessionStore()
        val refusing = object : TokenEndpointTransport {
            val inner = FakeTransport()
            override suspend fun send(request: TokenEndpointRequest): TokenEndpointResponse = if (request.kind == TokenEndpointRequestKind.CodeExchange) {
                // Shaped like a success, but not from the authorization server.
                TokenEndpointResponse(
                    status = 200,
                    body = """{"access_token":"at_forged","refresh_token":"rt_forged"}""",
                    origin = TokenEndpointResponseOrigin.Mediator,
                )
            } else {
                inner.send(request)
            }
        }
        val oauth = oauth(refusing, sessionStore, pendingStore)
        oauth.beginLogin("alice.test")

        assertFailsWith<OAuthException> { oauth.completeLogin(redirectFor(pendingStore)) }

        assertNull(sessionStore.session, "Tokens only come from the authorization server")
    }

    @Test
    fun logoutRevokesThroughTheTransportWithTheSessionsKey() = runTest {
        val pendingStore = InMemoryPendingAuthStore()
        val sessionStore = InMemorySessionStore()
        val transport = FakeTransport(kid = "k1", method = "private_key_jwt")
        val oauth = oauth(transport, sessionStore, pendingStore)
        oauth.beginLogin("alice.test")
        oauth.completeLogin(redirectFor(pendingStore))

        oauth.logout()

        val revoke = transport.of(TokenEndpointRequestKind.Revoke).single()
        assertEquals("https://auth.test/revoke", revoke.endpointUrl)
        assertEquals("https://auth.test", revoke.issuer)
        assertEquals("k1", revoke.clientAuthKid)
        assertEquals("rt_test", revoke.formParameters["token"])
        assertNull(sessionStore.session)
    }

    @Test
    fun logoutStillClearsWhenTheTransportFails() = runTest {
        val pendingStore = InMemoryPendingAuthStore()
        val sessionStore = InMemorySessionStore()
        val inner = FakeTransport()
        val failingRevoke = TokenEndpointTransport { request ->
            if (request.kind == TokenEndpointRequestKind.Revoke) throw TokenRequestNotSentException("backend down")
            inner.send(request)
        }
        val oauth = oauth(failingRevoke, sessionStore, pendingStore)
        oauth.beginLogin("alice.test")
        oauth.completeLogin(redirectFor(pendingStore))

        oauth.logout()

        assertNull(sessionStore.session, "Sign-out is local first; revocation is best effort")
    }
}
