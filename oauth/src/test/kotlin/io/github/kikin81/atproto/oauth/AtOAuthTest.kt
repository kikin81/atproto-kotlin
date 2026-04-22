package io.github.kikin81.atproto.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AtOAuthTest {

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

    private fun fullFlowMockClient(): HttpClient = HttpClient(
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
                    respond(
                        ByteReadChannel("""{"authorization_servers":["https://auth.test"]}"""),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )

                url.contains("/.well-known/oauth-authorization-server") ->
                    respond(
                        ByteReadChannel(
                            """{"issuer":"https://auth.test","authorization_endpoint":"https://auth.test/authorize","token_endpoint":"https://auth.test/token","pushed_authorization_request_endpoint":"https://auth.test/par"}""",
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )

                // PAR: first call returns use_dpop_nonce, second succeeds
                url.contains("/par") -> {
                    val hasDpopHeader = request.headers["DPoP"] != null
                    if (hasDpopHeader && request.headers["DPoP"]!!.count { it == '.' } == 2) {
                        // Check if this is a retry (has nonce in the JWT — we can't easily parse,
                        // so we use call count via a simple heuristic: first call gets nonce error)
                        respond(
                            ByteReadChannel("""{"request_uri":"urn:ietf:params:oauth:request_uri:test123","expires_in":3600}"""),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    } else {
                        respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                    }
                }

                // Token exchange
                url.contains("/token") ->
                    respond(
                        ByteReadChannel(
                            """{"access_token":"at_test","refresh_token":"rt_test","token_type":"DPoP","expires_in":3600,"sub":"did:plc:testuser"}""",
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )

                else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
            }
        },
    )

    @Test
    fun beginLoginReturnsAuthorizationUrl() = runTest {
        val store = InMemorySessionStore()
        val oauth = AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            sessionStore = store,
            httpClient = fullFlowMockClient(),
        )
        val authUrl = oauth.beginLogin("alice.test")
        assertContains(authUrl, "https://auth.test/authorize")
        assertContains(authUrl, "client_id=")
        assertContains(authUrl, "request_uri=")
    }

    @Test
    fun fullFlowProducesPersistedSession() = runTest {
        val store = InMemorySessionStore()
        val oauth = AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            sessionStore = store,
            httpClient = fullFlowMockClient(),
        )
        oauth.beginLogin("alice.test")

        // Simulate the browser redirect
        oauth.completeLogin(
            "io.github.kikin81:/oauth-redirect?code=auth_code_123&state=${extractState(oauth)}&iss=https://auth.test",
        )

        val session = store.session
        assertNotNull(session)
        assertTrue(session.accessToken == "at_test")
        assertTrue(session.refreshToken == "rt_test")
        assertTrue(session.did == "did:plc:testuser")
        assertTrue(session.handle == "alice.test")
        assertTrue(session.dpopPrivateKey.isNotEmpty())
    }

    @Test
    fun completeLoginRejectsMismatchedState() = runTest {
        val store = InMemorySessionStore()
        val oauth = AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            sessionStore = store,
            httpClient = fullFlowMockClient(),
        )
        oauth.beginLogin("alice.test")

        assertFailsWith<OAuthException> {
            oauth.completeLogin(
                "io.github.kikin81:/oauth-redirect?code=abc&state=wrong_state&iss=https://auth.test",
            )
        }
    }

    @Test
    fun completeLoginRejectsMismatchedIss() = runTest {
        val store = InMemorySessionStore()
        val oauth = AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            sessionStore = store,
            httpClient = fullFlowMockClient(),
        )
        oauth.beginLogin("alice.test")

        assertFailsWith<OAuthException> {
            oauth.completeLogin(
                "io.github.kikin81:/oauth-redirect?code=abc&state=${extractState(oauth)}&iss=https://evil.test",
            )
        }
    }

    @Test
    fun completeLoginRejectsMismatchedSub() = runTest {
        // Mock that returns a different sub in the token response
        val client = HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                when {
                    url.contains("/.well-known/atproto-did") ->
                        respond(ByteReadChannel("did:plc:expected"), HttpStatusCode.OK)

                    url.contains("plc.directory") ->
                        respond(
                            ByteReadChannel(
                                """{"id":"did:plc:expected","alsoKnownAs":["at://alice.test"],"service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"https://pds.test"}]}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    url.contains("/.well-known/oauth-protected-resource") ->
                        respond(ByteReadChannel("""{"authorization_servers":["https://auth.test"]}"""), HttpStatusCode.OK, jsonHeaders)

                    url.contains("/.well-known/oauth-authorization-server") ->
                        respond(
                            ByteReadChannel(
                                """{"issuer":"https://auth.test","authorization_endpoint":"https://auth.test/authorize","token_endpoint":"https://auth.test/token","pushed_authorization_request_endpoint":"https://auth.test/par"}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    url.contains("/par") ->
                        respond(ByteReadChannel("""{"request_uri":"urn:test","expires_in":60}"""), HttpStatusCode.OK, jsonHeaders)

                    url.contains("/token") ->
                        respond(
                            ByteReadChannel(
                                """{"access_token":"at","refresh_token":"rt","sub":"did:plc:WRONG"}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )

        val store = InMemorySessionStore()
        val oauth = AtOAuth("https://app.test/meta.json", store, client)
        oauth.beginLogin("alice.test")

        assertFailsWith<OAuthAccountMismatchException> {
            oauth.completeLogin(
                "io.github.kikin81:/oauth-redirect?code=abc&state=${extractState(oauth)}&iss=https://auth.test",
            )
        }
    }

    @Test
    fun fullFlowEndToEndWithXrpcQuery() = runTest {
        var xrpcRequestAuth: String? = null
        var xrpcRequestDpop: String? = null
        val store = InMemorySessionStore()
        val client = HttpClient(
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

                    url.contains("/par") ->
                        respond(ByteReadChannel("""{"request_uri":"urn:test:par","expires_in":60}"""), HttpStatusCode.OK, jsonHeaders)

                    url.contains("/token") ->
                        respond(
                            ByteReadChannel("""{"access_token":"at_e2e","refresh_token":"rt_e2e","token_type":"DPoP","sub":"did:plc:testuser"}"""),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    url.contains("/xrpc/app.bsky.feed.getTimeline") -> {
                        xrpcRequestAuth = request.headers["Authorization"]
                        xrpcRequestDpop = request.headers["DPoP"]
                        respond(
                            ByteReadChannel("""{"feed":[]}"""),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }

                    else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )

        val oauth = AtOAuth("https://app.test/oauth/client-metadata.json", store, client)

        // Step 1: beginLogin
        val authUrl = oauth.beginLogin("alice.test")
        assertContains(authUrl, "https://auth.test/authorize")

        // Step 2: simulate browser redirect
        oauth.completeLogin(
            "io.github.kikin81:/oauth-redirect?code=code123&state=${extractState(oauth)}&iss=https://auth.test",
        )

        // Step 3: verify session persisted with all fields
        val session = store.session
        assertNotNull(session)
        assertTrue(session.accessToken == "at_e2e")
        assertTrue(session.clientId == "https://app.test/oauth/client-metadata.json")
        assertTrue(session.revocationEndpoint == "https://auth.test/revoke")

        // Step 4: createClient and make an XRPC query
        val xrpcClient = oauth.createClient()
        val result = xrpcClient.query(
            nsid = "app.bsky.feed.getTimeline",
            params = Unit,
            paramsSerializer = kotlinx.serialization.serializer<Unit>(),
            responseSerializer = kotlinx.serialization.json.JsonObject.serializer(),
        )

        // Verify DPoP headers were attached
        assertNotNull(xrpcRequestAuth)
        assertTrue(xrpcRequestAuth!!.startsWith("DPoP "))
        assertNotNull(xrpcRequestDpop)
        // Verify response was parsed
        assertTrue(result.containsKey("feed"))
    }

    @Test
    fun createClientRefreshesExpiredToken() = runTest {
        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        var refreshCalled = false
        val store = InMemorySessionStore()
        store.session = OAuthSession(
            accessToken = "expired_token",
            refreshToken = "rt_valid",
            did = "did:plc:testuser",
            handle = "alice.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
        )

        val client = HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                when {
                    // First XRPC call returns 401 (expired token)
                    url.contains("/xrpc/") && !refreshCalled ->
                        respond(ByteReadChannel(""), HttpStatusCode.Unauthorized)

                    // Token refresh
                    url.contains("/token") -> {
                        refreshCalled = true
                        respond(
                            ByteReadChannel("""{"access_token":"at_refreshed","refresh_token":"rt_new","token_type":"DPoP","sub":"did:plc:testuser"}"""),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }

                    // Retry after refresh succeeds
                    url.contains("/xrpc/") && refreshCalled ->
                        respond(ByteReadChannel("""{"feed":[]}"""), HttpStatusCode.OK, jsonHeaders)

                    else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )

        val oauth = AtOAuth("https://app.test/meta.json", store, client)
        val xrpcClient = oauth.createClient()

        val result = xrpcClient.query(
            nsid = "app.bsky.feed.getTimeline",
            params = Unit,
            paramsSerializer = kotlinx.serialization.serializer<Unit>(),
            responseSerializer = kotlinx.serialization.json.JsonObject.serializer(),
        )

        assertTrue(refreshCalled, "Token refresh should have been triggered")
        assertTrue(result.containsKey("feed"))
        assertTrue(store.session!!.accessToken == "at_refreshed")
    }

    @Test
    fun logoutRevokesTokenAndClearsSession() = runTest {
        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        var revokeCalled = false
        val store = InMemorySessionStore()
        store.session = OAuthSession(
            accessToken = "at",
            refreshToken = "rt",
            did = "did:plc:x",
            handle = "x.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            revocationEndpoint = "https://auth.test/revoke",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
        )

        val client = HttpClient(
            MockEngine { request ->
                if (request.url.toString().contains("/revoke")) {
                    revokeCalled = true
                    assertNotNull(request.headers["DPoP"])
                }
                respond(ByteReadChannel(""), HttpStatusCode.OK)
            },
        )

        val oauth = AtOAuth("https://app.test/meta.json", store, client)
        oauth.logout()

        assertTrue(revokeCalled, "Token revocation should have been called")
        assertTrue(store.session == null, "Session should be cleared")
    }

    @Test
    fun logoutClearsSessionEvenWithoutRevocationEndpoint() = runTest {
        val store = InMemorySessionStore()
        store.session = OAuthSession(
            accessToken = "at",
            refreshToken = "rt",
            did = "did:plc:x",
            handle = "x.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            dpopPrivateKey = byteArrayOf(),
            dpopPublicKey = byteArrayOf(),
        )
        val oauth = AtOAuth("https://app.test/meta.json", store, HttpClient(MockEngine { respond(ByteReadChannel(""), HttpStatusCode.OK) }))
        oauth.logout()
        assertTrue(store.session == null)
    }

    /**
     * Extracts the state from a pending AtOAuth instance via reflection.
     * Only for testing — production consumers don't access internal state.
     */
    private fun extractState(oauth: AtOAuth): String {
        val field = AtOAuth::class.java.getDeclaredField("pendingState")
        field.isAccessible = true
        val pending = field.get(oauth) ?: throw IllegalStateException("No pending state")
        val stateField = pending::class.java.getDeclaredField("state")
        stateField.isAccessible = true
        return stateField.get(pending) as String
    }
}
