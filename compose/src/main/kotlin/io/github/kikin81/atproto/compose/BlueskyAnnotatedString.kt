package io.github.kikin81.atproto.compose

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import io.github.kikin81.atproto.app.bsky.richtext.Facet
import io.github.kikin81.atproto.app.bsky.richtext.FacetFeaturesUnion
import io.github.kikin81.atproto.app.bsky.richtext.FacetLink
import io.github.kikin81.atproto.app.bsky.richtext.FacetMention
import io.github.kikin81.atproto.app.bsky.richtext.FacetTag

/**
 * String-annotation tag attached to spans corresponding to
 * `app.bsky.richtext.facet#mention` features. The annotation's value is
 * the mentioned account's DID raw string.
 *
 * Use this constant — not a literal string — when extracting mention
 * targets from a click handler:
 *
 * ```kotlin
 * val mention = annotated
 *     .getStringAnnotations(ANNOTATION_TAG_MENTION, offset, offset)
 *     .firstOrNull()
 *     ?.item // → "did:plc:..."
 * ```
 */
public const val ANNOTATION_TAG_MENTION: String = "io.github.kikin81.atproto.mention"

/**
 * String-annotation tag attached to spans corresponding to
 * `app.bsky.richtext.facet#tag` features. The annotation's value is
 * the bare tag string (without the leading `#`).
 *
 * @see ANNOTATION_TAG_MENTION
 */
public const val ANNOTATION_TAG_TAG: String = "io.github.kikin81.atproto.tag"

/**
 * Render Bluesky post [text] + [facets] as an [AnnotatedString], with
 * styling controlled by the consumer's [styleMapper].
 *
 * This is the 80% case in the Compose helper. It wraps the lower-level
 * [appendBlueskyText] primitive with sensible defaults:
 *
 * - Each facet's char range is decorated with `SpanStyle` returned by
 *   [styleMapper], so the consumer's design system stays in control of
 *   colors, weights, and decorations.
 * - `#link` facets are attached via Compose 1.7+
 *   [LinkAnnotation.Url], so `Text(annotated)` opens the URL via the
 *   OS browser intent without any consumer-side click wiring. To use
 *   Custom Tabs or another in-app browser, override the click via
 *   `Text(...)`'s standard `LinkInteractionListener` parameter.
 * - `#mention` facets attach a [String] annotation under
 *   [ANNOTATION_TAG_MENTION] whose value is the mentioned account's
 *   DID raw string. Click handlers extract via
 *   `annotated.getStringAnnotations(ANNOTATION_TAG_MENTION, offset, offset)`.
 * - `#tag` facets attach a [String] annotation under
 *   [ANNOTATION_TAG_TAG] whose value is the bare tag string.
 * - [FacetFeaturesUnion.Unknown] variants are silently skipped, so the
 *   text remains readable when the upstream lexicon adds a new facet
 *   feature kind.
 *
 * UTF-8 byte-to-UTF-16 char index mapping is bullet-proof across
 * emoji, multi-byte CJK, and combining marks. Malformed facets are
 * silently dropped — the helper never throws.
 *
 * Material 3 consumers should prefer
 * `rememberBlueskyAnnotatedString` from the `:compose-material3`
 * artifact, which defaults [styleMapper] to a `SpanStyle` colored by
 * the current `MaterialTheme.colorScheme.primary`.
 *
 * @param text the post's plain text.
 * @param facets the post record's facets (may be null).
 * @param styleMapper produces a [SpanStyle] for each facet feature
 *   variant. Receives the typed sealed parent so `when` is exhaustive
 *   and IDE-completable.
 */
public fun buildBlueskyAnnotatedString(
    text: String,
    facets: List<Facet>?,
    styleMapper: (feature: FacetFeaturesUnion) -> SpanStyle,
): AnnotatedString = buildAnnotatedString {
    appendBlueskyText(text, facets) { feature, startChar, endChar, _ ->
        when (feature) {
            is FacetMention -> {
                addStyle(styleMapper(feature), startChar, endChar)
                addStringAnnotation(
                    ANNOTATION_TAG_MENTION,
                    feature.did.raw,
                    startChar,
                    endChar,
                )
            }
            is FacetLink -> {
                addStyle(styleMapper(feature), startChar, endChar)
                addLink(LinkAnnotation.Url(feature.uri.raw), startChar, endChar)
            }
            is FacetTag -> {
                addStyle(styleMapper(feature), startChar, endChar)
                addStringAnnotation(
                    ANNOTATION_TAG_TAG,
                    feature.tag,
                    startChar,
                    endChar,
                )
            }
            is FacetFeaturesUnion.Unknown -> Unit
        }
    }
}
