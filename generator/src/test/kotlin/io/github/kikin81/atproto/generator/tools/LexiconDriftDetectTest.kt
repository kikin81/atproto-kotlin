package io.github.kikin81.atproto.generator.tools

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LexiconDriftDetectTest {

    private val fixedNow: OffsetDateTime = OffsetDateTime.parse("2026-05-14T12:00:00Z")

    private companion object {
        /** Two opted-in NSIDs; resolutions additionally pins two transitive ones. */
        const val MANIFEST_WITH_TRANSITIVE = """
            {
              "version": 1,
              "lexicons": [
                "app.bsky.feed.getTimeline",
                "com.atproto.repo.createRecord"
              ],
              "resolutions": {
                "app.bsky.feed.getTimeline": {
                  "uri": "at://did:plc:xxx/com.atproto.lexicon.schema/app.bsky.feed.getTimeline",
                  "cid": "bafy1"
                },
                "com.atproto.repo.createRecord": {
                  "uri": "at://did:plc:xxx/com.atproto.lexicon.schema/com.atproto.repo.createRecord",
                  "cid": "bafy2"
                },
                "app.bsky.embed.images": {
                  "uri": "at://did:plc:xxx/com.atproto.lexicon.schema/app.bsky.embed.images",
                  "cid": "bafy3"
                },
                "app.bsky.feed.defs": {
                  "uri": "at://did:plc:xxx/com.atproto.lexicon.schema/app.bsky.feed.defs",
                  "cid": "bafy4"
                }
              }
            }
        """
    }

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
    fun `parseUpstreamNsids skips non-lexicon paths but keeps defs documents`() {
        // defs are kept here so the removed-side diff can match a manifest that
        // names one directly; the new-side strips them via optInCandidates.
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

        assertEquals(
            setOf("app.bsky.feed.getTimeline", "app.bsky.feed.defs"),
            parseUpstreamNsids(payload),
        )
    }

    @Test
    fun `optInCandidates drops defs documents`() {
        val upstream = setOf(
            "app.bsky.feed.getTimeline",
            "app.bsky.feed.defs",
            "app.bsky.embed.defs",
        )

        assertEquals(setOf("app.bsky.feed.getTimeline"), optInCandidates(upstream))
    }

    @Test
    fun `parseManifestNsids reads the lexicons array only`() {
        assertEquals(
            setOf("app.bsky.feed.getTimeline", "com.atproto.repo.createRecord"),
            parseManifestNsids(MANIFEST_WITH_TRANSITIVE),
        )
    }

    @Test
    fun `parseResolvedNsids reads every pinned NSID including transitive ones`() {
        assertEquals(
            setOf(
                "app.bsky.feed.getTimeline",
                "com.atproto.repo.createRecord",
                "app.bsky.embed.images",
                "app.bsky.feed.defs",
            ),
            parseResolvedNsids(MANIFEST_WITH_TRANSITIVE),
        )
    }

    @Test
    fun `parseResolvedNsids returns empty for a manifest with no resolutions`() {
        val payload = """{"version": 1, "lexicons": ["app.bsky.feed.post"]}"""

        assertEquals(emptySet(), parseResolvedNsids(payload))
    }

    @Test
    fun `transitively resolved NSIDs are not reported as a coverage gap`() {
        // app.bsky.embed.images is pinned in resolutions via app.bsky.feed.post
        // without being opted into — it is installed and generated, not a gap.
        val upstream = setOf(
            "app.bsky.feed.getTimeline",
            "com.atproto.repo.createRecord",
            "app.bsky.embed.images",
            "app.bsky.graph.follow",
        )
        val declared = parseManifestNsids(MANIFEST_WITH_TRANSITIVE)
        val resolved = parseResolvedNsids(MANIFEST_WITH_TRANSITIVE)

        val newNsids = optInCandidates(upstream) - declared - resolved

        assertEquals(setOf("app.bsky.graph.follow"), newNsids)
    }

    @Test
    fun `a manifest defs entry that exists upstream is not reported as removed`() {
        // The manifest may name a defs document directly. Diffing against the
        // unfiltered upstream set keeps it from reading missing forever.
        val upstream = setOf("app.bsky.feed.post", "app.bsky.video.defs")
        val declared = setOf("app.bsky.feed.post", "app.bsky.video.defs")

        assertEquals(emptySet(), declared - upstream)
    }

    @Test
    fun `a manifest NSID genuinely absent upstream is still reported as removed`() {
        val upstream = setOf("app.bsky.feed.post", "app.bsky.video.defs")
        val declared = setOf(
            "app.bsky.feed.post",
            "app.bsky.video.defs",
            "chat.bsky.group.getGroupPublicInfo",
        )

        assertEquals(setOf("chat.bsky.group.getGroupPublicInfo"), declared - upstream)
    }

    @Test
    fun `renderReport notes the transitive exclusion count`() {
        val body = renderReport(
            emptySet(),
            emptySet(),
            fixedNow,
            overlayCount = 0,
            transitiveCount = 21,
        )

        assertContains(body, "_(21 NSID(s) covered transitively via `resolutions`")
    }

    @Test
    fun `renderReport omits the transitive note when nothing is excluded`() {
        val body = renderReport(emptySet(), emptySet(), fixedNow)

        assertFalse(body.contains("covered transitively"))
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
