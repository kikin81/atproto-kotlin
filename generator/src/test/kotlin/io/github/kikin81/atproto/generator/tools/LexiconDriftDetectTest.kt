package io.github.kikin81.atproto.generator.tools

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LexiconDriftDetectTest {

    private val fixedNow: OffsetDateTime = OffsetDateTime.parse("2026-05-14T12:00:00Z")

    @Test
    fun `isSubscription matches the upstream subscribe prefix on leaves`() {
        assertTrue(isSubscription("com.atproto.sync.subscribeRepos"))
        assertTrue(isSubscription("com.atproto.label.subscribeLabels"))
        assertTrue(isSubscription("chat.bsky.moderation.subscribeModEvents"))
    }

    @Test
    fun `isSubscription rejects non-subscription leaves`() {
        assertFalse(isSubscription("app.bsky.feed.getTimeline"))
        assertFalse(isSubscription("com.atproto.repo.createRecord"))
        assertFalse(isSubscription("app.bsky.actor.profile"))
    }

    @Test
    fun `bucketByNamespace groups 4-segment NSIDs by first three segments`() {
        val nsids = setOf(
            "app.bsky.feed.getTimeline",
            "app.bsky.feed.like",
            "app.bsky.actor.profile",
        )

        val buckets = bucketByNamespace(nsids)

        assertEquals(setOf("app.bsky.actor", "app.bsky.feed"), buckets.keys)
        assertEquals(
            listOf("app.bsky.feed.getTimeline", "app.bsky.feed.like"),
            buckets.getValue("app.bsky.feed").sorted(),
        )
        assertEquals(
            listOf("app.bsky.actor.profile"),
            buckets.getValue("app.bsky.actor"),
        )
    }

    @Test
    fun `bucketByNamespace groups 3-segment OAuth scope leaves at the parent level`() {
        // app.bsky.authFullApp etc. are scope token NSIDs with only 3 segments —
        // they should group at `app.bsky`, not produce a solo bucket each.
        val nsids = setOf(
            "app.bsky.authFullApp",
            "app.bsky.authCreatePosts",
            "app.bsky.authViewAll",
        )

        val buckets = bucketByNamespace(nsids)

        assertEquals(setOf("app.bsky"), buckets.keys)
        assertEquals(3, buckets.getValue("app.bsky").size)
    }

    @Test
    fun `bucketByNamespace returns a sorted map for deterministic output`() {
        val nsids = setOf(
            "com.atproto.repo.createRecord",
            "app.bsky.feed.getTimeline",
            "chat.bsky.convo.sendMessage",
        )

        val keys = bucketByNamespace(nsids).keys.toList()

        assertEquals(
            listOf("app.bsky.feed", "chat.bsky.convo", "com.atproto.repo"),
            keys,
        )
    }

    @Test
    fun `parseUpstreamNsids extracts NSIDs from the GitHub tree response`() {
        val payload = """
            {
              "tree": [
                {"path": "lexicons/app/bsky/feed/getTimeline.json", "type": "blob"},
                {"path": "lexicons/app/bsky/feed/post.json", "type": "blob"},
                {"path": "lexicons/com/atproto/sync/subscribeRepos.json", "type": "blob"}
              ],
              "truncated": false
            }
        """.trimIndent()

        val result = parseUpstreamNsids(payload)

        assertEquals(
            setOf(
                "app.bsky.feed.getTimeline",
                "app.bsky.feed.post",
                "com.atproto.sync.subscribeRepos",
            ),
            result,
        )
    }

    @Test
    fun `parseUpstreamNsids skips defs files and non-lexicon paths`() {
        val payload = """
            {
              "tree": [
                {"path": "lexicons/app/bsky/feed/getTimeline.json", "type": "blob"},
                {"path": "lexicons/app/bsky/feed/defs.json", "type": "blob"},
                {"path": "lexicons/README.md", "type": "blob"},
                {"path": "lexicons/app/bsky/feed", "type": "tree"},
                {"path": "package.json", "type": "blob"}
              ]
            }
        """.trimIndent()

        assertEquals(setOf("app.bsky.feed.getTimeline"), parseUpstreamNsids(payload))
    }

    @Test
    fun `parseManifestNsids reads the lexicons array and ignores resolutions`() {
        val payload = """
            {
              "version": 1,
              "lexicons": [
                "app.bsky.feed.getTimeline",
                "com.atproto.repo.createRecord"
              ],
              "resolutions": {
                "app.bsky.feed.getTimeline": {
                  "uri": "at://did:plc:xxx/com.atproto.lexicon.schema/app.bsky.feed.getTimeline",
                  "cid": "bafy..."
                }
              }
            }
        """.trimIndent()

        assertEquals(
            setOf("app.bsky.feed.getTimeline", "com.atproto.repo.createRecord"),
            parseManifestNsids(payload),
        )
    }

    @Test
    fun `renderReport produces the no-drift body when both sets are empty`() {
        val body = renderReport(emptySet(), emptySet(), fixedNow)

        assertContains(body, "_Generated 2026-05-14 12:00 UTC")
        assertContains(body, "## New upstream NSIDs (0)")
        assertContains(body, "_(none — manifest is current with upstream.)_")
        assertContains(body, "## Manifest NSIDs missing from upstream (0)")
        assertContains(body, "_(none — every manifest NSID exists upstream.)_")
    }

    @Test
    fun `renderReport separates subscription NSIDs from regular NSIDs`() {
        val newNsids = setOf(
            "com.atproto.sync.subscribeRepos",
            "chat.bsky.moderation.subscribeModEvents",
            "app.bsky.bookmark.createBookmark",
            "app.bsky.bookmark.deleteBookmark",
        )

        val body = renderReport(newNsids, emptySet(), fixedNow)

        assertContains(body, "### Subscription type (2) — architectural gap")
        assertContains(body, "- `com.atproto.sync.subscribeRepos`")
        assertContains(body, "- `chat.bsky.moderation.subscribeModEvents`")
        assertContains(body, "### `app.bsky.bookmark.*` (2)")
        assertContains(body, "- `app.bsky.bookmark.createBookmark`")
    }

    @Test
    fun `renderReport renders removed NSIDs section with sorted entries`() {
        val removed = setOf(
            "com.atproto.legacy.deprecated",
            "app.bsky.old.thing",
        )

        val body = renderReport(emptySet(), removed, fixedNow)

        assertContains(body, "## Manifest NSIDs missing from upstream (2)")
        val appIdx = body.indexOf("app.bsky.old.thing")
        val comIdx = body.indexOf("com.atproto.legacy.deprecated")
        assertTrue(appIdx in 0 until comIdx, "removed entries should be sorted")
    }
}
