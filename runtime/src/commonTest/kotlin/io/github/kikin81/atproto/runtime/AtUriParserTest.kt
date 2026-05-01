package io.github.kikin81.atproto.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AtUriParserTest {

    @Test
    fun parse_fullUri_returnsRepoCollectionRkey() {
        val parts = AtUri("at://did:plc:abc/app.bsky.feed.post/3lkrkey").parse()
        assertEquals(AtIdentifier("did:plc:abc"), parts.repo)
        assertEquals(Nsid("app.bsky.feed.post"), parts.collection)
        assertEquals(RecordKey("3lkrkey"), parts.rkey)
        assertNull(parts.fragment)
    }

    @Test
    fun parse_specExampleFromIssue57() {
        val parts = AtUri("at://did:plc:xxxxx/app.bsky.feed.like/3lkrkey").parse()
        assertEquals(AtIdentifier("did:plc:xxxxx"), parts.repo)
        assertEquals(Nsid("app.bsky.feed.like"), parts.collection)
        assertEquals(RecordKey("3lkrkey"), parts.rkey)
        assertNull(parts.fragment)
    }

    @Test
    fun parse_repoOnly_returnsRepoOnly() {
        val parts = AtUri("at://did:plc:abc").parse()
        assertEquals(AtIdentifier("did:plc:abc"), parts.repo)
        assertNull(parts.collection)
        assertNull(parts.rkey)
        assertNull(parts.fragment)
    }

    @Test
    fun parse_repoAndCollectionOnly_returnsNoRkey() {
        val parts = AtUri("at://did:plc:abc/app.bsky.feed.post").parse()
        assertEquals(AtIdentifier("did:plc:abc"), parts.repo)
        assertEquals(Nsid("app.bsky.feed.post"), parts.collection)
        assertNull(parts.rkey)
        assertNull(parts.fragment)
    }

    @Test
    fun parse_trailingSlashAfterRepo_normalizesToRepoOnly() {
        val parts = AtUri("at://did:plc:abc/").parse()
        assertEquals(AtIdentifier("did:plc:abc"), parts.repo)
        assertNull(parts.collection)
        assertNull(parts.rkey)
        assertNull(parts.fragment)
    }

    @Test
    fun parse_trailingSlashAfterCollection_normalizesToNoRkey() {
        val parts = AtUri("at://did:plc:abc/app.bsky.feed.post/").parse()
        assertEquals(AtIdentifier("did:plc:abc"), parts.repo)
        assertEquals(Nsid("app.bsky.feed.post"), parts.collection)
        assertNull(parts.rkey)
        assertNull(parts.fragment)
    }

    @Test
    fun parse_fragmentOnFullUri_stripsLeadingHash() {
        val parts = AtUri("at://did:plc:abc/app.bsky.feed.post/3l#main").parse()
        assertEquals(AtIdentifier("did:plc:abc"), parts.repo)
        assertEquals(Nsid("app.bsky.feed.post"), parts.collection)
        assertEquals(RecordKey("3l"), parts.rkey)
        assertEquals("main", parts.fragment)
    }

    @Test
    fun parse_fragmentWithoutRecord_isValid() {
        val parts = AtUri("at://did:plc:abc#frag").parse()
        assertEquals(AtIdentifier("did:plc:abc"), parts.repo)
        assertNull(parts.collection)
        assertNull(parts.rkey)
        assertEquals("frag", parts.fragment)
    }

    @Test
    fun parse_handleAsRepo_acceptedWithoutSemanticCheck() {
        val parts = AtUri("at://alice.bsky.social/app.bsky.feed.post/3l").parse()
        assertEquals(AtIdentifier("alice.bsky.social"), parts.repo)
        assertEquals(Nsid("app.bsky.feed.post"), parts.collection)
        assertEquals(RecordKey("3l"), parts.rkey)
    }

    @Test
    fun parse_garbageRepo_acceptedStructurally() {
        // Parser is intentionally agnostic about repo-segment semantics; that
        // belongs to Did/Handle/AtIdentifier validators in a future ticket.
        val parts = AtUri("at://garbage/coll/rkey").parse()
        assertEquals(AtIdentifier("garbage"), parts.repo)
        assertEquals(Nsid("coll"), parts.collection)
        assertEquals(RecordKey("rkey"), parts.rkey)
    }

    @Test
    fun parse_missingScheme_throws() {
        assertFailsWith<IllegalArgumentException> {
            AtUri("did:plc:abc/app.bsky.feed.post/3l").parse()
        }
    }

    @Test
    fun parse_emptyRepo_throws() {
        assertFailsWith<IllegalArgumentException> {
            AtUri("at:///app.bsky.feed.post/3l").parse()
        }
    }

    @Test
    fun parse_emptyPathSegment_throws() {
        assertFailsWith<IllegalArgumentException> {
            AtUri("at://did:plc:abc//rkey").parse()
        }
    }

    @Test
    fun parse_doubleTrailingSlash_throws() {
        assertFailsWith<IllegalArgumentException> {
            AtUri("at://did:plc:abc//").parse()
        }
    }

    @Test
    fun parse_trailingSlashAfterRkey_throws() {
        assertFailsWith<IllegalArgumentException> {
            AtUri("at://did:plc:abc/app.bsky.feed.post/3l/").parse()
        }
    }

    @Test
    fun parse_doubleTrailingSlashAfterCollection_throws() {
        assertFailsWith<IllegalArgumentException> {
            AtUri("at://did:plc:abc/app.bsky.feed.post//").parse()
        }
    }

    @Test
    fun parseOrNull_validUri_returnsParts() {
        val parts = AtUri("at://did:plc:abc/app.bsky.feed.post/3l").parseOrNull()
        assertEquals(AtIdentifier("did:plc:abc"), parts?.repo)
        assertEquals(Nsid("app.bsky.feed.post"), parts?.collection)
        assertEquals(RecordKey("3l"), parts?.rkey)
    }

    @Test
    fun parseOrNull_missingScheme_returnsNull() {
        assertNull(AtUri("did:plc:abc/app.bsky.feed.post").parseOrNull())
    }

    @Test
    fun parseOrNull_emptyRepo_returnsNull() {
        assertNull(AtUri("at:///app.bsky.feed.post").parseOrNull())
    }

    @Test
    fun parseOrNull_emptyPathSegment_returnsNull() {
        assertNull(AtUri("at://did:plc:abc//rkey").parseOrNull())
    }
}
