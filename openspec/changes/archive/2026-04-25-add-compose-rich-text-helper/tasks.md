## 1. `:compose` module scaffolding

- [x] 1.1 Create `:compose` as a Kotlin/JVM module in `compose/`, add to `settings.gradle.kts`
- [x] 1.2 Add Compose UI version (≥ 1.7.0) to `gradle/libs.versions.toml` if not already present (`androidx.compose.ui:ui-text` + a Compose BOM alignment)
- [x] 1.3 Configure `compose/build.gradle.kts` with `kotlin.jvm` toolchain 17, dokka, vanniktech-maven-publish, and Compose runtime/UI dependencies
- [x] 1.4 Add `implementation(project(":models"))` and `implementation(libs.androidx.compose.ui.text)` (no Material dependency)
- [x] 1.5 Wire `mavenPublishing { ... }` to publish as `io.github.kikin81.atproto:compose` matching the existing `:oauth` POM template
- [x] 1.6 Add `compose/MODULE.md` with a one-paragraph overview for Dokka aggregation
- [x] 1.7 Verify `./gradlew :compose:compileKotlin` and `./gradlew spotlessApply` succeed on an empty module

## 2. UTF-8 byte ↔ UTF-16 char index walker

- [x] 2.1 Implement `internal class Utf8CharBoundaryTable(text: String)` that walks `text` once, records sorted byte→char crossings (parallel `IntArray`s), and exposes `fun byteToChar(byte: Int): Int?` returning the char position immediately after the codepoint whose final byte is at `byte` (or `null` if out of range)
- [x] 2.2 Handle surrogate pairs correctly (one codepoint = 4 UTF-8 bytes + 2 UTF-16 chars)
- [x] 2.3 Handle ASCII fast path (no allocation when no multi-byte chars are present)
- [x] 2.4 Unit-test pure ASCII text: `byteToChar(n) == n` for every valid `n`
- [x] 2.5 Unit-test 2-byte UTF-8 (Latin-1 supplement, e.g. é): byte 0..1 → char 0, byte 2 → char 1
- [x] 2.6 Unit-test 3-byte UTF-8 (CJK, e.g. 中): byte 0..2 → char 0, byte 3 → char 1
- [x] 2.7 Unit-test 4-byte UTF-8 (emoji 👋): byte 0..3 → chars 0..1 (surrogate pair), byte 4 → char 2
- [x] 2.8 Unit-test combining marks: each combining mark contributes its own byte/char count
- [x] 2.9 Unit-test `byteToChar(out-of-range)` returns `null` (used by malformed-facet skipping)

## 3. Tier 1 API — `appendBlueskyText` builder primitive

- [x] 3.1 Implement `public fun AnnotatedString.Builder.appendBlueskyText(text, facets, onFacet)` in package `io.github.kikin81.atproto.compose`
- [x] 3.2 Append `text` to the builder once, up front (consumers never see partial-text builders)
- [x] 3.3 Build a `Utf8CharBoundaryTable` for `text`; for each facet, translate `(byteStart, byteEnd)` to `(startChar, endChar)` via the table
- [x] 3.4 Sort facets by `byteStart` ascending before iterating (stable, matches issue acceptance criterion)
- [x] 3.5 Skip malformed facets silently: `byteStart < 0`, `byteEnd <= byteStart`, `byteEnd > text.utf8.size`, empty `features` list, `byteToChar` returning `null`
- [x] 3.6 For each valid facet, iterate `features` and invoke `onFacet(feature, startChar, endChar, text.substring(startChar, endChar))` for each member (not just the first — a facet can carry multiple feature variants)
- [x] 3.7 KDoc the builder primitive with the byte-to-char invariant, the silent-skip contract, the sort order guarantee, and the `slice` cheap-precompute rationale
- [x] 3.8 Unit-test ASCII text + 1 mention → callback fires with correct char range and slice
- [x] 3.9 Unit-test multiple facets fire in `byteStart` ascending order
- [x] 3.10 Unit-test malformed facet (inverted range, out-of-range bytes) is silently dropped, other facets still fire
- [x] 3.11 Unit-test empty/null facets list produces a builder containing only `text` with no callbacks fired

## 4. Tier 2 API — `buildBlueskyAnnotatedString` mapper convenience

- [x] 4.1 Implement `public fun buildBlueskyAnnotatedString(text, facets, styleMapper): AnnotatedString` wrapping `buildAnnotatedString { appendBlueskyText(...) { ... } }`
- [x] 4.2 Inside the `onFacet` lambda, apply `addStyle(styleMapper(feature), startChar, endChar)`
- [x] 4.3 For `FacetLink` features: call `addLink(LinkAnnotation.Url(feature.uri), startChar, endChar)` (Compose UI 1.7+)
- [x] 4.4 For `FacetMention` features: call `addStringAnnotation(ANNOTATION_TAG_MENTION, feature.did.raw, startChar, endChar)`
- [x] 4.5 For `FacetTag` features: call `addStringAnnotation(ANNOTATION_TAG_TAG, feature.tag, startChar, endChar)`
- [x] 4.6 For `FacetFeaturesUnion.Unknown`: skip silently (no style, no annotation)
- [x] 4.7 Declare `public const val ANNOTATION_TAG_MENTION = "io.github.kikin81.atproto.mention"` and `ANNOTATION_TAG_TAG = "io.github.kikin81.atproto.tag"` as top-level public symbols
- [x] 4.8 KDoc each public symbol with the byte-mapping invariant, the `LinkAnnotation` click-handling story, and example usage from `Text(annotatedString)`
- [x] 4.9 Unit-test: mention facet for DID `did:plc:alice` → annotation tag `ANNOTATION_TAG_MENTION` carries the DID raw string at the right range
- [x] 4.10 Unit-test: link facet → `LinkAnnotation.Url` is present at the right range with the expected URI
- [x] 4.11 Unit-test: tag facet → annotation tag `ANNOTATION_TAG_TAG` carries the tag value at the right range
- [x] 4.12 Unit-test: `FacetFeaturesUnion.Unknown` is silently skipped, other facets still styled
- [x] 4.13 Unit-test: emoji-before-mention canonical regression (`"hi 👋 @alice.bsky.social"`) → mention spans the correct char range
- [x] 4.14 Unit-test: CJK + mention → correct char range
- [x] 4.15 Unit-test: combining-mark + facet → correct char range
- [x] 4.16 Unit-test: empty facets / null facets → plain `AnnotatedString(text)` with no spans

## 5. `:compose-material3` module scaffolding

- [x] 5.1 Create `:compose-material3` as a Kotlin/JVM (Android-friendly) module in `compose-material3/`, add to `settings.gradle.kts`
- [x] 5.2 Configure `compose-material3/build.gradle.kts` with `implementation(project(":compose"))` and `implementation(libs.androidx.compose.material3)`
- [x] 5.3 Wire `mavenPublishing` to publish as `io.github.kikin81.atproto:compose-material3`
- [x] 5.4 Add `compose-material3/MODULE.md` for Dokka aggregation
- [x] 5.5 Verify `./gradlew :compose-material3:compileKotlin` succeeds with the module skeleton

## 6. Tier 3 API — `rememberBlueskyAnnotatedString`

- [x] 6.1 Implement `@Composable public fun rememberBlueskyAnnotatedString(text, facets, linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary)): AnnotatedString` in package `io.github.kikin81.atproto.compose.material3`
- [x] 6.2 Inside the composable, use `remember(text, facets, linkStyle) { buildBlueskyAnnotatedString(text, facets) { linkStyle } }`
- [x] 6.3 KDoc the composable with the artifact-split rationale, the `linkStyle` override contract, and the recomposition keys
- [ ] 6.4 ~~Unit-test default `linkStyle` reads `MaterialTheme.colorScheme.primary`.~~ DEFERRED — the Tier 3 composable is a 4-line wrapper. Testing the `MaterialTheme` lookup needs Robolectric / `compose-ui-test` infra not currently in the project. Byte-mapping correctness (the actual logic worth testing) is fully covered by Tier 1/Tier 2 tests in `:compose`. Verified end-to-end via the sample app.
- [ ] 6.5 ~~Unit-test consumer-supplied `linkStyle` overrides the Material default.~~ DEFERRED, same reason.

## 7. Sample app integration

- [x] 7.1 Add `implementation(project(":compose-material3"))` to `samples/android/build.gradle.kts`
- [x] 7.2 Locate the sample's post-rendering `@Composable` (the feed-item composable that renders post text) and replace its facet handling with a call to `rememberBlueskyAnnotatedString`
- [x] 7.3 Render the result via `Text(annotatedString, ...)` so `LinkAnnotation.Url` clicks open the OS browser by default
- [ ] 7.4 Optional: in a second sample screen or a side-by-side block, show Tier 2 usage with a custom `styleMapper` that reads a non-Material brand color, demonstrating the artifact-split's value
- [x] 7.5 Manually verify on emulator: post text renders correctly, mention/link/tag styling applies, link tap opens browser, emoji-before-mention regression is not present
- [ ] 7.6 ~~Update the sample's README section for post rendering to point at the helper.~~ DEFERRED — the sample README doesn't currently have a "post rendering" section to update. Root README's modules table now mentions the sample uses `:compose-material3` for facet rendering.

## 8. Documentation and release wiring

- [x] 8.1 Add `compose/README.md` with the API tier overview, dependency table, minimum Compose UI version, and example snippets for each tier
- [x] 8.2 Add `compose-material3/README.md` (short — points back to `:compose` for the API and shows the one-line Material3 usage)
- [x] 8.3 Verify `./gradlew :dokkaGeneratePublicationHtml` aggregates both new modules into the published API docs site
- [x] 8.4 Update root `README.md`'s "Modules" or "Maven Central" section to list both new artifact coordinates
- [x] 8.5 Update `MODULE.md` (root) Dokka aggregation list if it enumerates modules

## 9. Final verification

- [x] 9.1 `./gradlew build` (full build + tests + spotless) passes locally
- [x] 9.2 `./gradlew :compose:test :compose-material3:test` passes (canonical regression cases all green)
- [x] 9.3 `./gradlew :samples:android:installDebug` builds and runs on an emulator with the sample feed rendering through the new helper
- [x] 9.4 Confirm the `:compose` POM published locally (`./gradlew publishToMavenLocal`) does NOT list `androidx.compose.material3:material3` as a transitive dependency
- [ ] 9.5 Open PR with conventional-commit `feat(compose): facet → AnnotatedString helper for Bluesky post text` referencing GitHub issue #20; ensure semantic-release will cut a minor version bump on merge
