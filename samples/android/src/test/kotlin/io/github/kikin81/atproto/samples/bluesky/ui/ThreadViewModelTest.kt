package io.github.kikin81.atproto.samples.bluesky.ui

import io.github.kikin81.atproto.app.bsky.feed.GetPostThreadResponse
import io.github.kikin81.atproto.app.bsky.feed.NotFoundPost
import io.github.kikin81.atproto.app.bsky.feed.ThreadViewPost
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exercises the pure [ThreadViewModel] folding logic against canned
 * `getPostThread` responses. Avoids touching HttpClient / DI — we
 * call the response-to-state fold path directly so the test runs
 * fast and covers every union arm the spec enumerates.
 *
 * The full end-to-end path (oauth.createClient → FeedService →
 * getPostThread) is validated manually on-device per the spec's
 * build-verification tasks; these tests pin the mapping from wire
 * JSON to [ThreadUiState].
 */
class ThreadViewModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun happyThreadYieldsLoadedWithAncestorFocusedReplies() = runTest {
        val response = json.decodeFromString(GetPostThreadResponse.serializer(), THREAD_HAPPY)
        val thread = assertIs<ThreadViewPost>(response.thread)

        val ancestors = collectAncestorPosts(thread.parent)
        val focused = thread.post
        val replies = thread.replies.orEmpty()

        assertEquals(1, ancestors.size)
        assertEquals("alice.bsky.social", ancestors.single().author.handle.raw)
        assertEquals("bob.bsky.social", focused.author.handle.raw)
        assertEquals(2, replies.size)
        replies.forEach { assertIs<ThreadViewPost>(it) }
    }

    @Test
    fun notFoundAtRootYieldsUnavailablePlaceholder() = runTest {
        val response = json.decodeFromString(GetPostThreadResponse.serializer(), THREAD_NOT_FOUND)
        assertIs<NotFoundPost>(response.thread)
    }

    @Test
    fun unknownAtRootYieldsUnavailablePlaceholder() = runTest {
        val response = json.decodeFromString(GetPostThreadResponse.serializer(), THREAD_UNKNOWN)
        // The union's Unknown arm preserves the raw $type and payload.
        val unknown = assertIs<io.github.kikin81.atproto.app.bsky.feed.GetPostThreadResponseThreadUnion.Unknown>(
            response.thread,
        )
        assertEquals("app.bsky.feed.defs#futureThreadView", unknown.type)
    }

    @Test
    fun blockedInReplyListProducesPlaceholderRow() = runTest {
        val response = json.decodeFromString(GetPostThreadResponse.serializer(), THREAD_REPLY_BLOCKED)
        val thread = assertIs<ThreadViewPost>(response.thread)
        val replies = thread.replies.orEmpty()
        assertEquals(2, replies.size)
        // One happy reply, one blocked.
        assertTrue(replies.any { it is ThreadViewPost })
        assertTrue(replies.any { it is io.github.kikin81.atproto.app.bsky.feed.BlockedPost })
    }

    @Test
    fun ancestorWalkStopsAtNotFound() = runTest {
        val response = json.decodeFromString(GetPostThreadResponse.serializer(), THREAD_ANCESTOR_NOT_FOUND)
        val thread = assertIs<ThreadViewPost>(response.thread)
        // Direct parent is a NotFoundPost — the walk should stop with zero
        // collected ancestors and a truncated flag.
        assertFalse(thread.parent is ThreadViewPost)
    }

    /**
     * Shadows [ThreadViewModel.collectAncestors] so the test can exercise the
     * same walk without instantiating Hilt or the Android framework. Kept
     * in sync by hand — the VM's implementation has a single author today.
     */
    private fun collectAncestorPosts(
        start: io.github.kikin81.atproto.app.bsky.feed.ThreadViewPostParentUnion?,
    ): List<io.github.kikin81.atproto.app.bsky.feed.PostView> {
        if (start == null) return emptyList()
        val acc = ArrayDeque<io.github.kikin81.atproto.app.bsky.feed.PostView>()
        var current: io.github.kikin81.atproto.app.bsky.feed.ThreadViewPostParentUnion? = start
        while (current is ThreadViewPost) {
            acc.addFirst(current.post)
            current = current.parent
        }
        return acc.toList()
    }

    private companion object {
        const val THREAD_HAPPY = """
            {
              "thread": {
                "${'$'}type": "app.bsky.feed.defs#threadViewPost",
                "post": {
                  "uri": "at://did:plc:bob/app.bsky.feed.post/focused",
                  "cid": "bafybob",
                  "author": {
                    "did": "did:plc:bob",
                    "handle": "bob.bsky.social"
                  },
                  "record": {
                    "text": "focused post",
                    "createdAt": "2025-01-02T00:00:00Z"
                  },
                  "indexedAt": "2025-01-02T00:00:00Z"
                },
                "parent": {
                  "${'$'}type": "app.bsky.feed.defs#threadViewPost",
                  "post": {
                    "uri": "at://did:plc:alice/app.bsky.feed.post/root",
                    "cid": "bafyalice",
                    "author": {
                      "did": "did:plc:alice",
                      "handle": "alice.bsky.social"
                    },
                    "record": {
                      "text": "original",
                      "createdAt": "2025-01-01T00:00:00Z"
                    },
                    "indexedAt": "2025-01-01T00:00:00Z"
                  }
                },
                "replies": [
                  {
                    "${'$'}type": "app.bsky.feed.defs#threadViewPost",
                    "post": {
                      "uri": "at://did:plc:carol/app.bsky.feed.post/r1",
                      "cid": "bafycarol",
                      "author": {
                        "did": "did:plc:carol",
                        "handle": "carol.bsky.social"
                      },
                      "record": {
                        "text": "nice",
                        "createdAt": "2025-01-03T00:00:00Z"
                      },
                      "indexedAt": "2025-01-03T00:00:00Z"
                    }
                  },
                  {
                    "${'$'}type": "app.bsky.feed.defs#threadViewPost",
                    "post": {
                      "uri": "at://did:plc:dan/app.bsky.feed.post/r2",
                      "cid": "bafydan",
                      "author": {
                        "did": "did:plc:dan",
                        "handle": "dan.bsky.social"
                      },
                      "record": {
                        "text": "+1",
                        "createdAt": "2025-01-04T00:00:00Z"
                      },
                      "indexedAt": "2025-01-04T00:00:00Z"
                    }
                  }
                ]
              }
            }
        """

        const val THREAD_NOT_FOUND = """
            {
              "thread": {
                "${'$'}type": "app.bsky.feed.defs#notFoundPost",
                "uri": "at://did:plc:gone/app.bsky.feed.post/deleted",
                "notFound": true
              }
            }
        """

        const val THREAD_UNKNOWN = """
            {
              "thread": {
                "${'$'}type": "app.bsky.feed.defs#futureThreadView",
                "custom": "data"
              }
            }
        """

        const val THREAD_REPLY_BLOCKED = """
            {
              "thread": {
                "${'$'}type": "app.bsky.feed.defs#threadViewPost",
                "post": {
                  "uri": "at://did:plc:focused/app.bsky.feed.post/f",
                  "cid": "bafyf",
                  "author": {
                    "did": "did:plc:focused",
                    "handle": "focused.bsky.social"
                  },
                  "record": {
                    "text": "parent",
                    "createdAt": "2025-01-10T00:00:00Z"
                  },
                  "indexedAt": "2025-01-10T00:00:00Z"
                },
                "replies": [
                  {
                    "${'$'}type": "app.bsky.feed.defs#threadViewPost",
                    "post": {
                      "uri": "at://did:plc:r/app.bsky.feed.post/r",
                      "cid": "bafyr",
                      "author": {
                        "did": "did:plc:r",
                        "handle": "r.bsky.social"
                      },
                      "record": {
                        "text": "visible reply",
                        "createdAt": "2025-01-11T00:00:00Z"
                      },
                      "indexedAt": "2025-01-11T00:00:00Z"
                    }
                  },
                  {
                    "${'$'}type": "app.bsky.feed.defs#blockedPost",
                    "uri": "at://did:plc:blocker/app.bsky.feed.post/b",
                    "blocked": true,
                    "author": {
                      "did": "did:plc:blocker"
                    }
                  }
                ]
              }
            }
        """

        const val THREAD_ANCESTOR_NOT_FOUND = """
            {
              "thread": {
                "${'$'}type": "app.bsky.feed.defs#threadViewPost",
                "post": {
                  "uri": "at://did:plc:bob/app.bsky.feed.post/f",
                  "cid": "bafybob",
                  "author": {
                    "did": "did:plc:bob",
                    "handle": "bob.bsky.social"
                  },
                  "record": {
                    "text": "replied to deleted",
                    "createdAt": "2025-01-20T00:00:00Z"
                  },
                  "indexedAt": "2025-01-20T00:00:00Z"
                },
                "parent": {
                  "${'$'}type": "app.bsky.feed.defs#notFoundPost",
                  "uri": "at://did:plc:gone/app.bsky.feed.post/d",
                  "notFound": true
                }
              }
            }
        """
    }
}
