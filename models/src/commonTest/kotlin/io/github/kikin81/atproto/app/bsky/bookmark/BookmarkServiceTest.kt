package io.github.kikin81.atproto.app.bsky.bookmark

import io.github.kikin81.atproto.test.MockXrpcFixture
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BookmarkServiceTest {

    private lateinit var fixture: MockXrpcFixture

    @BeforeTest
    fun setup() {
        fixture = MockXrpcFixture()
    }

    @Test
    fun getBookmarksHitsCorrectXrpcPathWithPaginatedQueryParams() = runTest {
        fixture.respondWith("""{"cursor":"next","bookmarks":[]}""")

        val response = BookmarkService(fixture.client).getBookmarks(
            GetBookmarksRequest(limit = 25, cursor = "abc"),
        )

        assertEquals(HttpMethod.Get, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/app.bsky.bookmark.getBookmarks")
        assertContains(url, "limit=25")
        assertContains(url, "cursor=abc")
        assertEquals("next", response.cursor)
        assertEquals(emptyList(), response.bookmarks)
    }
}
