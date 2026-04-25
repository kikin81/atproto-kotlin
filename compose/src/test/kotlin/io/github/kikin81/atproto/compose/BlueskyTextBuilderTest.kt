package io.github.kikin81.atproto.compose

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import io.github.kikin81.atproto.app.bsky.richtext.Facet
import io.github.kikin81.atproto.app.bsky.richtext.FacetByteSlice
import io.github.kikin81.atproto.app.bsky.richtext.FacetFeaturesUnion
import io.github.kikin81.atproto.app.bsky.richtext.FacetLink
import io.github.kikin81.atproto.app.bsky.richtext.FacetMention
import io.github.kikin81.atproto.app.bsky.richtext.FacetTag
import io.github.kikin81.atproto.runtime.Did
import io.github.kikin81.atproto.runtime.Uri
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlueskyTextBuilderTest {
    private data class FacetCall(
        val feature: FacetFeaturesUnion,
        val startChar: Int,
        val endChar: Int,
        val slice: String,
    )

    private fun collect(
        text: String,
        facets: List<Facet>?,
    ): Pair<AnnotatedString, List<FacetCall>> {
        val calls = mutableListOf<FacetCall>()
        val annotated = buildAnnotatedString {
            appendBlueskyText(text, facets) { feature, start, end, slice ->
                calls += FacetCall(feature, start, end, slice)
            }
        }
        return annotated to calls
    }

    private fun mention(byteStart: Long, byteEnd: Long, did: String): Facet = Facet(
        features = listOf(FacetMention(did = Did(did))),
        index = FacetByteSlice(byteEnd = byteEnd, byteStart = byteStart),
    )

    private fun link(byteStart: Long, byteEnd: Long, uri: String): Facet = Facet(
        features = listOf(FacetLink(uri = Uri(uri))),
        index = FacetByteSlice(byteEnd = byteEnd, byteStart = byteStart),
    )

    private fun tag(byteStart: Long, byteEnd: Long, value: String): Facet = Facet(
        features = listOf(FacetTag(tag = value)),
        index = FacetByteSlice(byteEnd = byteEnd, byteStart = byteStart),
    )

    @Test
    fun `ASCII text with one mention fires callback with correct char range and slice`() {
        val text = "hello @alice.bsky.social"
        val facet = mention(byteStart = 6L, byteEnd = 24L, did = "did:plc:alice")

        val (annotated, calls) = collect(text, listOf(facet))

        assertEquals(text, annotated.text)
        assertEquals(1, calls.size)
        assertEquals(6, calls[0].startChar)
        assertEquals(24, calls[0].endChar)
        assertEquals("@alice.bsky.social", calls[0].slice)
        assertTrue(calls[0].feature is FacetMention)
        assertEquals("did:plc:alice", (calls[0].feature as FacetMention).did.raw)
    }

    @Test
    fun `multiple facets fire in byteStart ascending order`() {
        val text = "hi #cool @alice.bsky.social https://example.com"
        // facets are intentionally in disorder: link at byte 28, mention
        // at byte 9, tag at byte 3.
        val facets = listOf(
            link(byteStart = 28L, byteEnd = 47L, uri = "https://example.com"),
            mention(byteStart = 9L, byteEnd = 27L, did = "did:plc:alice"),
            tag(byteStart = 3L, byteEnd = 8L, value = "cool"),
        )

        val (_, calls) = collect(text, facets)

        assertEquals(3, calls.size)
        assertTrue(calls[0].feature is FacetTag, "first call should be the tag")
        assertEquals(3, calls[0].startChar)
        assertTrue(calls[1].feature is FacetMention, "second call should be the mention")
        assertEquals(9, calls[1].startChar)
        assertTrue(calls[2].feature is FacetLink, "third call should be the link")
        assertEquals(28, calls[2].startChar)
    }

    @Test
    fun `malformed facet with inverted range is silently dropped, others still fire`() {
        val text = "hello @alice.bsky.social"
        val facets = listOf(
            // inverted: byteEnd < byteStart
            mention(byteStart = 10L, byteEnd = 5L, did = "did:plc:bad"),
            // valid
            mention(byteStart = 6L, byteEnd = 24L, did = "did:plc:alice"),
        )

        val (_, calls) = collect(text, facets)

        assertEquals(1, calls.size, "inverted facet should be dropped")
        assertEquals("did:plc:alice", (calls[0].feature as FacetMention).did.raw)
    }

    @Test
    fun `out of range byteEnd is silently dropped`() {
        val text = "hi"
        val facets = listOf(
            mention(byteStart = 0L, byteEnd = 999L, did = "did:plc:bad"),
        )

        val (_, calls) = collect(text, facets)
        assertEquals(0, calls.size)
    }

    @Test
    fun `byte offset that falls inside a codepoint is silently dropped`() {
        // "中" is 3 UTF-8 bytes. A facet whose byteStart=1 lands inside
        // the codepoint and must be dropped.
        val text = "中"
        val facets = listOf(
            mention(byteStart = 1L, byteEnd = 3L, did = "did:plc:bad"),
        )

        val (_, calls) = collect(text, facets)
        assertEquals(0, calls.size)
    }

    @Test
    fun `empty features list is silently dropped`() {
        val text = "hello"
        val facets = listOf(
            Facet(
                features = emptyList(),
                index = FacetByteSlice(byteEnd = 5L, byteStart = 0L),
            ),
        )

        val (_, calls) = collect(text, facets)
        assertEquals(0, calls.size)
    }

    @Test
    fun `null facets produces builder containing only text with no callbacks`() {
        val text = "hello world"
        val (annotated, calls) = collect(text, null)
        assertEquals(text, annotated.text)
        assertEquals(0, calls.size)
    }

    @Test
    fun `empty facets produces builder containing only text with no callbacks`() {
        val text = "hello world"
        val (annotated, calls) = collect(text, emptyList())
        assertEquals(text, annotated.text)
        assertEquals(0, calls.size)
    }

    @Test
    fun `emoji before mention does not shift the mention boundary`() {
        // Canonical regression: "hi 👋 @alice.bsky.social"
        // UTF-8 bytes:    h i SP 👋(4) SP @ a l i c e . b s k y . s o c i a l
        // byte indices:   0 1 2  3-6   7  8 9 ... ending at byte 26.
        // UTF-16 chars:   h i SP D83D DC4B SP @ a l ... ending at char 24.
        val text = "hi 👋 @alice.bsky.social"
        val facet = mention(byteStart = 8L, byteEnd = 26L, did = "did:plc:alice")

        val (_, calls) = collect(text, listOf(facet))

        assertEquals(1, calls.size)
        // After the emoji (4 bytes / 2 chars) and the space, the mention
        // starts at char index 6 (h=0, i=1, SP=2, D83D=3, DC4B=4, SP=5,
        // @=6) and runs through the end of the string at char 24.
        assertEquals(6, calls[0].startChar)
        assertEquals(24, calls[0].endChar)
        assertEquals("@alice.bsky.social", calls[0].slice)
    }

    @Test
    fun `CJK text with following mention preserves correct char range`() {
        // "こんにちは @alice.bsky.social"
        // こんにちは = 5 codepoints × 3 UTF-8 bytes = 15 bytes / 5 chars.
        // Plus a space = 16 bytes / 6 chars. Mention starts at byte 16,
        // char 6, runs 18 bytes / 18 chars to byte 34, char 24.
        val text = "こんにちは @alice.bsky.social"
        val facet = mention(byteStart = 16L, byteEnd = 34L, did = "did:plc:alice")

        val (_, calls) = collect(text, listOf(facet))

        assertEquals(1, calls.size)
        assertEquals(6, calls[0].startChar)
        assertEquals(24, calls[0].endChar)
        assertEquals("@alice.bsky.social", calls[0].slice)
    }

    @Test
    fun `combining mark before facet preserves correct char range`() {
        // Explicit decomposed form: 'e' + U+0301 (combining acute).
        // 'e' = 1 byte / 1 char.
        // U+0301 = 2 bytes / 1 char.
        // ' ' = 1 byte / 1 char.
        // '@' = 1 byte / 1 char.
        // 'x' = 1 byte / 1 char.
        // The "@x" range is bytes 4..6, chars 3..5.
        // Use explicit ́ to force decomposed form (the literal in
        // source code is otherwise normalized to precomposed U+00E9 by
        // most editors / build pipelines).
        val text = "é @x"
        val facet = mention(byteStart = 4L, byteEnd = 6L, did = "did:plc:x")

        val (_, calls) = collect(text, listOf(facet))

        assertEquals(1, calls.size)
        assertEquals(3, calls[0].startChar)
        assertEquals(5, calls[0].endChar)
        assertEquals("@x", calls[0].slice)
    }

    @Test
    fun `unknown feature variant is delivered to callback for caller to handle`() {
        // Tier 1 doesn't drop unknowns — that's a Tier 2 decision. The
        // primitive passes them through so power callers can render
        // them however they like.
        val unknown = FacetFeaturesUnion.Unknown(
            type = "app.bsky.richtext.facet#stockTicker",
            raw = JsonObject(emptyMap()),
        )
        val text = "see \$TSLA today"
        val facet = Facet(
            features = listOf(unknown),
            index = FacetByteSlice(byteEnd = 10L, byteStart = 4L),
        )

        val (_, calls) = collect(text, listOf(facet))

        assertEquals(1, calls.size)
        assertTrue(calls[0].feature is FacetFeaturesUnion.Unknown)
    }

    @Test
    fun `facet indices are offset by the builder's existing length`() {
        // Regression for the bug where appendBlueskyText returned facet
        // char indices relative to `text` rather than the builder. A
        // consumer that appends a prefix (e.g. "Replying to @parent — ")
        // before the post body needs indices that line up with the
        // builder's char space so addStyle(start, end) lands correctly.
        val prefix = "Replying to @parent — "
        val text = "@alice.bsky.social"
        val facet = mention(byteStart = 0L, byteEnd = 18L, did = "did:plc:alice")

        val calls = mutableListOf<FacetCall>()
        val annotated = buildAnnotatedString {
            append(prefix)
            appendBlueskyText(text, listOf(facet)) { feature, start, end, slice ->
                calls += FacetCall(feature, start, end, slice)
            }
        }

        assertEquals(prefix + text, annotated.text)
        assertEquals(1, calls.size)
        // Indices reference the builder's char space — start must be
        // offset by the prefix length, not 0.
        assertEquals(prefix.length, calls[0].startChar)
        assertEquals(prefix.length + text.length, calls[0].endChar)
        // Slice is still the matched substring of `text`, not of the
        // builder. (Otherwise the consumer's substring would be wrong.)
        assertEquals("@alice.bsky.social", calls[0].slice)
        // Final-form sanity check: the indices truly point at the
        // mention in the assembled annotated string.
        assertEquals(
            "@alice.bsky.social",
            annotated.text.substring(calls[0].startChar, calls[0].endChar),
        )
    }
}
