package io.github.kikin81.atproto.samples.bluesky.util

import io.github.kikin81.atproto.app.bsky.richtext.Facet
import io.github.kikin81.atproto.runtime.AtField

/**
 * Coerce the three-state [AtField] facets field on a `Post` record into
 * the nullable list shape that
 * `io.github.kikin81.atproto.compose.material3.rememberBlueskyAnnotatedString`
 * accepts. `Missing` and `Null` collapse to `null`; the helper then
 * renders the post text without facet styling.
 */
fun AtField<List<Facet>>.toListOrNull(): List<Facet>? = when (this) {
    is AtField.Defined -> value
    AtField.Missing, AtField.Null -> null
}
