package io.github.kikin81.atproto.test

import io.github.kikin81.atproto.runtime.XrpcClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel

/**
 * Lightweight test fixture for generated XRPC service tests.
 *
 * Sets up a Ktor [MockEngine]-backed [XrpcClient] that captures the most
 * recent outgoing request (URL, method, headers, content-type, body) and serves
 * the most recently configured canned response. Designed for `@BeforeTest`
 * construction; per-test response is configured via [respondWith] before
 * invoking the service under test.
 *
 * Ktor's `TextContent` (used by JSON-body procedures) extends
 * `OutgoingContent.ByteArrayContent`, so the body capture covers both JSON
 * procedures and raw-bytes uploads with a single branch.
 */
class MockXrpcFixture(baseUrl: String = "https://pds.test") {
    private var nextResponseJson: String = "{}"
    private var nextStatus: HttpStatusCode = HttpStatusCode.OK

    var capturedUrl: String? = null
        private set
    var capturedMethod: HttpMethod? = null
        private set
    var capturedContentType: String? = null
        private set
    var capturedBody: String? = null
        private set
    var capturedHeaders: Map<String, String> = emptyMap()
        private set

    val client: XrpcClient = XrpcClient(
        baseUrl = baseUrl,
        httpClient = HttpClient(
            MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method
                // Normalize names to lower-case (HTTP header names are
                // case-insensitive) and join multi-valued headers rather than
                // dropping all but the first, so assertions are stable and
                // never throw on an empty value list.
                capturedHeaders = request.headers.entries()
                    .associate { (name, values) -> name.lowercase() to values.joinToString(",") }
                val body = request.body
                if (body is OutgoingContent.ByteArrayContent) {
                    capturedBody = body.bytes().decodeToString()
                    // Content-Type rides on the OutgoingContent (Ktor's request
                    // builder applies `contentType(...)` to the body, not to the
                    // top-level request headers).
                    capturedContentType = body.contentType?.toString()
                }
                respond(
                    ByteReadChannel(nextResponseJson),
                    nextStatus,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ),
    )

    fun respondWith(json: String, status: HttpStatusCode = HttpStatusCode.OK) {
        nextResponseJson = json
        nextStatus = status
    }
}
