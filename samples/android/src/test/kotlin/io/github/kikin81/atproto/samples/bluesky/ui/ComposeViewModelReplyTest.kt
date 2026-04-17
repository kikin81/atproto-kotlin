package io.github.kikin81.atproto.samples.bluesky.ui

import io.github.kikin81.atproto.app.bsky.feed.PostView
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers [ComposeViewModel.buildReplyRef] — the thread-root resolution rule
 * the spec mandates: when the reply target is itself a reply, the new
 * reply's `root` inherits the thread's originating root; otherwise the
 * target IS the root.
 *
 * Runs against PostView fixtures decoded from JSON so we exercise the
 * actual record decode path (not hand-constructed models that would
 * skip over the `record: JsonObject` plumbing).
 */
class ComposeViewModelReplyTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun replyToTopLevelPostSetsRootEqualToParent() {
        val target = json.decodeFromString(PostView.serializer(), TOP_LEVEL_POST)

        val ref = ComposeViewModel.buildReplyRef(target)

        assertEquals(target.uri.raw, ref.parent.uri.raw)
        assertEquals(target.cid.raw, ref.parent.cid.raw)
        // Top-level → root is the target itself
        assertEquals(ref.parent.uri.raw, ref.root.uri.raw)
        assertEquals(ref.parent.cid.raw, ref.root.cid.raw)
    }

    @Test
    fun replyToReplyPreservesOriginalThreadRoot() {
        val target = json.decodeFromString(PostView.serializer(), NESTED_REPLY_POST)

        val ref = ComposeViewModel.buildReplyRef(target)

        // Parent is always the direct target
        assertEquals(target.uri.raw, ref.parent.uri.raw)
        assertEquals(target.cid.raw, ref.parent.cid.raw)
        // Root is inherited from target.record.reply.root, not the parent
        assertEquals("at://did:plc:originator/app.bsky.feed.post/thread-root", ref.root.uri.raw)
        assertEquals("bafyoriginator", ref.root.cid.raw)
    }

    @Test
    fun replyToTargetWithUnparseableRecordFallsBackToTargetAsRoot() {
        val target = json.decodeFromString(PostView.serializer(), POST_WITH_UNPARSEABLE_RECORD)

        // buildReplyRef wraps decode in runCatching; an unknown record shape
        // must not crash — falls back to treating target as the thread root.
        val ref = ComposeViewModel.buildReplyRef(target)

        assertEquals(ref.parent.uri.raw, ref.root.uri.raw)
    }

    private companion object {
        const val TOP_LEVEL_POST = """
            {
              "uri": "at://did:plc:alice/app.bsky.feed.post/original",
              "cid": "bafyalice",
              "author": {
                "did": "did:plc:alice",
                "handle": "alice.bsky.social"
              },
              "record": {
                "text": "hello world",
                "createdAt": "2025-01-01T00:00:00Z"
              },
              "indexedAt": "2025-01-01T00:00:00Z"
            }
        """

        const val NESTED_REPLY_POST = """
            {
              "uri": "at://did:plc:bob/app.bsky.feed.post/a-reply",
              "cid": "bafybob",
              "author": {
                "did": "did:plc:bob",
                "handle": "bob.bsky.social"
              },
              "record": {
                "text": "middle of a thread",
                "createdAt": "2025-01-02T00:00:00Z",
                "reply": {
                  "parent": {
                    "uri": "at://did:plc:alice/app.bsky.feed.post/previous",
                    "cid": "bafyprevious"
                  },
                  "root": {
                    "uri": "at://did:plc:originator/app.bsky.feed.post/thread-root",
                    "cid": "bafyoriginator"
                  }
                }
              },
              "indexedAt": "2025-01-02T00:00:00Z"
            }
        """

        // record.text is missing (required field) — decodeRecord<Post>() will
        // fail and the helper must fall back cleanly.
        const val POST_WITH_UNPARSEABLE_RECORD = """
            {
              "uri": "at://did:plc:carol/app.bsky.feed.post/weird",
              "cid": "bafycarol",
              "author": {
                "did": "did:plc:carol",
                "handle": "carol.bsky.social"
              },
              "record": {
                "createdAt": "2025-01-03T00:00:00Z"
              },
              "indexedAt": "2025-01-03T00:00:00Z"
            }
        """
    }
}
