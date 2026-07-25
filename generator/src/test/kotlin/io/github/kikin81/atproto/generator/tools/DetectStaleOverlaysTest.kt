package io.github.kikin81.atproto.generator.tools

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetectStaleOverlaysTest {

    private val fixedNow: OffsetDateTime = OffsetDateTime.parse("2026-06-20T12:00:00Z")

    @Test
    fun `parseOverlayManifest reads nsids and removeWhenPublished`() {
        val payload = """
            {
              "version": 1,
              "overlays": [
                {
                  "nsid": "chat.bsky.convo.getConvoMembers",
                  "commit": "abc123",
                  "removeWhenPublished": true
                }
              ]
            }
        """.trimIndent()

        val manifest = parseOverlayManifest(payload)

        assertEquals(1, manifest.overlays.size)
        assertEquals("chat.bsky.convo.getConvoMembers", manifest.overlays[0].nsid)
        assertTrue(manifest.overlays[0].removeWhenPublished)
    }

    @Test
    fun `parseNsidBoolMap returns empty for null or blank`() {
        assertTrue(parseNsidBoolMap(null).isEmpty())
        assertTrue(parseNsidBoolMap("").isEmpty())
        assertTrue(parseNsidBoolMap("   ").isEmpty())
    }

    @Test
    fun `nsidToPath converts dotted nsid to a slash path`() {
        assertEquals(
            "chat/bsky/convo/getConvoMembers",
            nsidToPath("chat.bsky.convo.getConvoMembers"),
        )
    }

    @Test
    fun `computeDrift treats key-reordered equivalent JSON as in-sync`() {
        val vendored = """{"id":"x","lexicon":1,"defs":{"main":{"type":"query"}}}"""
        val upstream = """{"lexicon":1,"defs":{"main":{"type":"query"}},"id":"x"}"""

        assertEquals(DriftStatus.InSync, computeDrift(vendored, upstream))
    }

    @Test
    fun `computeDrift ignores insignificant whitespace`() {
        val vendored = """{"id":"x","lexicon":1}"""
        val upstream = "{\n  \"id\": \"x\",\n  \"lexicon\": 1\n}\n"

        assertEquals(DriftStatus.InSync, computeDrift(vendored, upstream))
    }

    @Test
    fun `computeDrift flags a semantic difference`() {
        val vendored = """{"id":"x","lexicon":1}"""
        val upstream = """{"id":"x","lexicon":2}"""

        assertEquals(DriftStatus.Drifted, computeDrift(vendored, upstream))
    }

    @Test
    fun `computeDrift preserves array order as significant`() {
        val vendored = """{"errors":[{"name":"A"},{"name":"B"}]}"""
        val upstream = """{"errors":[{"name":"B"},{"name":"A"}]}"""

        assertEquals(DriftStatus.Drifted, computeDrift(vendored, upstream))
    }

    @Test
    fun `computeDrift reports UpstreamRemoved when upstream is null`() {
        assertEquals(DriftStatus.UpstreamRemoved, computeDrift("""{"id":"x"}""", null))
    }

    @Test
    fun `OverlayStatus isStale is false only when not publishable and in sync`() {
        assertFalse(OverlayStatus("a.b.c", publishable = false, DriftStatus.InSync).isStale)
        assertTrue(OverlayStatus("a.b.c", publishable = true, DriftStatus.InSync).isStale)
        assertTrue(OverlayStatus("a.b.c", publishable = false, DriftStatus.Drifted).isStale)
        assertTrue(
            OverlayStatus("a.b.c", publishable = false, DriftStatus.UpstreamRemoved).isStale,
        )
    }

    @Test
    fun `pinned overlay (removeWhenPublished=false) is not retired when publishable`() {
        // Publishable but intentionally pinned → NOT stale (no retire signal).
        assertFalse(
            OverlayStatus("a.b.c", publishable = true, DriftStatus.InSync, removeWhenPublished = false).isStale,
        )
        // A pinned overlay still surfaces drift (re-vendor), just not retirement.
        assertTrue(
            OverlayStatus("a.b.c", publishable = true, DriftStatus.Drifted, removeWhenPublished = false).isStale,
        )
    }

    @Test
    fun `renderReport does not emit RETIRE for a pinned publishable overlay`() {
        val statuses = listOf(
            OverlayStatus(
                "chat.bsky.convo.getConvoMembers",
                publishable = true,
                DriftStatus.InSync,
                removeWhenPublished = false,
            ),
        )

        val body = renderReport(statuses, fixedNow)

        assertContains(body, "## Overlays needing attention (0)")
        assertFalse(body.contains("**RETIRE:**"))
        assertContains(body, "publishable (pinned: removeWhenPublished=false)")
    }

    @Test
    fun `renderReport produces the clean body when nothing is stale`() {
        val statuses = listOf(
            OverlayStatus(
                "chat.bsky.convo.getConvoMembers",
                publishable = false,
                DriftStatus.InSync,
            ),
        )

        val body = renderReport(statuses, fixedNow)

        assertContains(body, "_Generated 2026-06-20 12:00 UTC")
        assertContains(body, "## Overlays needing attention (0)")
        assertContains(body, "_(none — every overlay is still required and in sync with upstream.)_")
        assertContains(body, "## All overlays (1)")
        assertContains(body, "- `chat.bsky.convo.getConvoMembers` — not-yet-publishable, in-sync")
    }

    @Test
    fun `renderReport emits a RETIRE line for a publishable overlay`() {
        val statuses = listOf(
            OverlayStatus(
                "chat.bsky.convo.getConvoMembers",
                publishable = true,
                DriftStatus.InSync,
            ),
        )

        val body = renderReport(statuses, fixedNow)

        assertContains(body, "## Overlays needing attention (1)")
        assertContains(body, "✅ `chat.bsky.convo.getConvoMembers` — now resolvable on-network")
        assertContains(body, "**RETIRE:**")
        assertContains(
            body,
            "rm generator/overlay-lexicons/chat/bsky/convo/getConvoMembers.json",
        )
    }

    @Test
    fun `renderReport emits a RE-VENDOR line for a drifted overlay`() {
        val statuses = listOf(
            OverlayStatus("app.bsky.feed.fancy", publishable = false, DriftStatus.Drifted),
        )

        val body = renderReport(statuses, fixedNow)

        assertContains(body, "⚠️ `app.bsky.feed.fancy` — overlay drifted")
        assertContains(body, "**RE-VENDOR:**")
        assertContains(body, "generator/overlay-lexicons/app/bsky/feed/fancy.json")
    }

    @Test
    fun `renderReport emits a triage line for an upstream-removed overlay`() {
        val statuses = listOf(
            OverlayStatus("app.bsky.feed.gone", publishable = false, DriftStatus.UpstreamRemoved),
        )

        val body = renderReport(statuses, fixedNow)

        assertContains(body, "⚠️ `app.bsky.feed.gone` — upstream removed")
        assertContains(body, "**TRIAGE:**")
    }

    @Test
    fun `redundant overlay is stale even when pinned`() {
        val status = OverlayStatus(
            nsid = "app.bsky.feed.post",
            publishable = true,
            drift = DriftStatus.InSync,
            removeWhenPublished = false,
            redundant = true,
        )

        assertTrue(status.isStale)
    }

    @Test
    fun `renderReport emits a REDUNDANT line for a pinned redundant overlay`() {
        val body = renderReport(
            listOf(
                OverlayStatus(
                    nsid = "app.bsky.feed.post",
                    publishable = true,
                    drift = DriftStatus.InSync,
                    removeWhenPublished = false,
                    redundant = true,
                ),
            ),
            fixedNow,
        )

        assertContains(body, "## Overlays needing attention (1)")
        assertContains(body, "♻️ `app.bsky.feed.post`")
        assertContains(body, "byte-identical to the on-network document")
        assertContains(body, "rm generator/overlay-lexicons/app/bsky/feed/post.json")
        assertContains(body, "- `app.bsky.feed.post` — publishable (pinned: removeWhenPublished=false), in-sync, REDUNDANT")
    }

    @Test
    fun `non-redundant pinned in-sync overlay stays quiet`() {
        val status = OverlayStatus(
            nsid = "chat.bsky.group.defs",
            publishable = true,
            drift = DriftStatus.InSync,
            removeWhenPublished = false,
            redundant = false,
        )

        assertFalse(status.isStale)
    }

    @Test
    fun `parseNsidBoolMap parses a nsid-to-bool map`() {
        val map = parseNsidBoolMap("""{"a.b.c": true, "d.e.f": false}""")

        assertEquals(true, map["a.b.c"])
        assertEquals(false, map["d.e.f"])
    }

    @Test
    fun `parseOverlayManifest reads expectDrift and driftReason`() {
        val payload = """
            {
              "version": 1,
              "overlays": [
                {
                  "nsid": "chat.bsky.group.defs",
                  "removeWhenPublished": false,
                  "expectDrift": true,
                  "driftReason": "Superset: re-adds groupPublicView."
                }
              ]
            }
        """.trimIndent()

        val manifest = parseOverlayManifest(payload)

        assertTrue(manifest.overlays[0].expectDrift)
        assertEquals("Superset: re-adds groupPublicView.", manifest.overlays[0].driftReason)
    }

    @Test
    fun `parseOverlayManifest defaults expectDrift to false`() {
        val payload = """
            {"version": 1, "overlays": [{"nsid": "a.b.c"}]}
        """.trimIndent()

        assertFalse(parseOverlayManifest(payload).overlays[0].expectDrift)
        assertEquals("", parseOverlayManifest(payload).overlays[0].driftReason)
    }

    @Test
    fun `expectDrift suppresses drift staleness`() {
        val status = OverlayStatus(
            nsid = "chat.bsky.group.defs",
            publishable = true,
            drift = DriftStatus.Drifted,
            removeWhenPublished = false,
            expectDrift = true,
            driftReason = "Superset: re-adds groupPublicView.",
        )

        assertFalse(status.isStale)
    }

    @Test
    fun `expectDrift becomes stale when upstream catches up`() {
        val status = OverlayStatus(
            nsid = "chat.bsky.group.defs",
            publishable = true,
            drift = DriftStatus.InSync,
            removeWhenPublished = false,
            expectDrift = true,
            driftReason = "Superset: re-adds groupPublicView.",
        )

        assertTrue(status.isStale)
    }

    @Test
    fun `expectDrift still triages an upstream removal`() {
        val status = OverlayStatus(
            nsid = "chat.bsky.group.defs",
            publishable = true,
            drift = DriftStatus.UpstreamRemoved,
            removeWhenPublished = false,
            expectDrift = true,
        )

        assertTrue(status.isStale)
    }

    @Test
    fun `renderReport labels an expected-drift superset instead of flagging it`() {
        val body = renderReport(
            listOf(
                OverlayStatus(
                    nsid = "chat.bsky.group.defs",
                    publishable = true,
                    drift = DriftStatus.Drifted,
                    removeWhenPublished = false,
                    expectDrift = true,
                    driftReason = "Superset: re-adds groupPublicView.",
                ),
            ),
            fixedNow,
        )

        assertContains(body, "## Overlays needing attention (0)")
        assertContains(body, "SUPERSET (expected drift)")
        assertContains(body, "Superset: re-adds groupPublicView.")
    }

    @Test
    fun `renderReport tells the maintainer to drop a no-longer-needed expectDrift`() {
        val body = renderReport(
            listOf(
                OverlayStatus(
                    nsid = "chat.bsky.group.defs",
                    publishable = true,
                    drift = DriftStatus.InSync,
                    removeWhenPublished = false,
                    expectDrift = true,
                    driftReason = "Superset: re-adds groupPublicView.",
                ),
            ),
            fixedNow,
        )

        assertContains(body, "## Overlays needing attention (1)")
        assertContains(body, "🔄 `chat.bsky.group.defs`")
        assertContains(body, "drop `expectDrift`")
    }
}
