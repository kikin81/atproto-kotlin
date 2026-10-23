package io.github.kikin81.atproto.generator.tools

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class UpstreamSourcesTest {

    private val atproto = UPSTREAM_REPOS.first { it.slug == "bluesky-social/atproto" }
    private val bsky = UPSTREAM_REPOS.first { it.slug == "bluesky-social/bsky" }

    @Test
    fun `app and chat namespaces are served by the bsky repo`() {
        assertEquals(bsky, reposFor("app.bsky.feed.post").first())
        assertEquals(bsky, reposFor("chat.bsky.group.defs").first())
        assertEquals(bsky, reposFor("network.bsky.jetstream.getBlock").first())
    }

    @Test
    fun `com atproto and ozone namespaces are served by the atproto repo`() {
        assertEquals(atproto, reposFor("com.atproto.repo.strongRef").first())
        assertEquals(atproto, reposFor("tools.ozone.team.addMember").first())
    }

    @Test
    fun `unknown namespaces still try every repo`() {
        assertEquals(UPSTREAM_REPOS.toSet(), reposFor("blue.example.thing").toSet())
    }

    @Test
    fun `repo urls point at the lexicons directory`() {
        assertEquals(
            "https://raw.githubusercontent.com/bluesky-social/bsky/main/lexicons",
            bsky.rawBase,
        )
        assertEquals(
            "https://api.github.com/repos/bluesky-social/bsky/git/trees/main?recursive=1",
            bsky.treeUrl,
        )
    }

    @Test
    fun `union of both trees covers a manifest spanning both repos`() {
        fun tree(vararg paths: String) = parseUpstreamNsids(
            """{"tree":[${paths.joinToString(",") { """{"path":"$it","type":"blob"}""" }}]}""",
        )
        val atprotoTree = tree("lexicons/com/atproto/repo/strongRef.json")
        val bskyTree = tree("lexicons/app/bsky/feed/post.json")
        val declared = setOf("app.bsky.feed.post", "com.atproto.repo.strongRef")

        // The regression: atproto alone made every app.bsky NSID read "removed".
        assertEquals(setOf("app.bsky.feed.post"), declared - atprotoTree)
        assertEquals(emptySet(), declared - (atprotoTree + bskyTree))
    }

    @Test
    fun `drift report names every upstream repo`() {
        val body = renderReport(emptySet(), setOf("app.bsky.old.thing"), OffsetDateTime.parse("2026-10-22T12:00:00Z"))

        assertContains(body, "bluesky-social/atproto@main")
        assertContains(body, "bluesky-social/bsky@main")
    }
}
