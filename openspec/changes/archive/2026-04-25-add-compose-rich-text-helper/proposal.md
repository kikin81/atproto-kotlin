## Why

Every Android consumer of `io.github.kikin81.atproto:*` that renders Bluesky
post text must convert AT Protocol `app.bsky.richtext.facet` annotations into
a Jetpack Compose `AnnotatedString`. Two traps make this hostile to get right:

1. **The "Emoji Crash."** Facets address ranges by **UTF-8 byte** offsets
   (`byteStart`, `byteEnd`), but Compose / Java strings index by **UTF-16
   chars**. The first time a user posts `"hi 👋 @alice.bsky.social"`, the
   naive `String.substring(byteStart, byteEnd)` mis-aligns and the mention
   highlight lands on the wrong characters — or throws
   `IndexOutOfBoundsException`.
2. **Design-system lock-in.** A naive helper hardcodes
   `MaterialTheme.colorScheme.primary` for link color, which transitively
   forces every consumer to depend on `androidx.compose.material3:material3`
   even when their app uses a custom theme stack with its own brand color
   for links.

Filed by the nubecita team (consumer of this SDK) as GitHub issue #20. This
is the single highest-leverage helper the SDK can ship for Android
consumers — every post-text renderer needs it, and every one will hit the
byte-mapping bug if they roll their own.

`androidx.compose.ui.text.AnnotatedString` is JVM/Android-only Compose, so
this cannot live in `:models` or `:runtime` (both KMP common). It has to be
a new optional artifact pair.

## What Changes

- **New module: `:compose`** — JVM/Android-only. Depends on `:models` +
  `androidx.compose.ui:ui-text`. **No Material dependency.** Provides the
  core helper with three layers of API:
  - `AnnotatedString.Builder.appendBlueskyText(text, facets, onFacet)` —
    Tier 1, the lowest-level primitive. Hands the consumer the typed
    `FacetFeature` sealed parent, char-range indices, and the matched text
    slice for each facet, on a builder they already control.
  - `buildBlueskyAnnotatedString(text, facets, styleMapper)` — Tier 2, the
    80% case. Wraps Tier 1 with a `(FacetFeature) -> SpanStyle` mapper so
    consumers plug in their own design system's link/mention/tag styling.
  - Exported annotation tag constants `ANNOTATION_TAG_MENTION`,
    `ANNOTATION_TAG_TAG` so consumers' click handlers reference public
    symbols, not magic strings.
- **New module: `:compose-material3`** — JVM/Android-only. Depends on
  `:compose` + `androidx.compose.material3:material3`. Provides:
  - `@Composable rememberBlueskyAnnotatedString(text, facets, linkStyle)`
    — Tier 3, the one-liner for indie / quick-start consumers. Defaults
    `linkStyle` to `SpanStyle(color = MaterialTheme.colorScheme.primary)`.
- **Bullet-proof byte ↔ char index mapping.** The library guarantees that
  facets ranges given in UTF-8 bytes are translated to correct UTF-16 char
  ranges across emoji, multi-byte CJK, and combining-mark text.
- **Compose 1.7+ `LinkAnnotation.Url` for `#link` facets.** Native click
  handling, with `LinkInteractionListener` as the documented extension
  point for custom-tab integrations.
- **Graceful degradation.** Malformed facets (`byteStart >= byteEnd`,
  out-of-range bytes) are silently skipped — never throw. Unknown facet
  feature variants (`FacetFeature.Unknown`) are also skipped, preserving
  forward compatibility as the lexicon evolves.
- **Sample integration.** The `:samples:android` feed swaps its current
  post-text rendering for the new helper, demonstrating both Tier 2
  (custom-style) and Tier 3 (Material3) usage.

## Capabilities

### New Capabilities

- `compose-rich-text`: The Jetpack Compose `AnnotatedString` helper for
  rendering AT Protocol post text + `app.bsky.richtext.facet` annotations,
  spanning the core `:compose` artifact (Tier 1 + Tier 2 APIs, byte-to-char
  mapping invariant, malformed-facet handling) and the optional
  `:compose-material3` artifact (Tier 3 one-liner).

### Modified Capabilities

None. The new `:compose` and `:compose-material3` artifacts plug into the
existing semantic-release + vanniktech + Dokka publishing pipeline with
no changes to the release infra; the artifact split is captured as
requirements within the new `compose-rich-text` capability spec.

## Impact

- **New modules**: `:compose` and `:compose-material3` (both Kotlin/JVM,
  added to `settings.gradle.kts`).
- **New dependencies** (compose module only): `androidx.compose.ui:ui-text`
  (≥ 1.7.0 for `LinkAnnotation.Url`). Material3 dep lives only in the
  `:compose-material3` module so the core helper stays Material-free.
  **Neither propagates to `:runtime` or `:models`** — both are optional
  add-ons consumers pull when they want Compose helpers.
- **Modified module**: `:samples:android` — feed post rendering migrates
  to the new helper as a reference implementation.
- **External upstream**: Closes GitHub issue #20. Lets the nubecita team
  delete their colocated `nubecita-t3o` ticket and consume this upstream.
- **Compose UI minimum version**: ≥ 1.7.0. Acceptable — Compose BOM `2024.06`
  and later all ship 1.7+, and the sample is already on a current BOM.
