package io.github.kikin81.atproto.runtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Serializable
    data class UploadBlobResponse(val blob: String)

    @Test
    fun raw_bytes_procedure_posts_with_content_type() = runTest {
        val (client, engine) = makeClient {
            ok("""{"blob":"bafy"}""")
        }
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        val out = client.procedure(
            nsid = "com.atproto.repo.uploadBlob",
            params = Unit,
            paramsSerializer = Unit.serializer(),
            input = pngBytes,
            inputContentType = ContentType.Image.PNG,
            responseSerializer = UploadBlobResponse.serializer(),
        )

        assertEquals("bafy", out.blob)
        val req = engine.requestHistory.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("https://bsky.social/xrpc/com.atproto.repo.uploadBlob", req.url.toString())
        val body = req.body as OutgoingContent.ByteArrayContent
        assertContentEquals(pngBytes, body.bytes())
        assertEquals("image/png", body.contentType.toString())
    }

    @Test
    fun raw_bytes_procedure_applies_auth() = runTest {
        val (client, engine) = makeClient(auth = BearerTokenAuth("blob-token")) {
            ok("""{"blob":"bafy"}""")
        }

        client.procedure(
            nsid = "com.atproto.repo.uploadBlob",
            params = Unit,
            paramsSerializer = Unit.serializer(),
            input = byteArrayOf(1, 2, 3),
            inputContentType = ContentType.Application.OctetStream,
            responseSerializer = UploadBlobResponse.serializer(),
        )

        assertEquals(
            "Bearer blob-token",
            engine.requestHistory.single().headers[HttpHeaders.Authorization],
        )
    }

    @Test
    fun raw_bytes_procedure_retries_once_on_401_with_refreshed_auth() = runTest {
        val auth = RefreshingAuth(initial = "stale-token", refreshed = "fresh-token")
        var calls = 0
        val (client, engine) = makeClient(auth = auth) {
            calls++
            if (calls == 1) {
                respond(ByteReadChannel("""{"error":"AuthExpired"}"""), HttpStatusCode.Unauthorized, jsonHeaders)
            } else {
                ok("""{"blob":"bafy"}""")
            }
        }
        val payload = byteArrayOf(0x42, 0x42, 0x42)

        val out = client.procedure(
            nsid = "com.atproto.repo.uploadBlob",
            params = Unit,
            paramsSerializer = Unit.serializer(),
            input = payload,
            inputContentType = ContentType.Image.JPEG,
            responseSerializer = UploadBlobResponse.serializer(),
        )

        assertEquals("bafy", out.blob)
        assertEquals(2, engine.requestHistory.size)
        assertEquals("Bearer stale-token", engine.requestHistory[0].headers[HttpHeaders.Authorization])
        assertEquals("Bearer fresh-token", engine.requestHistory[1].headers[HttpHeaders.Authorization])
        // Body and Content-Type are preserved across the retry.
        for (req in engine.requestHistory) {
            val body = req.body as OutgoingContent.ByteArrayContent
            assertContentEquals(payload, body.bytes())
            assertEquals("image/jpeg", body.contentType.toString())
        }
    }

    @Test
    fun raw_bytes_procedure_maps_typed_error() = runTest {
        val mapper = XrpcErrorMapper { name, message, status ->
            when (name) {
                "InvalidBlobSize" -> InvalidBlobSize(message, status)
                else -> XrpcError.Unknown(name, message, status)
            }
        }
        val (client, _) = makeClient {
            respond(
                ByteReadChannel("""{"error":"InvalidBlobSize","message":"too large"}"""),
                HttpStatusCode.BadRequest,
                jsonHeaders,
            )
        }

        val err = assertFailsWith<InvalidBlobSize> {
            client.procedure(
                nsid = "com.atproto.repo.uploadBlob",
                params = Unit,
                paramsSerializer = Unit.serializer(),
                input = byteArrayOf(1, 2, 3),
                inputContentType = ContentType.Application.OctetStream,
                responseSerializer = UploadBlobResponse.serializer(),
                errorMapper = mapper,
            )
        }
        assertEquals("too large", err.errorMessage)
        assertEquals(400, err.status)
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

private class InvalidBlobSize(message: String?, status: Int) : XrpcError("InvalidBlobSize", message, status)

private class RefreshingAuth(initial: String, private val refreshed: String) : AuthProvider {
    private var token: String = initial
    override suspend fun authHeaders(method: String, url: String): Map<String, String> = mapOf("Authorization" to "Bearer $token")
    override suspend fun onUnauthorized(responseHeaders: Map<String, String>): Boolean {
        token = refreshed
        return true
    }
}
