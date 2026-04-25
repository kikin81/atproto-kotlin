## ADDED Requirements

### Requirement: Compose helper SHALL render Bluesky post text + facets as a correctly-styled `AnnotatedString`

The new `:compose` module SHALL provide a helper that converts a plain
`text: String` plus a `facets: List<Facet>?` (from
`io.github.kikin81.atproto.app.bsky.richtext.Facet`) into a Jetpack Compose
`androidx.compose.ui.text.AnnotatedString` whose styled spans align with
the post's mentions, links, and tags as authored. Facet `byteStart` /
`byteEnd` indices are UTF-8 byte offsets per the lexicon spec; the
helper SHALL translate them to UTF-16 char indices internally so that
spans land on the correct characters across emoji, multi-byte CJK, and
combining marks.

#### Scenario: Plain ASCII text with one mention is rendered with the mention spanned at the correct char range

- **WHEN** a consumer renders `text = "hello @alice.bsky.social"` with a
  single `Facet` whose `index.byteStart = 6`, `index.byteEnd = 24`, and
  `features` contains a `FacetMention(did = "did:plc:alice")`
- **THEN** the resulting `AnnotatedString` carries the consumer's mention
  `SpanStyle` over chars 6 through 24 inclusive of the start, exclusive
  of the end (matching the byte range exactly because the text is ASCII)
  and exposes a string annotation whose value is the DID raw string

#### Scenario: Emoji before a mention does not break the mention boundary (canonical regression test)

- **WHEN** a consumer renders `text = "hi 👋 @alice.bsky.social"` with a
  `Facet` whose byte range covers exactly the `@alice.bsky.social`
  substring per the upstream `bsky.app` reference encoding
- **THEN** the resulting `AnnotatedString` styles the chars corresponding
  to `@alice.bsky.social` and leaves the `"hi 👋 "` prefix unstyled — the
  4-byte / 2-char emoji 👋 does not shift the mention by 2 chars

#### Scenario: Multi-byte CJK text with a facet preserves the correct char boundary

- **WHEN** a consumer renders text that contains CJK characters before
  a facet (e.g. `"こんにちは @alice.bsky.social"`)
- **THEN** the styled span lands on the chars corresponding to the
  mention substring, not on the byte offsets misinterpreted as char
  offsets

#### Scenario: Combining diacritics before a facet do not shift the boundary

- **WHEN** a consumer renders text containing combining diacritics
  (Arabic / zalgo / per-base-character marks) before a facet
- **THEN** the styled span lands at the char position corresponding to
  the first byte after the combining-mark sequence, matching the
  upstream Bluesky reference renderer

### Requirement: Tier 1 builder primitive SHALL be exposed for power consumers

The `:compose` module SHALL expose a public extension function on
`AnnotatedString.Builder`:

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

The helper SHALL append the full `text` to the builder and SHALL invoke
the `onFacet` callback once per valid facet feature in `byteStart`-sorted
order, passing the typed `FacetFeaturesUnion`, the translated UTF-16 char
indices, and the matched substring slice. The callback runs with the
builder as receiver so consumers can apply styles, inline content,
string annotations, or `LinkAnnotation`s as they choose.

#### Scenario: Multiple facets are delivered to the callback in byteStart order

- **WHEN** a consumer renders text with two facets whose `byteStart`
  values are `30` and `5` respectively
- **THEN** the `onFacet` callback fires twice, with the `byteStart=5`
  facet first and the `byteStart=30` facet second

#### Scenario: Callback receives the matched substring slice without the consumer re-substringing

- **WHEN** the helper invokes `onFacet` for a facet covering chars 5..18
- **THEN** the `slice` parameter equals `text.substring(5, 18)` — the
  consumer does not need to re-compute the substring from the indices

### Requirement: Tier 2 mapper-based convenience SHALL build the AnnotatedString in one call

The `:compose` module SHALL expose a public top-level function:

```kotlin
public fun buildBlueskyAnnotatedString(
    text: String,
    facets: List<Facet>?,
    styleMapper: (feature: FacetFeaturesUnion) -> SpanStyle,
): AnnotatedString
```

The helper SHALL build a complete `AnnotatedString` by invoking
`appendBlueskyText` internally with an `onFacet` lambda that:
- applies `styleMapper(feature)` as a `SpanStyle` over the facet's char
  range,
- attaches a `LinkAnnotation.Url` (Compose UI 1.7+) for `FacetLink`
  features so default click handling opens the URL,
- attaches an `addStringAnnotation` with tag `ANNOTATION_TAG_MENTION`
  and value equal to the `FacetMention.did` raw string for `FacetMention`
  features,
- attaches an `addStringAnnotation` with tag `ANNOTATION_TAG_TAG` and
  value equal to the `FacetTag.tag` for `FacetTag` features,
- silently skips `FacetFeaturesUnion.Unknown` features.

#### Scenario: Mention facet is annotated with the exported mention tag

- **WHEN** a consumer calls `buildBlueskyAnnotatedString` on text with
  one mention facet for DID `did:plc:alice`
- **THEN** `result.getStringAnnotations(ANNOTATION_TAG_MENTION, 0,
  text.length)` returns a single annotation whose value is
  `"did:plc:alice"` and whose range matches the mention's char range

#### Scenario: Link facet uses LinkAnnotation.Url

- **WHEN** a consumer calls `buildBlueskyAnnotatedString` on text
  containing a link facet pointing at `https://example.com`
- **THEN** the resulting `AnnotatedString` carries a
  `LinkAnnotation.Url("https://example.com")` over the link's char
  range, such that `Text(result)` renders the link with native
  Compose 1.7 click handling

#### Scenario: Tag facet is annotated with the exported tag tag

- **WHEN** a consumer calls `buildBlueskyAnnotatedString` on text
  containing a tag facet for `bluesky`
- **THEN** `result.getStringAnnotations(ANNOTATION_TAG_TAG, 0,
  text.length)` returns a single annotation whose value is `"bluesky"`

#### Scenario: Unknown facet feature variant is silently skipped

- **WHEN** a consumer calls `buildBlueskyAnnotatedString` on text whose
  facets include a `FacetFeaturesUnion.Unknown` (e.g. a future facet
  feature kind not yet known to the codegen)
- **THEN** the resulting `AnnotatedString` contains the full text with
  no styling for the unknown feature, and the helper does not throw

### Requirement: Tier 3 Material3 one-liner SHALL live in a separate optional artifact

The `:compose-material3` module SHALL expose a public composable:

```kotlin
@Composable
public fun rememberBlueskyAnnotatedString(
    text: String,
    facets: List<Facet>?,
    linkStyle: SpanStyle = SpanStyle(color = MaterialTheme.colorScheme.primary),
): AnnotatedString
```

The helper SHALL `remember` the result keyed on `(text, facets,
linkStyle)`, internally delegate to `buildBlueskyAnnotatedString`, and
default `linkStyle` to a span style colored with the current
`MaterialTheme.colorScheme.primary`. The `:compose` module SHALL NOT
depend on `androidx.compose.material3:material3`; only
`:compose-material3` SHALL.

#### Scenario: Default link style reads the Material3 primary color

- **WHEN** a consumer renders a post inside a `MaterialTheme` with a
  custom `colorScheme` whose `primary` is `Color.Magenta`
- **AND** calls `rememberBlueskyAnnotatedString(text, facets)` without
  overriding `linkStyle`
- **THEN** the resulting `AnnotatedString`'s link/mention/tag spans use
  `Color.Magenta` as their text color

#### Scenario: Consumer-provided linkStyle overrides the Material default

- **WHEN** a consumer calls
  `rememberBlueskyAnnotatedString(text, facets, linkStyle =
  SpanStyle(color = Color.Cyan))`
- **THEN** the resulting `AnnotatedString` uses `Color.Cyan` regardless
  of the active `MaterialTheme`

### Requirement: Annotation tag constants SHALL be public exported symbols, not magic strings

The `:compose` module SHALL declare public top-level `const val`
declarations:

- `ANNOTATION_TAG_MENTION` — the string annotation tag used for
  `FacetMention` features
- `ANNOTATION_TAG_TAG` — the string annotation tag used for `FacetTag`
  features

Consumers' click handlers SHALL be able to do
`annotated.getStringAnnotations(ANNOTATION_TAG_MENTION, offset, offset)`
referencing the public constant.

#### Scenario: Consumer click handler resolves a mention via the exported constant

- **WHEN** a consumer has rendered post text with
  `buildBlueskyAnnotatedString` and the user taps a mention
- **AND** the consumer's tap handler calls
  `annotated.getStringAnnotations(ANNOTATION_TAG_MENTION, tapOffset,
  tapOffset).firstOrNull()`
- **THEN** the returned annotation's `item` is the mentioned account's
  DID raw string, with no reference to a magic-string tag value

### Requirement: Helper SHALL silently skip malformed facets and SHALL NOT throw

The helper SHALL silently skip any facet that is malformed in a way
that would cause an indexing error, including but not limited to:

- `byteStart < 0` or `byteEnd < 0`
- `byteEnd <= byteStart`
- `byteEnd > text.encodeToByteArray().size`
- `byteStart > text.encodeToByteArray().size`
- A facet whose `features` list is empty

Skipping a malformed facet SHALL NOT throw, SHALL NOT abort processing of
other facets, and SHALL leave the underlying text unstyled at that
range.

#### Scenario: Out-of-range byteEnd is dropped without affecting other facets

- **WHEN** a consumer renders text with two facets — facet A has a
  valid range, facet B has `byteEnd` past the end of the text
- **THEN** the resulting `AnnotatedString` carries facet A's style and
  contains the full text with no styling at facet B's intended range,
  and the helper does not throw

#### Scenario: Inverted byte range is dropped

- **WHEN** a consumer renders text with a facet whose
  `byteStart >= byteEnd`
- **THEN** the helper drops the facet silently and renders the
  remaining text + facets normally

### Requirement: Empty and null facets lists SHALL produce a plain AnnotatedString

The helper SHALL accept `facets: null` and `facets: emptyList()` and
SHALL produce an `AnnotatedString` containing the full `text` with no
styled spans and no annotations.

#### Scenario: Null facets returns plain text

- **WHEN** a consumer calls `buildBlueskyAnnotatedString(text =
  "hello world", facets = null) { _ -> SpanStyle() }`
- **THEN** the result equals `AnnotatedString("hello world")` with no
  styled spans

#### Scenario: Empty facets returns plain text

- **WHEN** a consumer calls `buildBlueskyAnnotatedString(text =
  "hello world", facets = emptyList()) { _ -> SpanStyle() }`
- **THEN** the result equals `AnnotatedString("hello world")` with no
  styled spans

### Requirement: Core `:compose` artifact SHALL NOT depend on Material

The `:compose` Gradle module SHALL declare its dependencies as
`:models` plus `androidx.compose.ui:ui-text` (Compose UI ≥ 1.7.0) and
SHALL NOT include any dependency on `androidx.compose.material:material`
or `androidx.compose.material3:material3`. A consumer that uses only
`:compose` (Tier 1 + Tier 2 APIs) SHALL be able to compile, test, and
run their app without pulling Material into their dependency graph.

#### Scenario: Material is not on the compileClasspath of the core artifact

- **WHEN** the `:compose` module's published POM is consumed
- **THEN** the resolved compile-classpath does not contain
  `androidx.compose.material3:material3` or
  `androidx.compose.material:material` as a transitive dependency

### Requirement: Modules SHALL publish to Maven Central via the existing pipeline

Both `:compose` and `:compose-material3` SHALL publish to Maven Central
and GitHub Packages through the existing semantic-release +
`open-turo/actions-jvm/release` + vanniktech-maven-publish pipeline,
under the existing group `io.github.kikin81.atproto`. Artifact IDs SHALL
be `compose` and `compose-material3` respectively (matching the
existing module-name-as-artifact-ID convention used by `runtime`,
`models`, and `oauth`). Both SHALL ship a Dokka-generated javadoc JAR
and a sources JAR per the project's release standard.

#### Scenario: Released artifact coordinates resolve from Maven Central

- **WHEN** a downstream consumer adds
  `io.github.kikin81.atproto:compose:<version>` to their Gradle
  dependencies
- **THEN** the artifact resolves from Maven Central with the matching
  POM, sources JAR, and Dokka javadoc JAR

#### Scenario: Material3 add-on resolves and brings in the core helper transitively

- **WHEN** a downstream consumer adds only
  `io.github.kikin81.atproto:compose-material3:<version>`
- **THEN** the consumer's classpath contains both
  `compose-material3` and `compose` (transitively), and Tier 1 + Tier 2
  + Tier 3 APIs are all callable
