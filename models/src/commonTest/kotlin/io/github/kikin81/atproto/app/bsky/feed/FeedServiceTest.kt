package io.github.kikin81.atproto.app.bsky.feed

import io.github.kikin81.atproto.runtime.AtField
import io.github.kikin81.atproto.runtime.AtIdentifier
import io.github.kikin81.atproto.runtime.AtUri
import io.github.kikin81.atproto.runtime.Language
import io.github.kikin81.atproto.test.MockXrpcFixture
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FeedServiceTest {

    private lateinit var fixture: MockXrpcFixture

    @BeforeTest
    fun setup() {
        fixture = MockXrpcFixture()
    }

    @Test
    fun getActorFeedsHitsCorrectXrpcPathWithPaginatedQueryParams() = runTest {
        fixture.respondWith("""{"cursor":"next","feeds":[]}""")

        val response = FeedService(fixture.client).getActorFeeds(
            GetActorFeedsRequest(actor = AtIdentifier("alice.test"), limit = 25, cursor = "abc"),
        )

        assertEquals(HttpMethod.Get, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/app.bsky.feed.getActorFeeds")
        assertContains(url, "actor=alice.test")
        assertContains(url, "limit=25")
        assertContains(url, "cursor=abc")
        assertEquals("next", response.cursor)
        assertEquals(emptyList(), response.feeds)
    }

    @Test
    fun describeFeedGeneratorHitsCorrectXrpcPathWithoutParams() = runTest {
        fixture.respondWith("""{"did":"did:web:feed.test","feeds":[]}""")

        val response = FeedService(fixture.client).describeFeedGenerator()

        assertEquals(HttpMethod.Get, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/app.bsky.feed.describeFeedGenerator")
        assertEquals("did:web:feed.test", response.did.raw)
    }

    @Test
    fun searchPostsV2SerializesRepeatedFilterParamsAndDecodesQueryLanguages() = runTest {
        fixture.respondWith(
            """
            {
              "posts": [],
              "cursor": "next",
              "hitsTotal": 42,
              "detectedQueryLanguages": ["ja", "ko"]
            }
            """.trimIndent(),
        )

        val response = FeedService(fixture.client).searchPostsV2(
            SearchPostsV2Request(
                query = "kotlin",
                sort = "top",
                limit = 25,
                authors = listOf(AtIdentifier("alice.test"), AtIdentifier("bob.test")),
                hashtags = listOf("kmp"),
                languages = listOf(Language("ja")),
                hasVideo = true,
            ),
        )

        assertEquals(HttpMethod.Get, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/app.bsky.feed.searchPostsV2")
        assertContains(url, "query=kotlin")
        assertContains(url, "sort=top")
        assertContains(url, "limit=25")
        // Array params repeat the key rather than joining — both authors survive.
        assertContains(url, "authors=alice.test")
        assertContains(url, "authors=bob.test")
        assertContains(url, "hashtags=kmp")
        assertContains(url, "languages=ja")
        assertContains(url, "hasVideo=true")
        assertEquals("next", response.cursor)
        assertEquals(42L, response.hitsTotal)
        assertEquals(listOf("ja", "ko"), response.detectedQueryLanguages)
        assertEquals(emptyList(), response.posts)
    }

    @Test
    fun sendInteractionsPostsInteractionsToCorrectXrpcPath() = runTest {
        fixture.respondWith("{}")

        FeedService(fixture.client).sendInteractions(
            SendInteractionsRequest(
                interactions = listOf(
                    Interaction(
                        item = AtField.Defined(AtUri("at://did:plc:abc/app.bsky.feed.post/123")),
                        event = AtField.Defined("app.bsky.feed.defs#interactionLike"),
                    ),
                ),
            ),
        )

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/app.bsky.feed.sendInteractions")
        val body = fixture.capturedBody
        assertNotNull(body)
        assertContains(body, "interactions")
        assertContains(body, "app.bsky.feed.defs#interactionLike")
    }
}
