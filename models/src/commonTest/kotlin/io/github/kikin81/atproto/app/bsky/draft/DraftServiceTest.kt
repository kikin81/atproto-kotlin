package io.github.kikin81.atproto.app.bsky.draft

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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DraftServiceTest {

    @Test
    fun createDraftPostsJsonEncodedInputToCorrectXrpcPath() = runTest {
        var capturedUrl: String? = null
        var capturedMethod: HttpMethod? = null
        var capturedBody: String? = null
        val client = HttpClient(
            MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method
                val body = request.body
                if (body is OutgoingContent.ByteArrayContent) {
                    capturedBody = body.bytes().decodeToString()
                }
                respond(
                    ByteReadChannel("""{"id":"draft-123"}"""),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val xrpc = XrpcClient(baseUrl = "https://pds.test", httpClient = client)

        val response = DraftService(xrpc).createDraft(
            CreateDraftRequest(
                draft = Draft(
                    posts = listOf(DraftPost(text = "hello from a draft")),
                ),
            ),
        )

        assertEquals(HttpMethod.Post, capturedMethod)
        val url = capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/app.bsky.draft.createDraft")

        val body = capturedBody
        assertNotNull(body)
        assertContains(body, "\"draft\"")
        assertContains(body, "hello from a draft")

        assertEquals("draft-123", response.id)
    }
}
