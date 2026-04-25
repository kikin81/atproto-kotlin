# AT Protocol Compose helpers (`compose`)

Jetpack Compose helpers for rendering Bluesky post text +
`app.bsky.richtext.facet` annotations as a correctly-styled
`AnnotatedString`. Bullet-proof UTF-8 byte → UTF-16 char index mapping
across emoji, multi-byte CJK, and combining marks. **No Material
dependency** — pull `:compose-material3` for the Material 3 default.

## Quick start

```kotlin
val annotated = buildBlueskyAnnotatedString(
    text = post.text,
    facets = post.facets, // List<Facet>?
) { feature ->
    SpanStyle(color = myBrandColor)
}
Text(annotated)
```

`Text(annotated)` automatically opens `#link` facets in the OS browser
via Compose 1.7's `LinkAnnotation.Url`. Mention and tag clicks attach
to exported annotation tags — extract the targets with
`getStringAnnotations(...)` (see below).

## API tiers

The helper has three layers, designed so each adds opinionated
convenience on top of the layer below. Pick the lowest one that meets
your needs.

| Tier | Symbol | Artifact | When |
| --- | --- | --- | --- |
| 1 — Builder primitive | `AnnotatedString.Builder.appendBlueskyText` | `:compose` | You need inline content (icons next to mentions, ellipsized URLs, per-feature tooltips) and want to drive the builder yourself. |
| 2 — Mapper convenience | `buildBlueskyAnnotatedString` | `:compose` | You want a styled `AnnotatedString` in one call and your design system supplies its own colors. **The 80% case.** |
| 3 — Material 3 default | `@Composable rememberBlueskyAnnotatedString` | `:compose-material3` | Your app uses Material 3 and you're happy with `MaterialTheme.colorScheme.primary` for link styling. One-liner. |

### Tier 1 — Builder primitive

The lowest-level escape hatch. Hands you the typed
`FacetFeaturesUnion`, char-range indices, and the matched substring
slice for each facet. The library handles only the byte-to-char
mapping — you decide everything else.

```kotlin
val annotated = buildAnnotatedString {
    appendBlueskyText(post.text, post.facets) { feature, start, end, slice ->
        when (feature) {
            is FacetMention -> {
                appendInlineContent("avatar", "[@]")
                addStyle(myMentionStyle, start, end)
                addStringAnnotation(ANNOTATION_TAG_MENTION, feature.did.raw, start, end)
            }
            is FacetLink -> {
                addStyle(myLinkStyle, start, end)
                addLink(LinkAnnotation.Url(feature.uri.raw), start, end)
            }
            is FacetTag -> {
                addStyle(myTagStyle, start, end)
                addStringAnnotation(ANNOTATION_TAG_TAG, feature.tag, start, end)
            }
            is FacetFeaturesUnion.Unknown -> Unit
        }
    }
}
```

The `slice: String` parameter is precomputed
(`text.substring(start, end)`) so consumers building inline content
don't need to re-substring with their own bounds.

### Tier 2 — Mapper convenience

Wraps Tier 1 with typed `(FacetFeaturesUnion) -> SpanStyle` styling.
Auto-attaches `LinkAnnotation.Url` for links and string annotations
for mentions and tags. Skips `FacetFeaturesUnion.Unknown` silently.

```kotlin
val annotated = buildBlueskyAnnotatedString(
    text = post.text,
    facets = post.facets,
) { feature ->
    when (feature) {
        is FacetMention -> SpanStyle(color = myDesignSystem.mentionColor)
        is FacetLink    -> SpanStyle(color = myDesignSystem.linkColor)
        is FacetTag     -> SpanStyle(color = myDesignSystem.tagColor)
        is FacetFeaturesUnion.Unknown -> SpanStyle()
    }
}
```

### Tier 3 — Material 3 default (`:compose-material3`)

```kotlin
val annotated = rememberBlueskyAnnotatedString(post.text, post.facets)
Text(annotated)
```

Defaults `linkStyle` to
`SpanStyle(color = MaterialTheme.colorScheme.primary)`. Override with
the `linkStyle` parameter if your Material 3 theme uses a different
color.

## Click handling

`#link` facets use Compose 1.7+ `LinkAnnotation.Url` — clicks open the
OS browser intent automatically when rendered via `Text(annotated)`.
For Custom Tabs or other in-app browsers, override at the call site:

```kotlin
Text(
    annotated,
    onTextLayout = { /* ... */ },
    inlineContent = mapOf(/* ... */),
    // Compose UI lets you swap the link click behavior here.
)
```

`#mention` and `#tag` facets attach string annotations under the
exported tag constants. Wire your own click handler:

```kotlin
ClickableText(annotated) { offset ->
    annotated.getStringAnnotations(ANNOTATION_TAG_MENTION, offset, offset)
        .firstOrNull()?.let { onMentionTap(Did(it.item)) }
        ?: annotated.getStringAnnotations(ANNOTATION_TAG_TAG, offset, offset)
            .firstOrNull()?.let { onTagTap(it.item) }
}
```

## Byte-mapping invariant

AT Protocol facets address sub-strings by **UTF-8 byte offsets**, but
Compose `AnnotatedString` indexes by **UTF-16 chars**. The two diverge
whenever the text contains a codepoint above U+007F.

The canonical regression case: `"hi 👋 @alice.bsky.social"`. The 👋
emoji is 4 UTF-8 bytes but 2 UTF-16 chars (a surrogate pair). A naive
`text.substring(byteStart, byteEnd)` shifts the mention highlight by
2 chars — or throws `IndexOutOfBoundsException`.

This helper walks the text once at construction, building a sorted
boundary table, then answers each facet's byte-to-char query via
binary search with zero allocation per lookup. Tested against:

- Pure ASCII (no boundary changes)
- 2-byte UTF-8 (Latin-1 supplement, e.g. `é`)
- 3-byte UTF-8 (CJK, e.g. `中`, `こんにちは`)
- 4-byte UTF-8 (emoji, e.g. `👋`)
- Decomposed combining marks (`e` + U+0301)

## Silent-skip semantics

The helper **never throws** from facet processing. Two failure modes
are handled silently, leaving the underlying text unstyled but
readable:

- **Malformed indices** — `byteEnd <= byteStart`, out-of-range bytes,
  byte offsets that fall inside a codepoint's UTF-8 sequence.
  Skipping protects against corrupt or adversarial records.
- **`FacetFeaturesUnion.Unknown`** — emitted by codegen when a future
  lexicon adds a new facet feature kind. Forward compatibility without
  consumer changes.

## Minimum versions

- **Compose UI 1.7.0+** (for `LinkAnnotation.Url` and the unified
  link API). Compose BOM `2024.06`+ satisfies this.
- **JDK 17** (toolchain).
- **AGP 9.x** with built-in Kotlin support.

## Dependencies

| Module | Depends on |
| --- | --- |
| `:compose` | `:models` + `androidx.compose.ui:ui-text` (no Material) |
| `:compose-material3` | `:compose` + `androidx.compose.material3:material3` |

The split lets consumers with custom theme stacks pull just `:compose`
without paying the Material 3 transitive cost.
