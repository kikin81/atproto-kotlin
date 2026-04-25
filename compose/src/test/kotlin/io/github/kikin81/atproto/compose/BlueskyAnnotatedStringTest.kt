package io.github.kikin81.atproto.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlueskyAnnotatedStringTest {
    private val accentStyle = SpanStyle(color = Color.Magenta)
    private val mapper: (FacetFeaturesUnion) -> SpanStyle = { accentStyle }

    @Test
    fun `mention facet attaches mention annotation tag with the DID raw string`() {
        val text = "hello @alice.bsky.social"
        val facet = Facet(
            features = listOf(FacetMention(did = Did("did:plc:alice"))),
            index = FacetByteSlice(byteEnd = 24L, byteStart = 6L),
        )

        val annotated = buildBlueskyAnnotatedString(text, listOf(facet), mapper)

        val mentions = annotated.getStringAnnotations(ANNOTATION_TAG_MENTION, 0, text.length)
        assertEquals(1, mentions.size)
        assertEquals("did:plc:alice", mentions[0].item)
        assertEquals(6, mentions[0].start)
        assertEquals(24, mentions[0].end)
    }

    @Test
    fun `link facet attaches a LinkAnnotation Url at the right range`() {
        val text = "see https://example.com"
        val facet = Facet(
            features = listOf(FacetLink(uri = Uri("https://example.com"))),
            index = FacetByteSlice(byteEnd = 23L, byteStart = 4L),
        )

        val annotated = buildBlueskyAnnotatedString(text, listOf(facet), mapper)

        // LinkAnnotation is exposed via the unified URL/string-annotations
        // API; getLinkAnnotations was added in Compose UI 1.7.
        val links = annotated.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        val item = links[0].item
        assertTrue(item is LinkAnnotation.Url, "expected LinkAnnotation.Url, was ${item::class}")
        assertEquals("https://example.com", item.url)
        assertEquals(4, links[0].start)
        assertEquals(23, links[0].end)
    }

    @Test
    fun `tag facet attaches the tag annotation with the bare tag value`() {
        val text = "love #compose"
        val facet = Facet(
            features = listOf(FacetTag(tag = "compose")),
            index = FacetByteSlice(byteEnd = 13L, byteStart = 5L),
        )

        val annotated = buildBlueskyAnnotatedString(text, listOf(facet), mapper)

        val tags = annotated.getStringAnnotations(ANNOTATION_TAG_TAG, 0, text.length)
        assertEquals(1, tags.size)
        assertEquals("compose", tags[0].item)
        assertEquals(5, tags[0].start)
        assertEquals(13, tags[0].end)
    }

    @Test
    fun `unknown feature variant is silently skipped, other facets still styled`() {
        val text = "see foo and @alice.bsky.social"
        val unknown = Facet(
            features = listOf(
                FacetFeaturesUnion.Unknown(
                    type = "app.bsky.richtext.facet#stockTicker",
                    raw = JsonObject(emptyMap()),
                ),
            ),
            index = FacetByteSlice(byteEnd = 7L, byteStart = 4L),
        )
        val mention = Facet(
            features = listOf(FacetMention(did = Did("did:plc:alice"))),
            index = FacetByteSlice(byteEnd = 30L, byteStart = 12L),
        )

        val annotated = buildBlueskyAnnotatedString(text, listOf(unknown, mention), mapper)

        val mentions = annotated.getStringAnnotations(ANNOTATION_TAG_MENTION, 0, text.length)
        assertEquals(1, mentions.size)
        assertEquals("did:plc:alice", mentions[0].item)
        // Unknown produces no string annotation under either tag.
        assertEquals(0, annotated.getStringAnnotations(ANNOTATION_TAG_TAG, 0, text.length).size)
    }

    @Test
    fun `emoji before mention places mention annotation at correct char range`() {
        val text = "hi 👋 @alice.bsky.social"
        val facet = Facet(
            features = listOf(FacetMention(did = Did("did:plc:alice"))),
            index = FacetByteSlice(byteEnd = 26L, byteStart = 8L),
        )

        val annotated = buildBlueskyAnnotatedString(text, listOf(facet), mapper)

        val mentions = annotated.getStringAnnotations(ANNOTATION_TAG_MENTION, 0, text.length)
        assertEquals(1, mentions.size)
        assertEquals(6, mentions[0].start)
        assertEquals(24, mentions[0].end)
        assertEquals("@alice.bsky.social", text.substring(mentions[0].start, mentions[0].end))
    }

    @Test
    fun `CJK before mention places mention at correct char range`() {
        val text = "こんにちは @alice.bsky.social"
        val facet = Facet(
            features = listOf(FacetMention(did = Did("did:plc:alice"))),
            index = FacetByteSlice(byteEnd = 34L, byteStart = 16L),
        )

        val annotated = buildBlueskyAnnotatedString(text, listOf(facet), mapper)

        val mentions = annotated.getStringAnnotations(ANNOTATION_TAG_MENTION, 0, text.length)
        assertEquals(1, mentions.size)
        assertEquals(6, mentions[0].start)
        assertEquals(24, mentions[0].end)
    }

    @Test
    fun `null facets returns plain AnnotatedString with no annotations`() {
        val annotated = buildBlueskyAnnotatedString("hello world", null, mapper)
        assertEquals("hello world", annotated.text)
        assertEquals(0, annotated.getStringAnnotations(ANNOTATION_TAG_MENTION, 0, 11).size)
        assertEquals(0, annotated.getStringAnnotations(ANNOTATION_TAG_TAG, 0, 11).size)
        assertEquals(0, annotated.getLinkAnnotations(0, 11).size)
    }

    @Test
    fun `empty facets returns plain AnnotatedString with no annotations`() {
        val annotated = buildBlueskyAnnotatedString("hello world", emptyList(), mapper)
        assertEquals("hello world", annotated.text)
        assertEquals(0, annotated.getStringAnnotations(ANNOTATION_TAG_MENTION, 0, 11).size)
    }

    @Test
    fun `style mapper is invoked per feature and result is applied to the range`() {
        val text = "see @alice.bsky.social"
        val facet = Facet(
            features = listOf(FacetMention(did = Did("did:plc:alice"))),
            index = FacetByteSlice(byteEnd = 22L, byteStart = 4L),
        )
        val customStyle = SpanStyle(color = Color.Cyan)
        var seen: FacetFeaturesUnion? = null

        val annotated = buildBlueskyAnnotatedString(text, listOf(facet)) { feature ->
            seen = feature
            customStyle
        }

        assertNotNull(seen)
        assertTrue(seen is FacetMention)
        // SpanStyle is applied — query at the mention's char range.
        val styles = annotated.spanStyles
        assertTrue(styles.any { it.item == customStyle && it.start == 4 && it.end == 22 })
    }

    @Test
    fun `mention click handler can resolve DID via the exported tag constant`() {
        val text = "@alice.bsky.social"
        val facet = Facet(
            features = listOf(FacetMention(did = Did("did:plc:alice"))),
            index = FacetByteSlice(byteEnd = 18L, byteStart = 0L),
        )
        val annotated = buildBlueskyAnnotatedString(text, listOf(facet), mapper)

        // Simulate a tap inside the mention range.
        val tapOffset = 5
        val resolved = annotated
            .getStringAnnotations(ANNOTATION_TAG_MENTION, tapOffset, tapOffset)
            .firstOrNull()

        assertNotNull(resolved)
        assertEquals("did:plc:alice", resolved.item)

        // Outside the mention range, no resolution.
        assertNull(
            annotated
                .getStringAnnotations(ANNOTATION_TAG_MENTION, text.length, text.length)
                .firstOrNull(),
        )
    }
}
