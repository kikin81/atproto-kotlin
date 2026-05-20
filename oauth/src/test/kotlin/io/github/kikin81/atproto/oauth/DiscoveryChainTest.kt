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
import kotlin.test.assertTrue

class DiscoveryChainTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun fullDiscoveryChainResolvesHandleToAuthServerMetadata() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                when {
                    request.url.toString().contains("/.well-known/atproto-did") ->
                        respond(ByteReadChannel("did:plc:testuser123"), HttpStatusCode.OK)

                    request.url.toString().contains("plc.directory/did:plc:testuser123") ->
                        respond(
                            ByteReadChannel(
                                """{"id":"did:plc:testuser123","alsoKnownAs":["at://alice.bsky.social"],"service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"https://pds.example.com"}]}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    request.url.toString().contains("/.well-known/oauth-protected-resource") ->
                        respond(
                            ByteReadChannel("""{"authorization_servers":["https://auth.example.com"]}"""),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    request.url.toString().contains("/.well-known/oauth-authorization-server") ->
                        respond(
                            ByteReadChannel(
                                """{"issuer":"https://auth.example.com","authorization_endpoint":"https://auth.example.com/oauth/authorize","token_endpoint":"https://auth.example.com/oauth/token","pushed_authorization_request_endpoint":"https://auth.example.com/oauth/par"}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )

        val metadata = DiscoveryChain(client).resolve("alice.bsky.social")

        assertEquals("did:plc:testuser123", metadata.did)
        assertEquals("alice.bsky.social", metadata.handle)
        assertEquals("https://pds.example.com", metadata.pdsUrl)
        assertEquals("https://auth.example.com", metadata.issuer)
        assertEquals("https://auth.example.com/oauth/authorize", metadata.authorizationEndpoint)
        assertEquals("https://auth.example.com/oauth/token", metadata.tokenEndpoint)
        assertEquals("https://auth.example.com/oauth/par", metadata.parEndpoint)
    }

    @Test
    fun handleResolutionFailureThrowsDiscoveryException() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(ByteReadChannel(""), HttpStatusCode.NotFound)
            },
        )
        val ex = assertFailsWith<OAuthDiscoveryException> {
            DiscoveryChain(client).resolve("nonexistent.example.com")
        }
        assertTrue(ex.message!!.contains("nonexistent.example.com"))
    }

    @Test
    fun invalidDidFromHandleResolutionThrows() = runTest {
        val client = HttpClient(
            MockEngine {
                respond(ByteReadChannel("not-a-did"), HttpStatusCode.OK)
            },
        )
        val ex = assertFailsWith<OAuthDiscoveryException> {
            DiscoveryChain(client).resolve("bad.example.com")
        }
        assertTrue(ex.message!!.contains("DID"), "expected DID-related error, got: ${ex.message}")
    }

    @Test
    fun missingPdsServiceThrows() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                when {
                    request.url.toString().contains("/.well-known/atproto-did") ->
                        respond(ByteReadChannel("did:plc:nopds"), HttpStatusCode.OK)

                    request.url.toString().contains("plc.directory") ->
                        respond(
                            ByteReadChannel("""{"id":"did:plc:nopds","alsoKnownAs":["at://nopds.example.com"],"service":[]}"""),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )
        val ex = assertFailsWith<OAuthDiscoveryException> {
            DiscoveryChain(client).resolve("nopds.example.com")
        }
        assertTrue(ex.message!!.contains("#atproto_pds"))
    }

    @Test
    fun bidirectionalHandleVerificationFails() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                when {
                    request.url.toString().contains("/.well-known/atproto-did") ->
                        respond(ByteReadChannel("did:plc:wronghandle"), HttpStatusCode.OK)

                    request.url.toString().contains("plc.directory") ->
                        respond(
                            ByteReadChannel(
                                """{"id":"did:plc:wronghandle","alsoKnownAs":["at://different.example.com"],"service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"https://pds.example.com"}]}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )
        val ex = assertFailsWith<OAuthDiscoveryException> {
            DiscoveryChain(client).resolve("claimed.example.com")
        }
        assertTrue(ex.message!!.contains("Bidirectional handle verification failed"))
    }

    @Test
    fun emptyAuthorizationServersArrayThrows() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                when {
                    request.url.toString().contains("/.well-known/atproto-did") ->
                        respond(ByteReadChannel("did:plc:emptyauth"), HttpStatusCode.OK)

                    request.url.toString().contains("plc.directory") ->
                        respond(
                            ByteReadChannel(
                                """{"id":"did:plc:emptyauth","alsoKnownAs":["at://empty.example.com"],"service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"https://pds.example.com"}]}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    request.url.toString().contains("/.well-known/oauth-protected-resource") ->
                        respond(
                            ByteReadChannel("""{"authorization_servers":[]}"""),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )
        val ex = assertFailsWith<OAuthDiscoveryException> {
            DiscoveryChain(client).resolve("empty.example.com")
        }
        assertTrue(ex.message!!.contains("empty authorization_servers"))
    }

    @Test
    fun didInputSkipsHandleResolution() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                when {
                    request.url.toString().contains("plc.directory/did:plc:direct") ->
                        respond(
                            ByteReadChannel(
                                """{"id":"did:plc:direct","alsoKnownAs":["at://direct.example.com"],"service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"https://pds.example.com"}]}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    request.url.toString().contains("/.well-known/oauth-protected-resource") ->
                        respond(
                            ByteReadChannel("""{"authorization_servers":["https://auth.example.com"]}"""),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    request.url.toString().contains("/.well-known/oauth-authorization-server") ->
                        respond(
                            ByteReadChannel(
                                """{"issuer":"https://auth.example.com","authorization_endpoint":"https://auth.example.com/authorize","token_endpoint":"https://auth.example.com/token","pushed_authorization_request_endpoint":"https://auth.example.com/par"}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )

                    else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )
        val metadata = DiscoveryChain(client).resolve("did:plc:direct")
        assertEquals("did:plc:direct", metadata.did)
        assertEquals("direct.example.com", metadata.handle)
    }

    @Test
    fun resolveKnownAuthServerSkipsIdentityResolution() = runTest {
        var requestedUrls = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                requestedUrls.add(request.url.toString())
                if (request.url.toString().contains("/.well-known/oauth-authorization-server")) {
                    respond(
                        ByteReadChannel(
                            """{"issuer":"https://bsky.social","authorization_endpoint":"https://bsky.social/oauth/authorize","token_endpoint":"https://bsky.social/oauth/token","pushed_authorization_request_endpoint":"https://bsky.social/oauth/par","prompt_values_supported":["none","login","consent","select_account","create"]}""",
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                } else {
                    respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )

        val metadata = DiscoveryChain(client).resolveKnownAuthServer("bsky.social")

        // Endpoints populated
        assertEquals("https://bsky.social", metadata.issuer)
        assertEquals("https://bsky.social/oauth/authorize", metadata.authorizationEndpoint)
        assertEquals("https://bsky.social/oauth/token", metadata.tokenEndpoint)
        assertEquals("https://bsky.social/oauth/par", metadata.parEndpoint)

        // prompt_values_supported parsed
        assertTrue(metadata.promptValuesSupported.contains("create"))

        // Identity fields null — discovery was short-circuited
        assertEquals(null, metadata.did)
        assertEquals(null, metadata.handle)
        assertEquals(null, metadata.pdsUrl)

        // Only one HTTP call — the auth-server metadata fetch
        assertEquals(1, requestedUrls.size, "expected single HTTP call, got: $requestedUrls")
        assertTrue(requestedUrls[0].contains("/.well-known/oauth-authorization-server"))
    }

    @Test
    fun resolveKnownAuthServerAcceptsFullUrl() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                if (request.url.toString().contains("/.well-known/oauth-authorization-server")) {
                    respond(
                        ByteReadChannel(
                            """{"issuer":"https://entryway.test","authorization_endpoint":"https://entryway.test/a","token_endpoint":"https://entryway.test/t","pushed_authorization_request_endpoint":"https://entryway.test/p"}""",
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                } else {
                    respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )

        val metadata = DiscoveryChain(client).resolveKnownAuthServer("https://entryway.test")
        assertEquals("https://entryway.test", metadata.issuer)
        // promptValuesSupported empty when server doesn't advertise it
        assertTrue(metadata.promptValuesSupported.isEmpty())
    }

    @Test
    fun resolveKnownAuthServerRejectsPlainHttpUrl() = runTest {
        val client = HttpClient(MockEngine { respond(ByteReadChannel(""), HttpStatusCode.OK) })
        val ex = assertFailsWith<OAuthDiscoveryException> {
            DiscoveryChain(client).resolveKnownAuthServer("http://insecure.test")
        }
        assertTrue(ex.message!!.contains("HTTPS"), "expected HTTPS-related error, got: ${ex.message}")
    }

    @Test
    fun hydrateIdentityRetriesWhenDocReturnsPartialThenComplete() = runTest {
        var attempt = 0
        val client = HttpClient(
            MockEngine { request ->
                if (request.url.toString().contains("plc.directory/did:plc:partial")) {
                    attempt++
                    val body = when (attempt) {
                        // First attempt: DID doc exists but service[] is empty and alsoKnownAs is empty
                        1 -> """{"id":"did:plc:partial","alsoKnownAs":[],"service":[]}"""
                        // Second attempt: handle is now in alsoKnownAs, still no PDS
                        2 -> """{"id":"did:plc:partial","alsoKnownAs":["at://late.bsky.social"],"service":[]}"""
                        // Third attempt: both fields populated
                        else ->
                            """{"id":"did:plc:partial","alsoKnownAs":["at://late.bsky.social"],"service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"https://late-pds.test"}]}"""
                    }
                    respond(ByteReadChannel(body), HttpStatusCode.OK, jsonHeaders)
                } else {
                    respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )

        val hydrated = DiscoveryChain(client).hydrateIdentityFromDid("did:plc:partial")
        assertEquals("late.bsky.social", hydrated.handle)
        assertEquals("https://late-pds.test", hydrated.pdsUrl)
        assertTrue(attempt >= 3, "expected at least 3 attempts under retry budget, got $attempt")
    }

    @Test
    fun hydrateIdentityRetainsBestKnownAcrossAttempts() = runTest {
        var attempt = 0
        val client = HttpClient(
            MockEngine { request ->
                if (request.url.toString().contains("plc.directory/did:plc:flaky")) {
                    attempt++
                    when (attempt) {
                        // Attempt 1: handle present, no PDS
                        1 -> respond(
                            ByteReadChannel(
                                """{"id":"did:plc:flaky","alsoKnownAs":["at://flaky.test"],"service":[]}""",
                            ),
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                        // Attempts 2-4: 404 (PLC fluttered offline)
                        else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                    }
                } else {
                    respond(ByteReadChannel(""), HttpStatusCode.NotFound)
                }
            },
        )

        val hydrated = DiscoveryChain(client).hydrateIdentityFromDid("did:plc:flaky")
        // Best-known handle from attempt 1 is preserved through subsequent failures
        assertEquals("flaky.test", hydrated.handle)
        assertEquals(null, hydrated.pdsUrl)
    }
}
