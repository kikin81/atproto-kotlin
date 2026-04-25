package io.github.kikin81.atproto.compose.material3

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import io.github.kikin81.atproto.app.bsky.richtext.Facet
import io.github.kikin81.atproto.compose.buildBlueskyAnnotatedString

/**
 * Remember a Material 3-styled [AnnotatedString] for Bluesky post
 * [text] + [facets]. The 80% case for Material 3 consumers — one line
 * to render a post.
 *
 * Memoizes on `(text, facets, linkStyle)` so recompositions don't
 * rebuild the boundary table or the annotation graph when nothing
 * relevant changed.
 *
 * `linkStyle` defaults to a [SpanStyle] colored with the current
 * `MaterialTheme.colorScheme.primary`. Consumers that want a different
 * color (brand color, secondary palette, etc.) pass their own
 * [SpanStyle] — the default is read fresh on every recomposition so
 * theme switches are picked up automatically.
 *
 * For consumers without Material 3 — those using a custom theme stack
 * with their own brand color for links — pull `:compose` directly and
 * call `buildBlueskyAnnotatedString` with a custom `styleMapper`.
 *
 * @param text the post's plain text.
 * @param facets the post record's facets (may be null).
 * @param linkStyle the [SpanStyle] applied to mention/link/tag spans.
 *   Defaults to `MaterialTheme.colorScheme.primary`.
 */
@Composable
public fun rememberBlueskyAnnotatedString(
    text: String,
    facets: List<Facet>?,
    linkStyle: SpanStyle = SpanStyle(color = MaterialTheme.colorScheme.primary),
): AnnotatedString = remember(text, facets, linkStyle) {
    buildBlueskyAnnotatedString(text, facets) { linkStyle }
}
