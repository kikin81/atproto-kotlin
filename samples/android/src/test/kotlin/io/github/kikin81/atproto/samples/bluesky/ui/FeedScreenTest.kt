package io.github.kikin81.atproto.samples.bluesky.ui

import io.github.kikin81.atproto.app.bsky.embed.ImagesView
import io.github.kikin81.atproto.app.bsky.embed.RecordView
import io.github.kikin81.atproto.app.bsky.embed.RecordWithMediaView
import io.github.kikin81.atproto.app.bsky.feed.GetTimelineResponse
import io.github.kikin81.atproto.app.bsky.feed.Post
import io.github.kikin81.atproto.app.bsky.feed.PostViewEmbedUnion
import io.github.kikin81.atproto.app.bsky.feed.ReasonRepost
import io.github.kikin81.atproto.runtime.decodeRecord
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feed-side integration tests. These don't touch Android UI — they drive the
 * `getTimeline` call through an XrpcClient backed by [MockEngine] and verify
 * the generated open-union dispatch plus the helpers in [FeedScreen] (text
 * extraction from the `record: JsonObject`, image-view pattern matching,
 * Unknown fall-through).
 */
class FeedScreenTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun imagesEmbedPatternMatchesAndExtractsThumbUrl() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_WITH_IMAGES_EMBED,
        )
        val post = response.feed.single().post

        val embed = assertNotNull(post.embed)
        val images = assertIs<ImagesView>(embed)
        assertEquals(1, images.images.size)
        assertEquals("https://cdn.bsky.app/img/thumb.jpg", images.images[0].thumb.raw)

        // Helper under test
        assertEquals("https://cdn.bsky.app/img/thumb.jpg", extractFirstImageThumb(post))
        assertEquals("hello world", post.record.decodeRecord<Post>().text)
    }

    @Test
    fun unknownEmbedVariantFallsThroughToNullThumb() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_WITH_UNKNOWN_EMBED,
        )
        val post = response.feed.single().post

        val embed = assertNotNull(post.embed)
        val unknown = assertIs<PostViewEmbedUnion.Unknown>(embed)
        assertEquals("app.bsky.embed.futureVariant#view", unknown.type)

        // The sample must NOT crash or drop the post when the embed is
        // Unknown — thumb extraction returns null, feed list still contains
        // the post.
        assertNull(extractFirstImageThumb(post))
        assertEquals("the future", post.record.decodeRecord<Post>().text)
        assertEquals(1, response.feed.size)
    }

    @Test
    fun postWithNoEmbedReturnsNullThumb() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_WITH_NO_EMBED,
        )
        val post = response.feed.single().post

        assertNull(post.embed)
        assertNull(extractFirstImageThumb(post))
        assertEquals("plain text post", post.record.decodeRecord<Post>().text)
    }

    @Test
    fun repostReasonSurfacesReposterHandle() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_WITH_REPOST_REASON,
        )
        val entry = response.feed.single()

        val reason = assertNotNull(entry.reason)
        val repost = assertIs<ReasonRepost>(reason)
        assertEquals("alice.bsky.social", repost.by.handle.raw)
        // The underlying post and its extractors behave exactly like a plain post.
        assertNull(extractFirstImageThumb(entry.post))
        assertNull(extractQuotedRecord(entry.post))
    }

    @Test
    fun quotePostExtractsEmbeddedRecord() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_WITH_QUOTE_POST,
        )
        val post = response.feed.single().post

        val embed = assertNotNull(post.embed)
        assertIs<RecordView>(embed)

        val quoted = assertNotNull(extractQuotedRecord(post))
        assertEquals("dan.bsky.social", quoted.author.handle.raw)
        assertEquals("original post", quoted.value.decodeRecord<Post>().text)
        assertNull(extractQuotedPlaceholder(post))
        // Quote-only embeds carry no outer media.
        assertNull(extractFirstImageThumb(post))
    }

    @Test
    fun quotePostWithMediaExtractsBothQuoteAndThumb() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_WITH_QUOTE_AND_MEDIA,
        )
        val post = response.feed.single().post

        val embed = assertNotNull(post.embed)
        assertIs<RecordWithMediaView>(embed)

        val quoted = assertNotNull(extractQuotedRecord(post))
        assertEquals("eve.bsky.social", quoted.author.handle.raw)
        assertEquals("quoted original", quoted.value.decodeRecord<Post>().text)
        assertEquals("https://cdn.bsky.app/img/outer.jpg", extractFirstImageThumb(post))
    }

    @Test
    fun blockedQuotedRecordYieldsPlaceholder() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_WITH_BLOCKED_QUOTE,
        )
        val post = response.feed.single().post

        assertNull(extractQuotedRecord(post))
        assertEquals("Quoted post from a blocked account", extractQuotedPlaceholder(post))
    }

    @Test
    fun notFoundQuotedRecordYieldsPlaceholder() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_WITH_NOTFOUND_QUOTE,
        )
        val post = response.feed.single().post

        assertNull(extractQuotedRecord(post))
        assertEquals("Quoted post not found", extractQuotedPlaceholder(post))
    }

    @Test
    fun detachedQuotedRecordYieldsPlaceholder() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_WITH_DETACHED_QUOTE,
        )
        val post = response.feed.single().post

        assertNull(extractQuotedRecord(post))
        assertEquals("Quoted post was detached by its author", extractQuotedPlaceholder(post))
    }

    @Test
    fun unknownQuotedRecordArmYieldsPlaceholder() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_WITH_UNKNOWN_QUOTE,
        )
        val post = response.feed.single().post

        assertNull(extractQuotedRecord(post))
        assertEquals("Quoted post unavailable", extractQuotedPlaceholder(post))
    }

    @Test
    fun replyWithKnownParentYieldsPostInfo() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_REPLY_WITH_KNOWN_PARENT,
        )
        val entry = response.feed.single()

        val info = assertNotNull(extractReplyParent(entry))
        val postInfo = assertIs<ReplyParentInfo.Post>(info)
        assertEquals("alice.bsky.social", postInfo.view.author.handle.raw)
        assertEquals("at://did:plc:alice/app.bsky.feed.post/root", postInfo.view.uri.raw)
        assertEquals("original post", postInfo.view.record.decodeRecord<Post>().text)
    }

    @Test
    fun replyWithNotFoundParentYieldsUnavailable() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_REPLY_WITH_NOT_FOUND_PARENT,
        )
        val entry = response.feed.single()

        val info = assertNotNull(extractReplyParent(entry))
        val unavailable = assertIs<ReplyParentInfo.Unavailable>(info)
        assertEquals("Replying to [deleted post]", unavailable.message)
    }

    @Test
    fun replyWithBlockedParentYieldsUnavailable() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_REPLY_WITH_BLOCKED_PARENT,
        )
        val entry = response.feed.single()

        val info = assertNotNull(extractReplyParent(entry))
        val unavailable = assertIs<ReplyParentInfo.Unavailable>(info)
        assertEquals("Replying to [blocked account]", unavailable.message)
    }

    @Test
    fun replyWithUnknownParentArmYieldsUnavailable() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_REPLY_WITH_UNKNOWN_PARENT,
        )
        val entry = response.feed.single()

        val info = assertNotNull(extractReplyParent(entry))
        val unavailable = assertIs<ReplyParentInfo.Unavailable>(info)
        assertEquals("Replying to [unavailable]", unavailable.message)
    }

    @Test
    fun topLevelPostHasNoReplyParent() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_WITH_NO_EMBED,
        )
        // TIMELINE_WITH_NO_EMBED happens to also have no `reply` field, making
        // it a clean regression fixture for the top-level case.
        val entry = response.feed.single()

        assertNull(extractReplyParent(entry))
    }

    @Test
    fun repostOfReplyExposesBothReasonAndParent() = runTest {
        val response = json.decodeFromString(
            GetTimelineResponse.serializer(),
            TIMELINE_REPOST_OF_REPLY,
        )
        val entry = response.feed.single()

        // Both signals are extractable — renderer stacks them as repost
        // header → parent context → reply body.
        val reason = assertNotNull(entry.reason)
        val repost = assertIs<ReasonRepost>(reason)
        assertEquals("bob.bsky.social", repost.by.handle.raw)

        val parent = assertNotNull(extractReplyParent(entry))
        val post = assertIs<ReplyParentInfo.Post>(parent)
        assertEquals("alice.bsky.social", post.view.author.handle.raw)
        // Main post is the reply itself, authored by a third party.
        assertTrue(entry.post.author.handle.raw == "carol.bsky.social")
    }

    private companion object {
        // Minimal GetTimelineResponse with one post carrying an images embed.
        // Only fields required by the generated models are populated.
        const val TIMELINE_WITH_IMAGES_EMBED = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:fake/app.bsky.feed.post/abc",
                    "cid": "bafyfake",
                    "author": {
                      "did": "did:plc:fake",
                      "handle": "alice.bsky.social"
                    },
                    "record": {
                      "text": "hello world",
                      "createdAt": "2025-01-01T00:00:00Z"
                    },
                    "embed": {
                      "${'$'}type": "app.bsky.embed.images#view",
                      "images": [
                        {
                          "thumb": "https://cdn.bsky.app/img/thumb.jpg",
                          "fullsize": "https://cdn.bsky.app/img/full.jpg",
                          "alt": "a picture"
                        }
                      ]
                    },
                    "indexedAt": "2025-01-01T00:00:00Z"
                  }
                }
              ]
            }
        """

        // Embed variant the sample's model set doesn't know about. The
        // generated PostViewEmbedUnionSerializer must fall through to the
        // Unknown arm and preserve the raw JSON.
        const val TIMELINE_WITH_UNKNOWN_EMBED = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:fake/app.bsky.feed.post/xyz",
                    "cid": "bafyfake2",
                    "author": {
                      "did": "did:plc:fake",
                      "handle": "bob.bsky.social"
                    },
                    "record": {
                      "text": "the future",
                      "createdAt": "2025-01-02T12:00:00Z"
                    },
                    "embed": {
                      "${'$'}type": "app.bsky.embed.futureVariant#view",
                      "custom": "data"
                    },
                    "indexedAt": "2025-01-02T12:00:00Z"
                  }
                }
              ]
            }
        """

        const val TIMELINE_WITH_NO_EMBED = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:fake/app.bsky.feed.post/plain",
                    "cid": "bafyfake3",
                    "author": {
                      "did": "did:plc:fake",
                      "handle": "carol.bsky.social"
                    },
                    "record": {
                      "text": "plain text post",
                      "createdAt": "2025-01-03T09:30:00Z"
                    },
                    "indexedAt": "2025-01-03T09:30:00Z"
                  }
                }
              ]
            }
        """

        const val TIMELINE_WITH_REPOST_REASON = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:original/app.bsky.feed.post/reposted",
                    "cid": "bafyorig",
                    "author": {
                      "did": "did:plc:original",
                      "handle": "dan.bsky.social"
                    },
                    "record": {
                      "text": "original content",
                      "createdAt": "2025-02-01T00:00:00Z"
                    },
                    "indexedAt": "2025-02-01T00:00:00Z"
                  },
                  "reason": {
                    "${'$'}type": "app.bsky.feed.defs#reasonRepost",
                    "by": {
                      "did": "did:plc:reposter",
                      "handle": "alice.bsky.social"
                    },
                    "indexedAt": "2025-02-02T00:00:00Z"
                  }
                }
              ]
            }
        """

        const val TIMELINE_WITH_QUOTE_POST = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:quoter/app.bsky.feed.post/q1",
                    "cid": "bafyquote",
                    "author": {
                      "did": "did:plc:quoter",
                      "handle": "bob.bsky.social"
                    },
                    "record": {
                      "text": "check this out",
                      "createdAt": "2025-03-01T00:00:00Z"
                    },
                    "embed": {
                      "${'$'}type": "app.bsky.embed.record#view",
                      "record": {
                        "${'$'}type": "app.bsky.embed.record#viewRecord",
                        "uri": "at://did:plc:original/app.bsky.feed.post/orig",
                        "cid": "bafyorig",
                        "author": {
                          "did": "did:plc:original",
                          "handle": "dan.bsky.social"
                        },
                        "value": {
                          "${'$'}type": "app.bsky.feed.post",
                          "text": "original post",
                          "createdAt": "2025-02-28T00:00:00Z"
                        },
                        "indexedAt": "2025-02-28T00:00:00Z"
                      }
                    },
                    "indexedAt": "2025-03-01T00:00:00Z"
                  }
                }
              ]
            }
        """

        const val TIMELINE_WITH_QUOTE_AND_MEDIA = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:quoter/app.bsky.feed.post/q2",
                    "cid": "bafyquote2",
                    "author": {
                      "did": "did:plc:quoter",
                      "handle": "bob.bsky.social"
                    },
                    "record": {
                      "text": "quote + image",
                      "createdAt": "2025-03-02T00:00:00Z"
                    },
                    "embed": {
                      "${'$'}type": "app.bsky.embed.recordWithMedia#view",
                      "record": {
                        "${'$'}type": "app.bsky.embed.record#view",
                        "record": {
                          "${'$'}type": "app.bsky.embed.record#viewRecord",
                          "uri": "at://did:plc:original/app.bsky.feed.post/orig2",
                          "cid": "bafyorig2",
                          "author": {
                            "did": "did:plc:original",
                            "handle": "eve.bsky.social"
                          },
                          "value": {
                            "${'$'}type": "app.bsky.feed.post",
                            "text": "quoted original",
                            "createdAt": "2025-03-01T12:00:00Z"
                          },
                          "indexedAt": "2025-03-01T12:00:00Z"
                        }
                      },
                      "media": {
                        "${'$'}type": "app.bsky.embed.images#view",
                        "images": [
                          {
                            "thumb": "https://cdn.bsky.app/img/outer.jpg",
                            "fullsize": "https://cdn.bsky.app/img/outer-full.jpg",
                            "alt": "outer image"
                          }
                        ]
                      }
                    },
                    "indexedAt": "2025-03-02T00:00:00Z"
                  }
                }
              ]
            }
        """

        const val TIMELINE_WITH_BLOCKED_QUOTE = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:quoter/app.bsky.feed.post/blocked",
                    "cid": "bafyblocked",
                    "author": {
                      "did": "did:plc:quoter",
                      "handle": "bob.bsky.social"
                    },
                    "record": {
                      "text": "",
                      "createdAt": "2025-04-01T00:00:00Z"
                    },
                    "embed": {
                      "${'$'}type": "app.bsky.embed.record#view",
                      "record": {
                        "${'$'}type": "app.bsky.embed.record#viewBlocked",
                        "uri": "at://did:plc:blocked/app.bsky.feed.post/x",
                        "blocked": true,
                        "author": {
                          "did": "did:plc:blocked"
                        }
                      }
                    },
                    "indexedAt": "2025-04-01T00:00:00Z"
                  }
                }
              ]
            }
        """

        const val TIMELINE_WITH_NOTFOUND_QUOTE = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:quoter/app.bsky.feed.post/notfound",
                    "cid": "bafynotfound",
                    "author": {
                      "did": "did:plc:quoter",
                      "handle": "bob.bsky.social"
                    },
                    "record": {
                      "text": "",
                      "createdAt": "2025-04-02T00:00:00Z"
                    },
                    "embed": {
                      "${'$'}type": "app.bsky.embed.record#view",
                      "record": {
                        "${'$'}type": "app.bsky.embed.record#viewNotFound",
                        "uri": "at://did:plc:gone/app.bsky.feed.post/y",
                        "notFound": true
                      }
                    },
                    "indexedAt": "2025-04-02T00:00:00Z"
                  }
                }
              ]
            }
        """

        const val TIMELINE_WITH_DETACHED_QUOTE = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:quoter/app.bsky.feed.post/detached",
                    "cid": "bafydetached",
                    "author": {
                      "did": "did:plc:quoter",
                      "handle": "bob.bsky.social"
                    },
                    "record": {
                      "text": "",
                      "createdAt": "2025-04-03T00:00:00Z"
                    },
                    "embed": {
                      "${'$'}type": "app.bsky.embed.record#view",
                      "record": {
                        "${'$'}type": "app.bsky.embed.record#viewDetached",
                        "uri": "at://did:plc:author/app.bsky.feed.post/z",
                        "detached": true
                      }
                    },
                    "indexedAt": "2025-04-03T00:00:00Z"
                  }
                }
              ]
            }
        """

        const val TIMELINE_WITH_UNKNOWN_QUOTE = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:quoter/app.bsky.feed.post/unkq",
                    "cid": "bafyunkq",
                    "author": {
                      "did": "did:plc:quoter",
                      "handle": "bob.bsky.social"
                    },
                    "record": {
                      "text": "",
                      "createdAt": "2025-04-04T00:00:00Z"
                    },
                    "embed": {
                      "${'$'}type": "app.bsky.embed.record#view",
                      "record": {
                        "${'$'}type": "app.bsky.embed.record#viewFromFuture",
                        "custom": "data"
                      }
                    },
                    "indexedAt": "2025-04-04T00:00:00Z"
                  }
                }
              ]
            }
        """

        // Reply entry: `reply.parent` is a known PostView. ExtractReplyParent
        // must return ReplyParentInfo.Post with alice's handle and URI.
        const val TIMELINE_REPLY_WITH_KNOWN_PARENT = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:carol/app.bsky.feed.post/reply1",
                    "cid": "bafycarol",
                    "author": {
                      "did": "did:plc:carol",
                      "handle": "carol.bsky.social"
                    },
                    "record": {
                      "text": "good take!",
                      "createdAt": "2025-05-02T00:00:00Z"
                    },
                    "indexedAt": "2025-05-02T00:00:00Z"
                  },
                  "reply": {
                    "parent": {
                      "${'$'}type": "app.bsky.feed.defs#postView",
                      "uri": "at://did:plc:alice/app.bsky.feed.post/root",
                      "cid": "bafyalice",
                      "author": {
                        "did": "did:plc:alice",
                        "handle": "alice.bsky.social"
                      },
                      "record": {
                        "text": "original post",
                        "createdAt": "2025-05-01T00:00:00Z"
                      },
                      "indexedAt": "2025-05-01T00:00:00Z"
                    },
                    "root": {
                      "${'$'}type": "app.bsky.feed.defs#postView",
                      "uri": "at://did:plc:alice/app.bsky.feed.post/root",
                      "cid": "bafyalice",
                      "author": {
                        "did": "did:plc:alice",
                        "handle": "alice.bsky.social"
                      },
                      "record": {
                        "text": "original post",
                        "createdAt": "2025-05-01T00:00:00Z"
                      },
                      "indexedAt": "2025-05-01T00:00:00Z"
                    }
                  }
                }
              ]
            }
        """

        const val TIMELINE_REPLY_WITH_NOT_FOUND_PARENT = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:carol/app.bsky.feed.post/reply2",
                    "cid": "bafycarol2",
                    "author": {
                      "did": "did:plc:carol",
                      "handle": "carol.bsky.social"
                    },
                    "record": {
                      "text": "responding to something that's gone",
                      "createdAt": "2025-05-03T00:00:00Z"
                    },
                    "indexedAt": "2025-05-03T00:00:00Z"
                  },
                  "reply": {
                    "parent": {
                      "${'$'}type": "app.bsky.feed.defs#notFoundPost",
                      "uri": "at://did:plc:gone/app.bsky.feed.post/deleted",
                      "notFound": true
                    },
                    "root": {
                      "${'$'}type": "app.bsky.feed.defs#notFoundPost",
                      "uri": "at://did:plc:gone/app.bsky.feed.post/deleted",
                      "notFound": true
                    }
                  }
                }
              ]
            }
        """

        const val TIMELINE_REPLY_WITH_BLOCKED_PARENT = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:carol/app.bsky.feed.post/reply3",
                    "cid": "bafycarol3",
                    "author": {
                      "did": "did:plc:carol",
                      "handle": "carol.bsky.social"
                    },
                    "record": {
                      "text": "reply to blocked",
                      "createdAt": "2025-05-04T00:00:00Z"
                    },
                    "indexedAt": "2025-05-04T00:00:00Z"
                  },
                  "reply": {
                    "parent": {
                      "${'$'}type": "app.bsky.feed.defs#blockedPost",
                      "uri": "at://did:plc:blocked/app.bsky.feed.post/x",
                      "blocked": true,
                      "author": {
                        "did": "did:plc:blocked"
                      }
                    },
                    "root": {
                      "${'$'}type": "app.bsky.feed.defs#blockedPost",
                      "uri": "at://did:plc:blocked/app.bsky.feed.post/x",
                      "blocked": true,
                      "author": {
                        "did": "did:plc:blocked"
                      }
                    }
                  }
                }
              ]
            }
        """

        // Parent arm carries a $type the model set doesn't know — the
        // ReplyRefParentUnion's Unknown fallback kicks in.
        const val TIMELINE_REPLY_WITH_UNKNOWN_PARENT = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:carol/app.bsky.feed.post/reply4",
                    "cid": "bafycarol4",
                    "author": {
                      "did": "did:plc:carol",
                      "handle": "carol.bsky.social"
                    },
                    "record": {
                      "text": "reply to something new",
                      "createdAt": "2025-05-05T00:00:00Z"
                    },
                    "indexedAt": "2025-05-05T00:00:00Z"
                  },
                  "reply": {
                    "parent": {
                      "${'$'}type": "app.bsky.feed.defs#futureReplyParent",
                      "custom": "data"
                    },
                    "root": {
                      "${'$'}type": "app.bsky.feed.defs#futureReplyParent",
                      "custom": "data"
                    }
                  }
                }
              ]
            }
        """

        // Someone reposted a reply: entry carries both ReasonRepost (by bob)
        // and a reply ref whose parent is alice's original post. The main
        // post itself is carol's reply.
        const val TIMELINE_REPOST_OF_REPLY = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:carol/app.bsky.feed.post/reply5",
                    "cid": "bafycarol5",
                    "author": {
                      "did": "did:plc:carol",
                      "handle": "carol.bsky.social"
                    },
                    "record": {
                      "text": "carol's reply",
                      "createdAt": "2025-05-06T00:00:00Z"
                    },
                    "indexedAt": "2025-05-06T00:00:00Z"
                  },
                  "reply": {
                    "parent": {
                      "${'$'}type": "app.bsky.feed.defs#postView",
                      "uri": "at://did:plc:alice/app.bsky.feed.post/orig",
                      "cid": "bafyalice2",
                      "author": {
                        "did": "did:plc:alice",
                        "handle": "alice.bsky.social"
                      },
                      "record": {
                        "text": "alice's original",
                        "createdAt": "2025-05-05T00:00:00Z"
                      },
                      "indexedAt": "2025-05-05T00:00:00Z"
                    },
                    "root": {
                      "${'$'}type": "app.bsky.feed.defs#postView",
                      "uri": "at://did:plc:alice/app.bsky.feed.post/orig",
                      "cid": "bafyalice2",
                      "author": {
                        "did": "did:plc:alice",
                        "handle": "alice.bsky.social"
                      },
                      "record": {
                        "text": "alice's original",
                        "createdAt": "2025-05-05T00:00:00Z"
                      },
                      "indexedAt": "2025-05-05T00:00:00Z"
                    }
                  },
                  "reason": {
                    "${'$'}type": "app.bsky.feed.defs#reasonRepost",
                    "by": {
                      "did": "did:plc:bob",
                      "handle": "bob.bsky.social"
                    },
                    "indexedAt": "2025-05-07T00:00:00Z"
                  }
                }
              ]
            }
        """
    }
}
