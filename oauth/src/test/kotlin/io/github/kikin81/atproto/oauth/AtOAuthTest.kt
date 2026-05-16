package io.github.kikin81.atproto.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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

    /**
     * Returns the canned discovery response for the `did:plc:testuser` / alice.test
     * fixture if [url] matches one of the AT Protocol discovery URLs, else null.
     * Shared by mock builders so the discovery flow stays in one place.
     */
    private fun MockRequestHandleScope.respondToDiscovery(url: String): HttpResponseData? = when {
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

        else -> null
    }

    private fun fullFlowMockClient(): HttpClient = HttpClient(
        MockEngine { request ->
            val url = request.url.toString()
            respondToDiscovery(url) ?: when {
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

    /**
     * Builds a mock that captures the PAR form body. Used by scope-propagation
     * tests to assert the requested scope hits the wire verbatim.
     */
    private fun parCapturingClient(capture: (String) -> Unit): HttpClient = HttpClient(
        MockEngine { request ->
            val url = request.url.toString()
            respondToDiscovery(url) ?: when {
                url.contains("/par") -> {
                    val body = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    capture(body)
                    respond(
                        ByteReadChannel("""{"request_uri":"urn:test","expires_in":60}"""),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

                else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
            }
        },
    )

    /**
     * Builds a mock that runs the full discovery + PAR + token flow and captures
     * the token-exchange form body. Used to assert that values threaded from
     * beginLogin (e.g. redirect_uri) are forwarded unchanged to the token POST.
     */
    private fun tokenCapturingClient(capture: (String) -> Unit): HttpClient = HttpClient(
        MockEngine { request ->
            val url = request.url.toString()
            respondToDiscovery(url) ?: when {
                url.contains("/par") ->
                    respond(
                        ByteReadChannel("""{"request_uri":"urn:test:par","expires_in":60}"""),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )

                url.contains("/token") -> {
                    val body = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    capture(body)
                    respond(
                        ByteReadChannel(
                            """{"access_token":"at","refresh_token":"rt","token_type":"DPoP","sub":"did:plc:testuser"}""",
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

                else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
            }
        },
    )

    @Test
    fun parSendsDefaultScopeWhenNotOverridden() = runTest {
        val store = InMemorySessionStore()
        var parBody: String? = null
        val oauth = AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            redirectUri = "app.example:/oauth-redirect",
            sessionStore = store,
            httpClient = parCapturingClient { parBody = it },
        )
        oauth.beginLogin("alice.test")

        val body = parBody
        assertNotNull(body)
        assertContains(body, "scope=atproto+transition%3Ageneric")
    }

    @Test
    fun parSendsCustomScopeWhenProvided() = runTest {
        val store = InMemorySessionStore()
        var parBody: String? = null
        val oauth = AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            redirectUri = "app.example:/oauth-redirect",
            sessionStore = store,
            httpClient = parCapturingClient { parBody = it },
            scope = "atproto transition:generic transition:chat.bsky",
        )
        oauth.beginLogin("alice.test")

        val body = parBody
        assertNotNull(body)
        assertContains(body, "scope=atproto+transition%3Ageneric+transition%3Achat.bsky")
    }

    @Test
    fun parRequestUsesProvidedRedirectUri() = runTest {
        val store = InMemorySessionStore()
        var parBody: String? = null
        val oauth = AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            redirectUri = "app.example:/oauth-redirect",
            sessionStore = store,
            httpClient = parCapturingClient { parBody = it },
        )
        oauth.beginLogin("alice.test")

        assertEquals("app.example:/oauth-redirect", redirectUriParamFrom(parBody))
    }

    @Test
    fun redirectUriIsPassedThroughUntransformed() = runTest {
        val store = InMemorySessionStore()
        var parBody: String? = null
        val oauth = AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            redirectUri = "a",
            sessionStore = store,
            httpClient = parCapturingClient { parBody = it },
        )
        oauth.beginLogin("alice.test")

        assertEquals("a", redirectUriParamFrom(parBody))
    }

    @Test
    fun tokenRequestUsesProvidedRedirectUri() = runTest {
        val store = InMemorySessionStore()
        var tokenBody: String? = null
        val oauth = AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            redirectUri = "app.example:/oauth-redirect",
            sessionStore = store,
            httpClient = tokenCapturingClient { tokenBody = it },
        )
        oauth.beginLogin("alice.test")
        oauth.completeLogin(
            "app.example:/oauth-redirect?code=code_xyz&state=${extractState(oauth)}&iss=https://auth.test",
        )

        assertEquals("app.example:/oauth-redirect", redirectUriParamFrom(tokenBody))
    }

    @Test
    fun beginLoginReturnsAuthorizationUrl() = runTest {
        val store = InMemorySessionStore()
        val oauth = AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            redirectUri = "app.example:/oauth-redirect",
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
            redirectUri = "app.example:/oauth-redirect",
            sessionStore = store,
            httpClient = fullFlowMockClient(),
        )
        oauth.beginLogin("alice.test")

        // Simulate the browser redirect
        oauth.completeLogin(
            "app.example:/oauth-redirect?code=auth_code_123&state=${extractState(oauth)}&iss=https://auth.test",
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
            redirectUri = "app.example:/oauth-redirect",
            sessionStore = store,
            httpClient = fullFlowMockClient(),
        )
        oauth.beginLogin("alice.test")

        assertFailsWith<OAuthException> {
            oauth.completeLogin(
                "app.example:/oauth-redirect?code=abc&state=wrong_state&iss=https://auth.test",
            )
        }
    }

    @Test
    fun completeLoginRejectsMismatchedIss() = runTest {
        val store = InMemorySessionStore()
        val oauth = AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            redirectUri = "app.example:/oauth-redirect",
            sessionStore = store,
            httpClient = fullFlowMockClient(),
        )
        oauth.beginLogin("alice.test")

        assertFailsWith<OAuthException> {
            oauth.completeLogin(
                "app.example:/oauth-redirect?code=abc&state=${extractState(oauth)}&iss=https://evil.test",
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
        val oauth = AtOAuth("https://app.test/meta.json", "app.example:/oauth-redirect", store, client)
        oauth.beginLogin("alice.test")

        assertFailsWith<OAuthAccountMismatchException> {
            oauth.completeLogin(
                "app.example:/oauth-redirect?code=abc&state=${extractState(oauth)}&iss=https://auth.test",
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

        val oauth = AtOAuth("https://app.test/oauth/client-metadata.json", "app.example:/oauth-redirect", store, client)

        // Step 1: beginLogin
        val authUrl = oauth.beginLogin("alice.test")
        assertContains(authUrl, "https://auth.test/authorize")

        // Step 2: simulate browser redirect
        oauth.completeLogin(
            "app.example:/oauth-redirect?code=code123&state=${extractState(oauth)}&iss=https://auth.test",
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

        val oauth = AtOAuth("https://app.test/meta.json", "app.example:/oauth-redirect", store, client)
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

        val oauth = AtOAuth("https://app.test/meta.json", "app.example:/oauth-redirect", store, client)
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
        val oauth = AtOAuth(
            "https://app.test/meta.json",
            "app.example:/oauth-redirect",
            store,
            HttpClient(MockEngine { respond(ByteReadChannel(""), HttpStatusCode.OK) }),
        )
        oauth.logout()
        assertTrue(store.session == null)
    }

    // ---------- Signup flow tests ----------

    /**
     * Auth-server metadata advertising prompt=create — the bsky.social shape.
     */
    private val bskySocialAuthServerMetadata =
        """{"issuer":"https://bsky.social","authorization_endpoint":"https://bsky.social/oauth/authorize","token_endpoint":"https://bsky.social/oauth/token","pushed_authorization_request_endpoint":"https://bsky.social/oauth/par","prompt_values_supported":["none","login","consent","select_account","create"]}"""

    @Test
    fun beginSignupShortCircuitsDiscoveryAndSendsPromptCreate() = runTest {
        val store = InMemorySessionStore()
        val requestedUrls = mutableListOf<String>()
        var parBody: String? = null
        val client = HttpClient(
            MockEngine { request ->
                requestedUrls.add(request.url.toString())
                when {
                    request.url.toString().contains("/.well-known/oauth-authorization-server") ->
                        respond(ByteReadChannel(bskySocialAuthServerMetadata), HttpStatusCode.OK, jsonHeaders)

                    request.url.toString().contains("/par") -> {
                        parBody = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                        respond(
                            ByteReadChannel("""{"request_uri":"urn:ietf:params:oauth:request_uri:signup-test","expires_in":60}"""),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }

                    else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )

        val oauth = AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            redirectUri = "app.example:/oauth-redirect",
            sessionStore = store,
            httpClient = client,
        )
        val authUrl = oauth.beginSignup()

        // No PLC, no atproto-did, no oauth-protected-resource fetched
        val discoveryUrls = requestedUrls.filter { it.contains("/.well-known") }
        assertEquals(1, discoveryUrls.size, "expected exactly 1 well-known fetch (the auth-server metadata), got: $discoveryUrls")
        assertTrue(discoveryUrls[0].contains("oauth-authorization-server"))

        // PAR body carries prompt=create and no login_hint
        val body = parBody
        assertNotNull(body)
        assertContains(body, "prompt=create")
        assertTrue(!body.contains("login_hint"), "PAR body should not contain login_hint, got: $body")

        // Authorization URL points at the known auth server
        assertContains(authUrl, "https://bsky.social/oauth/authorize")
    }

    @Test
    fun completeLoginAfterSignupAcceptsTokenSubAsAuthority() = runTest {
        val store = InMemorySessionStore()
        val client = HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                when {
                    url.contains("/.well-known/oauth-authorization-server") ->
                        respond(ByteReadChannel(bskySocialAuthServerMetadata), HttpStatusCode.OK, jsonHeaders)

                    url.contains("/par") ->
                        respond(
                            ByteReadChannel("""{"request_uri":"urn:test","expires_in":60}"""),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    url.contains("/token") ->
                        respond(
                            ByteReadChannel(
                                """{"access_token":"at_signup","refresh_token":"rt_signup","sub":"did:plc:freshlyminted"}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    url.contains("plc.directory/did:plc:freshlyminted") ->
                        respond(
                            ByteReadChannel(
                                """{"id":"did:plc:freshlyminted","alsoKnownAs":["at://newbie.bsky.social"],"service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"https://newbie-pds.test"}]}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )

        val oauth = AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            redirectUri = "app.example:/oauth-redirect",
            sessionStore = store,
            httpClient = client,
        )
        oauth.beginSignup()
        oauth.completeLogin(
            "app.example:/oauth-redirect?code=signup_code&state=${extractState(oauth)}&iss=https://bsky.social",
        )

        val session = store.session
        assertNotNull(session)
        // sub from the token response is the authoritative DID — no prior comparison
        assertEquals("did:plc:freshlyminted", session.did)
        // handle and pdsUrl hydrated from the DID doc
        assertEquals("newbie.bsky.social", session.handle)
        assertEquals("https://newbie-pds.test", session.pdsUrl)
    }

    @Test
    fun signupHydrationRetriesAndPersistsNullIdentityWhenBudgetExhausts() = runTest {
        val store = InMemorySessionStore()
        var plcCallCount = 0
        val client = HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                when {
                    url.contains("/.well-known/oauth-authorization-server") ->
                        respond(ByteReadChannel(bskySocialAuthServerMetadata), HttpStatusCode.OK, jsonHeaders)

                    url.contains("/par") ->
                        respond(
                            ByteReadChannel("""{"request_uri":"urn:t","expires_in":60}"""),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    url.contains("/token") ->
                        respond(
                            ByteReadChannel(
                                """{"access_token":"at","refresh_token":"rt","sub":"did:plc:slowpropagation"}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    url.contains("plc.directory") -> {
                        plcCallCount++
                        // Always 404 — simulate propagation delay never resolving in-window
                        respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                    }

                    else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )

        val oauth = AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            redirectUri = "app.example:/oauth-redirect",
            sessionStore = store,
            httpClient = client,
        )
        oauth.beginSignup()
        oauth.completeLogin(
            "app.example:/oauth-redirect?code=c&state=${extractState(oauth)}&iss=https://bsky.social",
        )

        // Retried multiple times before giving up (4 attempts: immediate + 3 backoff slots)
        assertTrue(plcCallCount >= 2, "expected at least 2 PLC attempts under backoff, got $plcCallCount")

        // Session persisted with DID populated but handle/pds null
        val session = store.session
        assertNotNull(session)
        assertEquals("did:plc:slowpropagation", session.did)
        assertEquals(null, session.handle)
        assertEquals(null, session.pdsUrl)
    }

    @Test
    fun signupPersistsTokensBeforeHydrationCompletes() = runTest {
        // Two-write semantics: first save carries the new DID + null identity
        // (crash-resilience guarantee), second save carries the hydrated identity.
        val saves = mutableListOf<OAuthSession>()
        val store = object : OAuthSessionStore {
            override suspend fun load(): OAuthSession? = saves.lastOrNull()
            override suspend fun save(session: OAuthSession) {
                saves.add(session)
            }
            override suspend fun clear() {
                saves.clear()
            }
        }
        val client = HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                when {
                    url.contains("/.well-known/oauth-authorization-server") ->
                        respond(ByteReadChannel(bskySocialAuthServerMetadata), HttpStatusCode.OK, jsonHeaders)

                    url.contains("/par") ->
                        respond(ByteReadChannel("""{"request_uri":"urn:t","expires_in":60}"""), HttpStatusCode.OK, jsonHeaders)

                    url.contains("/token") ->
                        respond(
                            ByteReadChannel(
                                """{"access_token":"at","refresh_token":"rt","sub":"did:plc:newuser"}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    url.contains("plc.directory/did:plc:newuser") ->
                        respond(
                            ByteReadChannel(
                                """{"id":"did:plc:newuser","alsoKnownAs":["at://newbie.bsky.social"],"service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"https://newbie-pds.test"}]}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )

        val oauth = AtOAuth("https://app.test/m.json", "app.example:/oauth-redirect", store, client)
        oauth.beginSignup()
        oauth.completeLogin(
            "app.example:/oauth-redirect?code=c&state=${extractState(oauth)}&iss=https://bsky.social",
        )

        assertEquals(2, saves.size, "signup flow should persist twice: pre- and post-hydration")
        // First save: DID known, identity not yet hydrated
        assertEquals("did:plc:newuser", saves[0].did)
        assertEquals(null, saves[0].handle)
        assertEquals(null, saves[0].pdsUrl)
        assertEquals("at", saves[0].accessToken)
        // Second save: identity hydrated
        assertEquals("did:plc:newuser", saves[1].did)
        assertEquals("newbie.bsky.social", saves[1].handle)
        assertEquals("https://newbie-pds.test", saves[1].pdsUrl)
    }

    @Test
    fun loginFlowPersistsSessionOnce() = runTest {
        // Regression: login path remains single-write (no benefit to a second save
        // since identity is fully resolved before the first persist).
        val saves = mutableListOf<OAuthSession>()
        val store = object : OAuthSessionStore {
            override suspend fun load(): OAuthSession? = saves.lastOrNull()
            override suspend fun save(session: OAuthSession) {
                saves.add(session)
            }
            override suspend fun clear() {
                saves.clear()
            }
        }
        val oauth = AtOAuth(
            clientMetadataUrl = "https://app.test/oauth/client-metadata.json",
            redirectUri = "app.example:/oauth-redirect",
            sessionStore = store,
            httpClient = fullFlowMockClient(),
        )
        oauth.beginLogin("alice.test")
        oauth.completeLogin(
            "app.example:/oauth-redirect?code=c&state=${extractState(oauth)}&iss=https://auth.test",
        )

        assertEquals(1, saves.size, "login flow should persist exactly once")
        assertEquals("alice.test", saves[0].handle)
    }

    @Test
    fun beginLoginStillRejectsMismatchedSubAfterSignupBranching() = runTest {
        // Regression: signup-flow branching must NOT disable login-flow's DID mismatch check.
        val store = InMemorySessionStore()
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
                        respond(ByteReadChannel("""{"request_uri":"urn:t","expires_in":60}"""), HttpStatusCode.OK, jsonHeaders)

                    url.contains("/token") ->
                        respond(
                            ByteReadChannel("""{"access_token":"at","refresh_token":"rt","sub":"did:plc:WRONG"}"""),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )

        val oauth = AtOAuth("https://app.test/m.json", "app.example:/oauth-redirect", store, client)
        oauth.beginLogin("alice.test")
        assertFailsWith<OAuthAccountMismatchException> {
            oauth.completeLogin(
                "app.example:/oauth-redirect?code=c&state=${extractState(oauth)}&iss=https://auth.test",
            )
        }
    }

    @Test
    fun beginSignupRejectsServerWithoutPromptCreateSupport() = runTest {
        val store = InMemorySessionStore()
        val client = HttpClient(
            MockEngine { request ->
                if (request.url.toString().contains("/.well-known/oauth-authorization-server")) {
                    respond(
                        ByteReadChannel(
                            """{"issuer":"https://entryway.test","authorization_endpoint":"https://entryway.test/a","token_endpoint":"https://entryway.test/t","pushed_authorization_request_endpoint":"https://entryway.test/p","prompt_values_supported":["none","login","consent"]}""",
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                } else {
                    respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )

        val oauth = AtOAuth("https://app.test/m.json", "app.example:/oauth-redirect", store, client)
        val ex = assertFailsWith<OAuthSignupNotSupportedException> {
            oauth.beginSignup(authServer = "entryway.test")
        }
        assertContains(ex.advertisedPromptValues, "none")
        assertTrue(!ex.advertisedPromptValues.contains("create"))
    }

    @Test
    fun beginSignupBypassesGateWhenRequirePromptCreateSupportFalse() = runTest {
        val store = InMemorySessionStore()
        var parBody: String? = null
        val client = HttpClient(
            MockEngine { request ->
                when {
                    request.url.toString().contains("/.well-known/oauth-authorization-server") ->
                        respond(
                            ByteReadChannel(
                                """{"issuer":"https://entryway.test","authorization_endpoint":"https://entryway.test/a","token_endpoint":"https://entryway.test/t","pushed_authorization_request_endpoint":"https://entryway.test/p","prompt_values_supported":["none","login"]}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    request.url.toString().contains("/p") -> {
                        parBody = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
                        respond(
                            ByteReadChannel("""{"request_uri":"urn:t","expires_in":60}"""),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    }

                    else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )

        val oauth = AtOAuth("https://app.test/m.json", "app.example:/oauth-redirect", store, client)
        // Must NOT throw despite missing "create" in prompt_values_supported
        oauth.beginSignup(authServer = "entryway.test", requirePromptCreateSupport = false)

        val body = parBody
        assertNotNull(body)
        assertContains(body, "prompt=create")
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

    /**
     * Parses an `application/x-www-form-urlencoded` request body and returns the
     * decoded `redirect_uri` parameter. Asserting against the decoded value (rather
     * than substring-matching the raw encoded string) keeps the test resilient to
     * future parameter-ordering or encoding changes while still pinning the
     * passthrough contract.
     */
    private fun redirectUriParamFrom(body: String?): String? {
        val captured = assertNotNull(body)
        return parseQueryString(captured)["redirect_uri"]
    }
}
