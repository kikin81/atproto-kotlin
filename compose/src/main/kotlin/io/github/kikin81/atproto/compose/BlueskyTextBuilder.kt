package io.github.kikin81.atproto.compose

import androidx.compose.ui.text.AnnotatedString
import io.github.kikin81.atproto.app.bsky.richtext.Facet
import io.github.kikin81.atproto.app.bsky.richtext.FacetFeaturesUnion
import io.github.kikin81.atproto.compose.internal.Utf8CharBoundaryTable

/**
 * Append Bluesky [text] to this [AnnotatedString.Builder] and invoke
 * [onFacet] for every valid facet feature, sorted by `byteStart`
 * ascending.
 *
 * This is the lowest-level primitive in the Compose helper. The full
 * [text] is appended to the builder once, up front. Callers handle
 * styling, inline content, link/string annotations themselves inside
 * the [onFacet] lambda — this primitive's job is only to translate
 * UTF-8 byte offsets (the facet contract) into UTF-16 char offsets
 * (the [AnnotatedString] contract) correctly.
 *
 * The byte-to-char mapping is computed once via a one-pass walker
 * (see `Utf8CharBoundaryTable`) and shared across all facets, so this
 * function is O(text.length + facets.size · log codepoints) and
 * allocates no transient `String`s for boundary lookups.
 *
 * **Silent skip contract.** Malformed facets are dropped without
 * throwing:
 * - `byteStart < 0`, `byteEnd <= byteStart`, or `byteEnd > utf8 length`
 * - byte offsets that fall *inside* a codepoint's UTF-8 byte sequence
 * - facets with an empty `features` list
 *
 * Unknown feature variants ([FacetFeaturesUnion.Unknown]) are *not*
 * dropped here — they are passed to [onFacet] so callers can decide
 * whether to render them or skip them. The higher-level
 * [buildBlueskyAnnotatedString] skips unknowns silently.
 *
 * **Sort order guarantee.** [onFacet] is invoked in `byteStart`
 * ascending order. Ties between facets at the same `byteStart` preserve
 * their original order in the input list (stable sort). Ties between
 * features inside a single facet preserve their `features` array
 * order. This lets callers stream output (e.g. `appendInlineContent`)
 * without sorting themselves.
 *
 * The [slice] parameter on [onFacet] is `text.substring(startChar,
 * endChar)`, precomputed so callers building inline content (icon
 * next to a `@handle`, ellipsized URLs) don't need to re-substring
 * with their own bounds.
 *
 * @param text the post's plain text. Appended to the builder up front.
 * @param facets facet annotations from the post record (may be null).
 * @param onFacet invoked for each facet feature, with the typed
 *   sealed-parent [FacetFeaturesUnion], the translated UTF-16 char
 *   range `[startChar, endChar)`, and the matched substring `slice`.
 *   Runs with the builder as receiver — call `addStyle`, `addLink`,
 *   `addStringAnnotation`, etc. to attach behavior to the range.
 */
public fun AnnotatedString.Builder.appendBlueskyText(
    text: String,
    facets: List<Facet>?,
    onFacet: AnnotatedString.Builder.(
        feature: FacetFeaturesUnion,
        startChar: Int,
        endChar: Int,
        slice: String,
    ) -> Unit,
) {
    // Capture the builder's existing length so facet indices line up with
    // the builder's char space, not just `text`'s. Consumers calling this
    // after appending a prefix (e.g. a "@handle " label or a quoted-post
    // attribution) need indices that work with `addStyle(start, end)` on
    // the receiver.
    val baseChar = length
    append(text)

    if (facets.isNullOrEmpty()) return

    val table = Utf8CharBoundaryTable(text)
    val sorted = facets.sortedBy { it.index.byteStart }

    for (facet in sorted) {
        if (facet.features.isEmpty()) continue

        val byteStart = facet.index.byteStart
        val byteEnd = facet.index.byteEnd
        if (byteStart < 0L || byteEnd <= byteStart) continue
        if (byteStart > Int.MAX_VALUE || byteEnd > Int.MAX_VALUE) continue

        val startCharInText = table.byteToChar(byteStart.toInt()) ?: continue
        val endCharInText = table.byteToChar(byteEnd.toInt()) ?: continue
        if (endCharInText <= startCharInText) continue

        val slice = text.substring(startCharInText, endCharInText)
        val startChar = baseChar + startCharInText
        val endChar = baseChar + endCharInText
        for (feature in facet.features) {
            onFacet(feature, startChar, endChar, slice)
        }
    }
}
