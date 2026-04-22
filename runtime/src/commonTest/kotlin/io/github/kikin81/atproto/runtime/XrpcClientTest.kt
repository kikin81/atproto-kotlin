package io.github.kikin81.atproto.runtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

private fun MockRequestHandleScope.ok(body: String): HttpResponseData = respond(ByteReadChannel(body), HttpStatusCode.OK, jsonHeaders)

private fun makeClient(
    auth: AuthProvider = NoAuth,
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): Pair<XrpcClient, MockEngine> {
    val engine = MockEngine(handler)
    val client = XrpcClient(
        baseUrl = "https://bsky.social",
        httpClient = HttpClient(engine),
        authProvider = auth,
    )
    return client to engine
}

class XrpcClientTest {

    @Serializable
    data class TimelineParams(val limit: Int? = null, val cursor: String? = null)

    @Serializable
    data class TimelineResponse(val cursor: String? = null, val feed: List<String>)

    @Serializable
    data class CreateSessionInput(val identifier: String, val password: String)

    @Serializable
    data class CreateSessionOutput(val accessJwt: String, val did: String)

    @Serializable
    data class SearchParams(val q: String, val tags: List<String> = emptyList())

    @Test
    fun query_issues_get_with_params() = runTest {
        val (client, engine) = makeClient { ok("""{"feed":["a","b"]}""") }

        val response = client.query(
            nsid = "app.bsky.feed.getTimeline",
            params = TimelineParams(limit = 50),
            paramsSerializer = TimelineParams.serializer(),
            responseSerializer = TimelineResponse.serializer(),
        )

        assertEquals(listOf("a", "b"), response.feed)
        val req = engine.requestHistory.single()
        assertEquals(HttpMethod.Get, req.method)
        assertEquals("https://bsky.social/xrpc/app.bsky.feed.getTimeline?limit=50", req.url.toString())
        assertNull(req.headers[HttpHeaders.Authorization])
    }

    @Test
    fun query_omits_null_params() = runTest {
        val (client, engine) = makeClient { ok("""{"feed":[]}""") }

        client.query(
            nsid = "app.bsky.feed.getTimeline",
            params = TimelineParams(limit = null, cursor = null),
            paramsSerializer = TimelineParams.serializer(),
            responseSerializer = TimelineResponse.serializer(),
        )

        assertEquals(
            "https://bsky.social/xrpc/app.bsky.feed.getTimeline",
            engine.requestHistory.single().url.toString(),
        )
    }

    @Test
    fun query_arrays_repeat_key() = runTest {
        val (client, engine) = makeClient { ok("""{"feed":[]}""") }

        client.query(
            nsid = "app.bsky.feed.searchPosts",
            params = SearchParams(q = "hi", tags = listOf("foo", "bar")),
            paramsSerializer = SearchParams.serializer(),
            responseSerializer = TimelineResponse.serializer(),
        )

        val url = engine.requestHistory.single().url.toString()
        assertTrue("tags=foo" in url && "tags=bar" in url, "expected array-repeats-key in $url")
        assertTrue("q=hi" in url)
    }

    @Test
    fun procedure_posts_json_body() = runTest {
        val (client, engine) = makeClient {
            ok("""{"accessJwt":"tok","did":"did:plc:abc"}""")
        }

        val out = client.procedure(
            nsid = "com.atproto.server.createSession",
            params = Unit,
            paramsSerializer = Unit.serializer(),
            input = CreateSessionInput(identifier = "alice", password = "pw"),
            inputSerializer = CreateSessionInput.serializer(),
            responseSerializer = CreateSessionOutput.serializer(),
        )

        assertEquals("tok", out.accessJwt)
        val req = engine.requestHistory.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("https://bsky.social/xrpc/com.atproto.server.createSession", req.url.toString())
        val body = req.body as TextContent
        assertEquals("application/json", body.contentType.toString())
        assertEquals("""{"identifier":"alice","password":"pw"}""", body.text)
    }

    @Test
    fun auth_provider_adds_bearer_header() = runTest {
        val (client, engine) = makeClient(auth = BearerTokenAuth("abc123")) { ok("""{"feed":[]}""") }

        client.query(
            nsid = "app.bsky.feed.getTimeline",
            params = TimelineParams(),
            paramsSerializer = TimelineParams.serializer(),
            responseSerializer = TimelineResponse.serializer(),
        )

        assertEquals("Bearer abc123", engine.requestHistory.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun per_call_auth_overrides_client_auth() = runTest {
        val (client, engine) = makeClient(auth = BearerTokenAuth("client-token")) {
            ok("""{"feed":[]}""")
        }

        client.query(
            nsid = "app.bsky.feed.getTimeline",
            params = TimelineParams(),
            paramsSerializer = TimelineParams.serializer(),
            responseSerializer = TimelineResponse.serializer(),
            auth = BearerTokenAuth("call-token"),
        )

        assertEquals("Bearer call-token", engine.requestHistory.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun declared_error_is_mapped_to_typed_variant() = runTest {
        val mapper = XrpcErrorMapper { name, message, status ->
            when (name) {
                "AuthMissing" -> AuthMissing(message, status)
                else -> XrpcError.Unknown(name, message, status)
            }
        }
        val (client, _) = makeClient {
            respond(
                ByteReadChannel("""{"error":"AuthMissing","message":"missing token"}"""),
                HttpStatusCode.Unauthorized,
                jsonHeaders,
            )
        }

        val err = assertFailsWith<AuthMissing> {
            client.query(
                nsid = "app.bsky.feed.getTimeline",
                params = TimelineParams(),
                paramsSerializer = TimelineParams.serializer(),
                responseSerializer = TimelineResponse.serializer(),
                errorMapper = mapper,
            )
        }
        assertEquals("missing token", err.errorMessage)
        assertEquals(401, err.status)
    }

    @Test
    fun unknown_error_falls_through_to_unknown() = runTest {
        val (client, _) = makeClient {
            respond(
                ByteReadChannel("""{"error":"Weird","message":"what"}"""),
                HttpStatusCode.InternalServerError,
                jsonHeaders,
            )
        }

        val err = assertFailsWith<XrpcError.Unknown> {
            client.query(
                nsid = "app.bsky.feed.getTimeline",
                params = TimelineParams(),
                paramsSerializer = TimelineParams.serializer(),
                responseSerializer = TimelineResponse.serializer(),
            )
        }
        assertEquals("Weird", err.errorName)
        assertEquals(500, err.status)
    }
}

private class AuthMissing(message: String?, status: Int) : XrpcError("AuthMissing", message, status)
