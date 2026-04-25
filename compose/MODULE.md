# Module compose

Jetpack Compose helpers for the AT Protocol Kotlin SDK. Renders Bluesky
post text + `app.bsky.richtext.facet` annotations as a correctly-styled
`AnnotatedString`, with bullet-proof UTF-8 byte → UTF-16 char mapping
across emoji, multi-byte CJK, and combining marks.

## What's in here

- **`AnnotatedString.Builder.appendBlueskyText(...)`** — Tier 1 builder
  primitive. Hands the consumer the typed `FacetFeaturesUnion`,
  char-range indices, and the matched substring slice for each facet.
  Use this when you need inline content (icons next to mentions,
  ellipsized URLs, per-feature tooltips) that the higher-level helpers
  don't cover.
- **`buildBlueskyAnnotatedString(text, facets, styleMapper)`** — Tier 2
  the 80% case. Wraps the builder with a typed
  `(FacetFeaturesUnion) -> SpanStyle` mapper so the consumer's design
  system stays in control of styling. Adds `LinkAnnotation.Url` for
  link facets and string annotations (`ANNOTATION_TAG_MENTION`,
  `ANNOTATION_TAG_TAG`) for mentions and tags.
- **`ANNOTATION_TAG_MENTION` / `ANNOTATION_TAG_TAG`** — public constants
  for click handlers to call `getStringAnnotations(...)` against.

## What's not in here

Material 3 styling defaults — those live in `:compose-material3`. The
core `:compose` artifact has no Material dependency, so consumers with
custom theme stacks can pull just this module.

## Byte-mapping invariant

Facets in AT Protocol address ranges by UTF-8 byte offsets, but
`AnnotatedString` indexes by UTF-16 chars. The helper translates
between the two with a one-pass walker that handles surrogate-pair
emoji and combining marks correctly. Malformed facets (`byteEnd <=
byteStart`, out-of-range bytes) and unknown facet feature variants
(`FacetFeaturesUnion.Unknown`) are silently skipped — the helper never
throws.

# Package io.github.kikin81.atproto.compose

`appendBlueskyText` builder extension, `buildBlueskyAnnotatedString`
helper, and the exported `ANNOTATION_TAG_*` annotation tag constants.
