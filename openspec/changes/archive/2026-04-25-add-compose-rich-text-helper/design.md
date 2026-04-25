## Context

AT Protocol post records (`app.bsky.feed.post`) carry rich-text annotations
in a `facets: List<Facet>` field. Each `Facet` is:

```kotlin
data class Facet(
    val features: List<FacetFeaturesUnion>,  // open union: FacetMention | FacetLink | FacetTag | Unknown
    val index: FacetByteSlice,                // byteStart: Long, byteEnd: Long — UTF-8 byte offsets
)
```

The lexicon spec is explicit that `index.byteStart` / `byteEnd` are
**zero-indexed UTF-8 byte offsets**, not Unicode codepoints, not UTF-16
chars. The Kotlin generated types live in
`io.github.kikin81.atproto.app.bsky.richtext.*` (`:models` artifact, KMP
common code).

Jetpack Compose renders rich text via
`androidx.compose.ui.text.AnnotatedString`, which is a JVM/Android-only API.
`AnnotatedString` indices are **UTF-16 chars** (the JVM `String` native
unit). The two index spaces diverge whenever the text contains:

- Any character above U+007F (multi-byte in UTF-8)
- Surrogate pairs (e.g. emoji above the BMP — 4 UTF-8 bytes, 2 UTF-16 chars)
- Combining marks (Arabic / zalgo / certain CJK)

A correct facet → `AnnotatedString` helper must walk the source string and
maintain a byte-position-to-char-position map.

The existing `:models` and `:runtime` artifacts are KMP common (JVM + iOS).
Adding a Compose dependency to either would force iOS consumers to pull a
JVM-only library and would block iOS compilation outright. The Compose
helper therefore lives in a **new optional artifact pair**, not in the
existing modules.

GitHub issue #20 (filed 2026-04-24 by the nubecita team) provides the
problem statement, an initial 3-tier API sketch with five corrections, and
suggests `compose-rich-text` as the capability name. This design adopts
that structure with two further corrections grounded in the actual
generated lexicon types (see Decision 1 and Decision 6).

## Goals / Non-Goals

**Goals:**

- A consumer can render a `Bluesky post text + facets` pair as a fully
  styled `AnnotatedString` with ≤ 3 lines of integration code.
- The byte ↔ char index mapping is **bullet-proof** across emoji, CJK,
  and combining marks. The canonical regression test
  (`"hi 👋 @alice.bsky.social"`) places the mention highlight on the
  correct chars.
- The core helper (`:compose`) has **zero Material dependency**.
  Consumers with custom theme stacks (the nubecita team and any indie
  consumer using their own design system) can use it without pulling
  Material in transitively.
- Consumers using Material3 get a one-line convenience API in a separate
  `:compose-material3` artifact — pay only for what they want.
- Power consumers (inline content, custom icons next to mentions, per-
  feature tooltip wiring) have a low-level builder primitive
  (`AnnotatedString.Builder.appendBlueskyText(...)`) they can extend
  without re-implementing the byte-mapping.
- Forward compatibility: `FacetFeaturesUnion.Unknown` variants (lexicon
  evolution) and malformed facets (`byteStart >= byteEnd`,
  out-of-range bytes) are silently skipped — the helper **never throws**.
- Click handling for `#link` facets uses the native Compose 1.7+
  `LinkAnnotation.Url`. Mention and tag clicks use exported
  `ANNOTATION_TAG_*` constants so consumers reference public symbols, not
  magic strings.

**Non-Goals:**

- **iOS Compose support.** Compose for iOS exists but is alpha and uses
  a different `AnnotatedString` graph. Out of scope; iOS consumers
  consume the raw `Facet` types from `:models` directly.
- **`AnnotatedString` → `RichText` round-trip / outbound builder.**
  This change is read-only: rendering received post text. The reverse
  problem (composing a post with mentions / links the user types) needs
  a separate facet-detector module and is not in scope here.
- **Image embeds, video embeds, quote embeds.** Those are
  `app.bsky.embed.*` (separate from `app.bsky.richtext.facet`). Compose
  helpers for embeds may follow but are out of scope for this change.
- **Custom `LinkInteractionListener` defaults.** The `LinkAnnotation.Url`
  default behavior (OS browser intent) is documented; consumers wanting
  custom-tab integration override via the standard Compose `Text(...)`
  parameters. This change does not ship a Custom Tabs default.
- **Pluggable URL display formatting** (e.g. ellipsizing
  `https://very.long.url/path/...` to `very.long.url/...`). Bluesky's
  client does this client-side via the `link` facet feature's display
  text vs. URI distinction; the helper exposes the matched substring so
  consumers can format, but does not impose a default truncation.

## Decisions

### Decision 1: Use the actual generated sealed parent name `FacetFeaturesUnion`, not `FacetFeature`

Issue #20's API sketch uses `FacetFeature` as the sealed-parent type for
the styling callback. The actual generator emits this type as
`io.github.kikin81.atproto.app.bsky.richtext.FacetFeaturesUnion` (an open
`interface` per the runtime's open-union convention, with `FacetMention`,
`FacetLink`, `FacetTag`, and a nested `FacetFeaturesUnion.Unknown` data
class). Using the actual generated name keeps the helper aligned with the
codegen pipeline — if the generator ever renames the union (unlikely; it
follows the deterministic `<RecordName>FeaturesUnion` rule from
`NamingMatrix`), the helper picks up the rename automatically.

The public API uses `FacetFeaturesUnion` everywhere the issue's sketch
showed `FacetFeature`. Consumers' `when` expressions branch on
`FacetMention | FacetLink | FacetTag | FacetFeaturesUnion.Unknown`
exactly the same way.

**Alternatives considered:**

- **Define a hand-written `FacetFeature` typealias or sealed wrapper in
  `:compose`.** Rejected: introduces a parallel type hierarchy in front
  of the codegen output. Consumers who already work with the generated
  `FacetFeaturesUnion` from `:models` would have to convert, defeating
  the helper's purpose.
- **Use `Any` in the callback as in Gemini's first sketch.** Rejected
  for the reason in issue #20: kills exhaustive `when`, kills IDE
  auto-complete, kills the typed-API ergonomics that justify shipping a
  helper at all.

### Decision 2: One-pass UTF-8 ↔ UTF-16 mapping, not per-facet `String(bytes, offset, len, UTF_8)`

The naive byte-to-char algorithm (and the one in Gemini's first sketch)
recomputes `String(text.toByteArray(UTF_8), 0, byteOffset, UTF_8).length`
once per facet boundary, allocating a transient `String` per call. For
typical posts (text < 300 chars, ≤ 5 facets) this is negligible, but on
a feed of 50 visible posts × ~3 facets × 2 boundaries that's 300
transient `String` allocations per `LazyColumn` recomposition —
meaningful on cold-scroll.

The helper ships a one-pass walker:

1. Walk `text` once, tracking both running UTF-8 byte count and char
   index. Emit a sorted boundary table (e.g. `IntArray` of byte
   crossings, parallel to a `IntArray` of char positions).
2. Each facet boundary lookup is then **O(log N) via binary search with
   zero allocation**.

The walker handles surrogate pairs correctly: when it encounters a high
surrogate, it consumes both halves as one codepoint contributing 4 UTF-8
bytes and 2 UTF-16 chars in lockstep. Combining marks need no special
handling — they each contribute their own byte/char counts and the
boundary lookup just lands on the position immediately after the base
character (which matches the upstream Bluesky / `bsky.app` reference
behavior — facets address the codepoint sequence as written, not
grapheme clusters).

Cost: ~30 lines of code in `:compose`, fully unit-testable in isolation.

**Alternatives considered:**

- **Naive per-facet `String(bytes, ...)` slice.** Rejected: the
  performance characteristic of helper libraries calcifies. Once
  consumers depend on cold-scroll feeling smooth, fixing this later
  becomes a risky change. Get it right on day one.
- **Precompute by building a `ByteArray` once and using
  `String(bytes, off, len, UTF_8)` for each facet.** Better than the
  naive per-call encode, but still allocates one `String` per facet
  boundary. The one-pass walker has zero allocation per lookup.
- **Use `java.text.BreakIterator` for grapheme cluster boundaries.**
  Rejected: facets address codepoints, not grapheme clusters. Using
  `BreakIterator` would mis-align around combining-mark cases that
  Bluesky's reference client renders differently.

### Decision 3: Three-tier API — Builder primitive, mapper convenience, Material3 one-liner

The API has three layers, deliberately staged so each layer adds
opinionated convenience on top of the layer below:

- **Tier 1** — `AnnotatedString.Builder.appendBlueskyText(text, facets,
  onFacet)`. Pure primitive. Hands the consumer the typed
  `FacetFeaturesUnion`, char-range indices `(start, end)`, and the
  matched text `slice: String` for each facet. The library is responsible
  only for the byte-mapping and the iteration order. The consumer makes
  every styling/click-handling decision. This is the only layer that has
  to exist for the architecture to be sound — Tiers 2 and 3 are
  convenience wrappers built on it.
- **Tier 2** — `buildBlueskyAnnotatedString(text, facets, styleMapper)`.
  Wraps Tier 1 with a `(FacetFeaturesUnion) -> SpanStyle` mapper so the
  consumer's design system stays in control of styling. Adds the
  `LinkAnnotation.Url` for `#link` facets and `addStringAnnotation` with
  the exported `ANNOTATION_TAG_*` constants for `#mention` and `#tag`.
  This is the 80% case.
- **Tier 3** — `@Composable rememberBlueskyAnnotatedString(text, facets,
  linkStyle)`. Wraps Tier 2 with a default `linkStyle` of
  `SpanStyle(color = MaterialTheme.colorScheme.primary)`. One-liner for
  indie / quick-start consumers. Lives in `:compose-material3` so it
  doesn't pollute the core artifact.

This staging is the canonical "Builder → builder-fn → @Composable
remember-fn" pattern in Compose libraries (see e.g. `AnnotatedString`
itself, `LinkAnnotation`). Each layer is independently useful and
swappable. A consumer can ignore Tier 3 entirely (custom theme), use
Tier 2 directly with their own brand color, or drop to Tier 1 for inline
content needs.

**Alternatives considered:**

- **Single high-level API** `@Composable rememberBlueskyAnnotatedString`
  only. Rejected: forces the Material dependency on every consumer and
  gives no escape hatch for inline content (icons next to mentions,
  ellipsized URLs, per-feature tooltip overlays) that needs Tier 1.
- **Single low-level API** `appendBlueskyText` only. Rejected: makes
  the 80% case (Material3 indie consumer) write 15 lines of boilerplate
  every time they render a post. Tier 2 and Tier 3 are the value-add
  this SDK ships over raw lexicon access.

### Decision 4: Use Compose 1.7+ `LinkAnnotation.Url` for `#link` facets

Compose UI 1.7 added `LinkAnnotation.Url` as a first-class link
annotation type, with native click handling (no `pointerInput` + manual
`getStringAnnotations` round-trip). The compose ecosystem standardized
on this in Compose BOM `2024.06`+; the `:samples:android` already uses a
BOM that satisfies this.

For `#link` facets, the helper uses
`builder.addLink(LinkAnnotation.Url(uri), start, end)`. `Text(annotated)`
then handles clicks via the OS browser intent automatically. Consumers
wanting custom-tab integration (e.g. nubecita using
`androidx.browser.customtabs.CustomTabsIntent`) override via the
standard `Text(..., inlineContent = ..., linkInteractionListener = ...)`
parameters — no API surface needed in this helper.

For `#mention` and `#tag` facets, there's no built-in equivalent (the
target isn't a URL). Those continue to use `addStringAnnotation` with
the exported tag constants `ANNOTATION_TAG_MENTION` and
`ANNOTATION_TAG_TAG`. Consumers' click handlers do
`annotated.getStringAnnotations(ANNOTATION_TAG_MENTION, offset,
offset).firstOrNull()` to extract the DID / tag value.

The helper's minimum required Compose UI version is therefore **1.7.0**.

**Alternatives considered:**

- **Use `addStringAnnotation` for all three facet types uniformly.**
  Rejected: `LinkAnnotation.Url` is the modern, more ergonomic API; the
  in-the-box click handling means consumers don't need to wire a
  `pointerInput` for the most common case. Documenting two different
  click-handling paths (one for links, one for mentions/tags) is a fair
  trade for the better link UX.
- **Pin to a lower Compose version and use `addStringAnnotation` for
  links too.** Rejected: Compose UI 1.7+ is two years old at this
  point; any modern Android project will already meet it. The cost of
  pinning higher is essentially zero.

### Decision 5: Two artifacts — `:compose` and `:compose-material3` — not one

Putting the Material3 default in the same artifact as the core helper
forces every consumer (including those using fully custom theme stacks)
to pull `androidx.compose.material3:material3` transitively. The
nubecita team explicitly flagged this in issue #20 — they use M3 but
not `colorScheme.primary` for links. The split has precedent: the
existing `:oauth` module is also an optional add-on that doesn't pull
into `:runtime`.

Module split:

| Module | Gradle | Maven coordinate | Depends on |
|---|---|---|---|
| Core helper | `:compose` | `io.github.kikin81.atproto:compose` | `:models` + `androidx.compose.ui:ui-text` |
| Material3 default | `:compose-material3` | `io.github.kikin81.atproto:compose-material3` | `:compose` + `androidx.compose.material3:material3` |

Names follow the existing pattern (`runtime`, `models`, `oauth` — bare
module names, not `at-protocol-*` as issue #20 sketched). The published
group ID `io.github.kikin81.atproto` already namespaces sufficiently.

**Alternatives considered:**

- **Single `:compose` artifact with Material3 as `compileOnly`.**
  Rejected: `compileOnly` would make the Material3 helper compile but
  fail at runtime with a `NoClassDefFoundError` in apps that don't pull
  Material themselves. A separate artifact lets the type system catch
  the missing dependency at compile time.
- **`:compose` with both Material3 and core in the same module, and
  consumers `exclude` Material3.** Rejected: `exclude` is a Gradle
  workaround consumers shouldn't need. Splitting at publish time is the
  clean answer.

### Decision 6: Pass `slice: String` into the Tier 1 callback

Tier 1's `onFacet` callback receives the matched text as a `String`
slice alongside the indices and the typed feature:

```kotlin
public fun AnnotatedString.Builder.appendBlueskyText(
    text: String,
    facets: List<Facet>?,
    onFacet: AnnotatedString.Builder.(
        feature: FacetFeaturesUnion,
        startChar: Int,
        endChar: Int,
        slice: String,
    ) -> Unit,
)
```

Consumers building inline content (icon next to a `@handle`, ellipsizing
long URLs, per-feature tooling tips) shouldn't need to re-substring with
their own bounds. Cheap to compute (one `text.substring(start, end)`
per facet) and free on the consumer side. Issue #20's correction #5
calls this out.

**Alternatives considered:**

- **Omit `slice`; consumer re-substrings if they need it.** Rejected:
  every consumer that needs the slice would re-pay the substring cost,
  and a few would mis-compute the bounds (it happened in the Gemini
  sketch). Doing it once in the helper is strictly better.

### Decision 7: Silent skip of malformed facets and `Unknown` features

The helper **never throws** from facet processing. Two failure modes
are handled silently:

1. **Malformed indices** — `byteStart < 0`, `byteEnd > text.utf8Bytes`,
   `byteStart >= byteEnd`. These are skipped with a debug-level log
   (no public-API surface). Skipping rather than throwing matches the
   upstream `bsky.app` web client's behavior and protects consumers from
   crashing on adversarial / corrupt records.
2. **`FacetFeaturesUnion.Unknown` variants** — emitted by the codegen
   when a future lexicon adds a new facet feature kind (e.g. some
   `#stockTicker` facet that doesn't exist today). The helper silently
   skips, leaving the underlying text unstyled. Forward compatibility
   without consumer changes.

This is documented per public symbol's KDoc.

**Alternatives considered:**

- **Throw on malformed facets in debug builds.** Rejected: a single bad
  facet from one rogue record would crash the whole feed. Production
  apps would have to wrap every render in a try/catch, defeating the
  helper's purpose.
- **Surface skipped facets via a callback / result object.** Rejected
  for v1: adds API surface for an edge case, complicates the Tier 2
  signature. Can be added later if a consumer asks for it.

## Risks / Trade-offs

- **Compose 1.7 minimum version excludes some legacy projects.** →
  Compose 1.7 has been GA since June 2024 and is included in BOM
  `2024.06`+. Consumers on older BOMs upgrade or stay on the helper-less
  path. Documented in the module README.
- **`:compose` adds a third optional artifact to the publish matrix
  (alongside `:oauth`).** → Same vanniktech + Dokka pipeline as `:oauth`,
  no new release infra. CI matrix already builds and tests
  five modules; adding two more is incremental, not architectural.
- **Tier 3 (Material3 one-liner) duplicates a small amount of code from
  Tier 2.** → ~5 lines. Acceptable for the convenience.
- **`FacetFeaturesUnion` is the generator-emitted name today; if the
  generator's `NamingMatrix` rule changes, the public API of `:compose`
  changes too.** → The generator's naming is governed by INV-1..4
  invariants, with golden-file tests preventing accidental rename. A
  rename would be a deliberate, breaking change and would require a
  major version bump anyway.
- **One-pass byte-mapping correctness is subtle on combining marks.** →
  The unit test suite includes the canonical regression cases
  (emoji-before-mention, CJK + facet, combining diacritic + facet).
  Tested against the same corpus that the upstream Bluesky reference
  uses where possible.
- **`LinkAnnotation.Url` click handling defaults to OS browser intent.**
  → Consumers wanting Custom Tabs integration must override at the
  `Text(...)` call site. Documented in KDoc; no API change in the
  helper.

## Migration Plan

1. **Land `:compose` module** with Tier 1 + Tier 2 APIs, the one-pass
   byte-mapping walker, and the full unit test suite (canonical
   regression cases). No `:samples:android` integration yet — the module
   is independently testable against a JVM unit test harness.

2. **Land `:compose-material3` module** with Tier 3 `@Composable
   rememberBlueskyAnnotatedString`. Tiny module — one file, one test.

3. **Publish both artifacts** through the existing semantic-release +
   vanniktech pipeline. First release is a minor version bump
   (additive, no API breaks).

4. **Migrate `:samples:android`** feed post rendering to use the
   helper. Add `implementation(project(":compose-material3"))` to the
   sample's build, replace the existing facet-rendering code (if any)
   with a Tier 3 call. Keep both rendering modes (Tier 2 + Tier 3)
   visible in the sample so future consumers see both patterns.

5. **Update the module README and Dokka pages.** KDoc on each public
   symbol covers the byte-mapping invariant, the `LinkAnnotation` click
   behavior, and the malformed-facet silent-skip contract.

Rollback: the `:compose` and `:compose-material3` artifacts are purely
additive. Reverting is a `settings.gradle.kts` edit and a sample
revert; nothing in `:runtime` or `:models` changes.

## Open Questions

- **Should Tier 2 expose a `LinkInteractionListener` parameter for
  consumers wiring Custom Tabs?** The standard pattern is to set this on
  the `Text(...)` call site, not on the `AnnotatedString`. Defer until
  a consumer actually asks for an `AnnotatedString`-level hook —
  premature otherwise.
- **Should the helper emit a separate `LinkAnnotation.Clickable` (not
  `Url`) for `#mention` and `#tag` facets?** Compose UI 1.7+ has
  `LinkAnnotation.Clickable` for non-URL clickable spans, which would
  unify the click-handling story. Investigate during implementation;
  if the API is stable and the click handler ergonomics are good, prefer
  this over `addStringAnnotation`. Either way, the public API for
  consumers reading the annotations should remain unchanged.
- **Should `:compose-material3` also offer a Tier 2-equivalent
  `buildBlueskyAnnotatedString` that defaults the link color from
  `MaterialTheme`?** Currently only Tier 3 (the `@Composable` one)
  reads `MaterialTheme`. If the demand exists, a non-`@Composable`
  variant that takes a `ColorScheme` parameter could be added later.
- **Module name for the Material3 add-on: `:compose-material3` (current
  proposal) vs. `:material3` vs. `:compose-m3`.** Settled on
  `:compose-material3` to match the upstream Material3 artifact name
  (`androidx.compose.material3:material3`) and avoid ambiguity with
  potential future Material 2 / Compose Material support.
