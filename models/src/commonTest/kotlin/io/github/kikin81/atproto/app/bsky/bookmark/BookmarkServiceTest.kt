package io.github.kikin81.atproto.app.bsky.bookmark

import io.github.kikin81.atproto.runtime.XrpcClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BookmarkServiceTest {

    @Test
    fun getBookmarksHitsCorrectXrpcPathWithPaginatedQueryParams() = runTest {
        var capturedUrl: String? = null
        var capturedMethod: HttpMethod? = null
        val client = HttpClient(
            MockEngine { request ->
                capturedUrl = request.url.toString()
                capturedMethod = request.method
                respond(
                    ByteReadChannel("""{"cursor":"next","bookmarks":[]}"""),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val xrpc = XrpcClient(baseUrl = "https://pds.test", httpClient = client)

        val response = BookmarkService(xrpc).getBookmarks(
            GetBookmarksRequest(limit = 25, cursor = "abc"),
        )

        assertEquals(HttpMethod.Get, capturedMethod)
        val url = capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/app.bsky.bookmark.getBookmarks")
        assertContains(url, "limit=25")
        assertContains(url, "cursor=abc")
        assertEquals("next", response.cursor)
        assertEquals(emptyList(), response.bookmarks)
    }
}
