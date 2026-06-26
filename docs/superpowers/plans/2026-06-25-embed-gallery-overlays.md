# embed.gallery + getEmbedExternalView Overlays Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add typed Kotlin support for `app.bsky.embed.gallery` (10-image gallery) and `app.bsky.embed.getEmbedExternalView`, fully wired into the three embed unions, via the overlay mechanism.

**Architecture:** Vendor five lexicon JSON files verbatim from `bluesky-social/atproto@main` into `generator/overlay-lexicons/` (two new schemas + three shadowed core files that add gallery to their unions), register them in `generator/overlay-lexicons.json`, regenerate `:models`, and refresh the binary-compat API dump. The generator merges overlays by NSID with overlay-wins semantics.

**Tech Stack:** Kotlin Multiplatform, KotlinPoet codegen, kotlinx.serialization, kotlinx binary-compatibility-validator, JUnit5/kotlin.test, Gradle.

---

## Background (read before starting)

- **Why overlays, not `lexicons.json`:** gallery + getEmbedExternalView are live in the production **data plane** but their `com.atproto.lexicon.schema` **records are unpublished** on-network (`RecordNotFound`), so `npx lex install` can't fetch them. Overlays vendor the schema from GitHub. See `docs/superpowers/specs/2026-06-25-embed-gallery-overlays-design.md` and memory `lexicon-resolution-network-vs-github`.
- **How codegen consumes overlays:** `generator/src/main/kotlin/.../Main.kt` parses the overlay dir (3rd arg) and merges by `id` with overlay-wins; shadowing a corpus NSID logs a stderr WARN. Generated source lands in `models/build/generated/...` (gitignored — regenerated each build, **not** committed).
- **How staleness tracking consumes overlays:** `DetectStaleOverlays.kt` reads `overlay-lexicons.json` and the vendored file at `generator/overlay-lexicons/<nsid-with-slashes>.json`. It (a) compares the vendored file to upstream **`main`** via key-sorted JSON canonicalization (drift) and (b) reads a publishable verdict. `isStale = (publishable && removeWhenPublished) || drift != InSync`.
- **Two retire conditions (drives `removeWhenPublished`):**
  - New files (`gallery`, `getEmbedExternalView`) → `removeWhenPublished: true` (retire once their schema record publishes on-network).
  - Shadow files (`feed.post`, `embed.recordWithMedia`, `feed.defs`) → `removeWhenPublished: false`. They are **already publishable** (the older, gallery-less version resolves on-network), so `true` would wrongly flag them for immediate retirement. They stay pinned; drift is the only signal, and final retirement is a manual call once the network's published copy includes gallery.
- **Vendored file format:** GitHub style — `{"lexicon":1,"id":"...","defs":{...}}`, 2-space indent, **no** `$type` field (the network adds `$type`; GitHub-vendored overlays omit it, matching `generator/overlay-lexicons/chat/bsky/convo/getConvoMembers.json`). Canonicalization makes the `$type` difference irrelevant to drift.
- **Vendor from current `main`:** upstream `main` moves. Drift compares against `main`, so vendor each file from the current `main` and pin the manifest `commit` to the current `main` sha at vendoring time. Do **not** reuse a stale sha.
- **API dump:** `:models` is published; CI runs `apiCheck` against `models/api/models.api`. New public classes change the API, so `./gradlew apiDump` and committing the updated `.api` file is mandatory.

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `generator/overlay-lexicons/app/bsky/embed/gallery.json` | Create | Vendored gallery schema (new type) |
| `generator/overlay-lexicons/app/bsky/embed/getEmbedExternalView.json` | Create | Vendored query schema (new type) |
| `generator/overlay-lexicons/app/bsky/feed/post.json` | Create | Shadow: adds gallery to post embed union |
| `generator/overlay-lexicons/app/bsky/embed/recordWithMedia.json` | Create | Shadow: adds gallery/gallery#view to media unions |
| `generator/overlay-lexicons/app/bsky/feed/defs.json` | Create | Shadow: adds gallery#view to postView union |
| `generator/overlay-lexicons.json` | Modify | Five manifest entries (provenance + retire flags) |
| `models/api/models.api` | Modify (via `apiDump`) | Refreshed public-API signatures |
| `models/src/commonTest/kotlin/io/github/kikin81/atproto/app/bsky/embed/EmbedGalleryUnionTest.kt` | Create | Round-trip proof that gallery is a typed union member |
| `docs/superpowers/specs/2026-06-25-embed-gallery-overlays-design.md` | Modify | Correct stale open-item + commit sha |

---

## Task 1: Vendor overlay files and register them in the manifest

**Files:**
- Create: the five files under `generator/overlay-lexicons/...` (see table)
- Modify: `generator/overlay-lexicons.json`

- [ ] **Step 1: Capture the current upstream main sha**

```bash
cd <repo-root>
SHA=$(gh api repos/bluesky-social/atproto/commits/main -q '.sha')
echo "$SHA"   # record this; used for every manifest `commit` field below
```
Expected: a 40-char sha (e.g. `fdc8ca8...`). Save it as `$SHA` for the rest of this task.

- [ ] **Step 2: Vendor the five files verbatim from main**

```bash
cd <repo-root>
for p in embed/gallery embed/getEmbedExternalView embed/recordWithMedia feed/post feed/defs; do
  dest="generator/overlay-lexicons/app/bsky/$p.json"
  mkdir -p "$(dirname "$dest")"
  gh api "repos/bluesky-social/atproto/contents/lexicons/app/bsky/$p.json?ref=$SHA" \
    -q '.content' | base64 -d > "$dest"
  echo "wrote $dest"
done
```
Expected: five "wrote …" lines, no errors.

- [ ] **Step 3: Sanity-check the vendored content**

```bash
cd <repo-root>
grep -l '"app.bsky.embed.gallery' generator/overlay-lexicons/app/bsky/feed/post.json \
  generator/overlay-lexicons/app/bsky/embed/recordWithMedia.json
grep -l 'gallery#view' generator/overlay-lexicons/app/bsky/feed/defs.json
python3 -c "import json,glob; [json.load(open(f)) for f in glob.glob('generator/overlay-lexicons/app/bsky/**/*.json',recursive=True)]; print('all parse OK')"
grep -L '\$type' generator/overlay-lexicons/app/bsky/embed/gallery.json   # should print the path (no \$type)
```
Expected: `post.json` + `recordWithMedia.json` contain `app.bsky.embed.gallery`; `defs.json` contains `gallery#view`; "all parse OK"; gallery.json has no `$type`.

- [ ] **Step 4: Add the five manifest entries**

Replace the `overlays` array in `generator/overlay-lexicons.json` so it contains the existing `getConvoMembers` entry plus the five below. Set every `commit` to the `$SHA` from Step 1 and `vendoredAt` to today (`2026-06-25`). Note the differing `removeWhenPublished` values and `reason` text.

```json
{
  "version": 1,
  "overlays": [
    {
      "nsid": "chat.bsky.convo.getConvoMembers",
      "source": "https://github.com/bluesky-social/atproto/blob/3cb156907a15f3f22a1be734f82b3b0c855b4da0/lexicons/chat/bsky/convo/getConvoMembers.json",
      "commit": "3cb156907a15f3f22a1be734f82b3b0c855b4da0",
      "vendoredAt": "2026-06-20",
      "reason": "Served by appview + present in bluesky-social/atproto main, but not yet published on-network (npx lex install -> RecordNotFound). Retire once publishable.",
      "removeWhenPublished": true
    },
    {
      "nsid": "app.bsky.embed.gallery",
      "source": "https://github.com/bluesky-social/atproto/blob/<SHA>/lexicons/app/bsky/embed/gallery.json",
      "commit": "<SHA>",
      "vendoredAt": "2026-06-25",
      "reason": "10-image gallery embed. Live in the production data plane but its com.atproto.lexicon.schema record is unpublished on-network (npx lex install -> RecordNotFound). Retire once publishable.",
      "removeWhenPublished": true
    },
    {
      "nsid": "app.bsky.embed.getEmbedExternalView",
      "source": "https://github.com/bluesky-social/atproto/blob/<SHA>/lexicons/app/bsky/embed/getEmbedExternalView.json",
      "commit": "<SHA>",
      "vendoredAt": "2026-06-25",
      "reason": "Enhanced external-embed resolver query. Present in bluesky-social/atproto main but its schema record is unpublished on-network (npx lex install -> RecordNotFound). Retire once publishable.",
      "removeWhenPublished": true
    },
    {
      "nsid": "app.bsky.feed.post",
      "source": "https://github.com/bluesky-social/atproto/blob/<SHA>/lexicons/app/bsky/feed/post.json",
      "commit": "<SHA>",
      "vendoredAt": "2026-06-25",
      "reason": "PINNED shadow (removeWhenPublished=false). This NSID is already published on-network, so it will never read as not-yet-publishable; we vendor it only to add app.bsky.embed.gallery to the embed union ahead of the network publishing that change. RE-VENDOR on drift; retire MANUALLY once the on-network post.json includes gallery.",
      "removeWhenPublished": false
    },
    {
      "nsid": "app.bsky.embed.recordWithMedia",
      "source": "https://github.com/bluesky-social/atproto/blob/<SHA>/lexicons/app/bsky/embed/recordWithMedia.json",
      "commit": "<SHA>",
      "vendoredAt": "2026-06-25",
      "reason": "PINNED shadow (removeWhenPublished=false). Already published on-network; vendored only to add app.bsky.embed.gallery / gallery#view to the media unions ahead of the network. RE-VENDOR on drift; retire MANUALLY once the on-network copy includes gallery.",
      "removeWhenPublished": false
    },
    {
      "nsid": "app.bsky.feed.defs",
      "source": "https://github.com/bluesky-social/atproto/blob/<SHA>/lexicons/app/bsky/feed/defs.json",
      "commit": "<SHA>",
      "vendoredAt": "2026-06-25",
      "reason": "PINNED shadow (removeWhenPublished=false). Already published on-network; vendored only to add app.bsky.embed.gallery#view to the postView embed union ahead of the network. RE-VENDOR on drift; retire MANUALLY once the on-network copy includes gallery#view.",
      "removeWhenPublished": false
    }
  ]
}
```
Replace every `<SHA>` with the Step 1 value.

- [ ] **Step 5: Validate the manifest parses**

```bash
cd <repo-root>
python3 -c "import json;d=json.load(open('generator/overlay-lexicons.json'));print('overlays:',[o['nsid'] for o in d['overlays']]);assert {o['nsid'] for o in d['overlays']} >= {'app.bsky.embed.gallery','app.bsky.embed.getEmbedExternalView','app.bsky.feed.post','app.bsky.embed.recordWithMedia','app.bsky.feed.defs'};print('OK')"
```
Expected: lists 6 nsids, prints `OK`.

- [ ] **Step 6: Regenerate models and verify the wiring**

```bash
cd <repo-root>
./gradlew :generator:generateModels
gen=models/build/generated/source/lexicon/commonMain/kotlin/io/github/kikin81/atproto/app/bsky/embed
ls $gen/Gallery*.kt
grep -c '"app.bsky.embed.gallery" -> Gallery.serializer()' $gen/RecordWithMediaMediaUnion.kt
grep -rc '"app.bsky.embed.gallery#view" -> GalleryView.serializer()' $gen/RecordWithMediaViewMediaUnion.kt
grep -rl 'gallery' models/build/generated/source/lexicon/commonMain/kotlin/io/github/kikin81/atproto/app/bsky/feed/ | head
find models/build/generated -iname "*GetEmbedExternalView*"
```
Expected: `Gallery.kt`/`GalleryView.kt` exist; the two `grep -c` print `1`; the feed package references gallery in its post embed union; a `getEmbedExternalView` artifact exists. The generator log should show a `WARN overlay shadows installed lexicon` line for `app.bsky.feed.post`, `app.bsky.embed.recordWithMedia`, and `app.bsky.feed.defs` (expected — those are the intentional shadows).

- [ ] **Step 7: Commit**

```bash
cd <repo-root>
git add generator/overlay-lexicons/ generator/overlay-lexicons.json
git commit -m "feat(models): vendor embed.gallery + getEmbedExternalView overlays

Add gallery (10-image) + getEmbedExternalView schemas plus pinned shadows of
feed.post/embed.recordWithMedia/feed.defs to wire gallery into all three embed
unions. Schemas are live in the data plane but unpublished on-network, so the
overlay mechanism bridges the gap. Shadows use removeWhenPublished=false.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Round-trip test proving gallery is a typed union member

**Files:**
- Create: `models/src/commonTest/kotlin/io/github/kikin81/atproto/app/bsky/embed/EmbedGalleryUnionTest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package io.github.kikin81.atproto.app.bsky.embed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Proves the gallery overlay wired `app.bsky.embed.gallery` into the
 * recordWithMedia media unions as a *typed* member (not the Unknown fallback).
 */
class EmbedGalleryUnionTest {
    // Mirror the generator-emitted Json config (see runtime OpenUnionTest).
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun authoringUnion_decodesGalleryAsTypedMember() {
        val wire = """{"${'$'}type":"app.bsky.embed.gallery","items":[]}"""
        val decoded = json.decodeFromString<RecordWithMediaMediaUnion>(wire)
        assertIs<Gallery>(decoded)
    }

    @Test
    fun viewUnion_decodesGalleryViewAsTypedMember() {
        val wire = """{"${'$'}type":"app.bsky.embed.gallery#view","items":[]}"""
        val decoded = json.decodeFromString<RecordWithMediaViewMediaUnion>(wire)
        assertIs<GalleryView>(decoded)
    }

    @Test
    fun authoringUnion_encodesGalleryWithNsidDollarType() {
        val value: RecordWithMediaMediaUnion = Gallery(items = emptyList())
        val encoded = json.encodeToString(value)
        val type = json.parseToJsonElement(encoded).jsonObject["${'$'}type"]!!.jsonPrimitive.content
        assertEquals("app.bsky.embed.gallery", type)
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

```bash
cd <repo-root>
./gradlew :models:jvmTest --tests '*EmbedGalleryUnionTest*'
```
Expected: BUILD SUCCESSFUL, 3 tests passed. (If `assertIs<Gallery>` fails or it routes to `RecordWithMediaMediaUnion.Unknown`, the overlay wiring from Task 1 is wrong — fix before continuing.)

- [ ] **Step 3: If `Gallery`/`GalleryView` field names or the encode helper signature differ from generated output, align the test**

```bash
cd <repo-root>
gen=models/build/generated/source/lexicon/commonMain/kotlin/io/github/kikin81/atproto/app/bsky/embed
sed -n '1,40p' $gen/Gallery.kt
```
Expected: confirm the class is `data class Gallery(... items: List<...> ...)`. Adjust the test's constructor/encode call only if the generated signature differs; do not change the assertions.

- [ ] **Step 4: Commit**

```bash
cd <repo-root>
git add models/src/commonTest/kotlin/io/github/kikin81/atproto/app/bsky/embed/EmbedGalleryUnionTest.kt
git commit -m "test(models): gallery is a typed member of the recordWithMedia unions

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Refresh the binary-compat API dump

**Files:**
- Modify: `models/api/models.api`

- [ ] **Step 1: Confirm `apiCheck` currently fails (new public API undeclared)**

```bash
cd <repo-root>
./gradlew :models:apiCheck
```
Expected: FAIL — the report lists added classes such as `Gallery`, `GalleryView`, `GalleryImage`, `GalleryViewImage`, and the `getEmbedExternalView` types as not present in `models/api/models.api`. (If it unexpectedly PASSES, the new types aren't public/generated — revisit Task 1 Step 6.)

- [ ] **Step 2: Regenerate the API dump**

```bash
cd <repo-root>
./gradlew apiDump
git diff --stat models/api/models.api
grep -E 'Gallery|GetEmbedExternalView' models/api/models.api | head
```
Expected: `models/api/models.api` changed; grep shows the new gallery + getEmbedExternalView symbols.

- [ ] **Step 3: Verify apiCheck now passes**

```bash
cd <repo-root>
./gradlew :models:apiCheck
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd <repo-root>
git add models/api/models.api
git commit -m "chore(models): apiDump for embed.gallery + getEmbedExternalView

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Correct the design doc's resolved open-item

**Files:**
- Modify: `docs/superpowers/specs/2026-06-25-embed-gallery-overlays-design.md`

- [ ] **Step 1: Replace the stale "Open item" section**

In the spec, replace the `## Open item to resolve during implementation` section body with the resolved finding:

```markdown
## Resolved during planning

The `generator/overlay-lexicons/` directory **does exist** (it already holds
`chat/bsky/convo/getConvoMembers.json`); codegen reads the directory while the
`overlay-lexicons.json` manifest drives drift/staleness tracking. New overlay
files live at `generator/overlay-lexicons/<nsid-with-slashes>.json`. The three
shadow entries use `removeWhenPublished: false` so the staleness tool keeps
them pinned (they are already publishable on-network in their gallery-less form).
```

Also update the `## Manifest semantics` line that cites commit `85c25efde14c1d0383f32bba21b21bdad2ae2619` to read "the upstream `main` sha captured at vendoring time (see `overlay-lexicons.json`)".

- [ ] **Step 2: Commit**

```bash
cd <repo-root>
git add docs/superpowers/specs/2026-06-25-embed-gallery-overlays-design.md
git commit -m "docs: mark overlay-dir open-item resolved in gallery spec

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Full verification

- [ ] **Step 1: Generator tests (drift/stale + golden) pass**

```bash
cd <repo-root>
./gradlew :generator:test
```
Expected: BUILD SUCCESSFUL. `DetectStaleOverlaysTest` and `GoldenFileTest` pass. (Golden fixtures are independent of the real corpus/overlays, so they should be unaffected; if `GoldenFileTest` fails, inspect the diff — it likely indicates an unintended change and should be understood, not blindly `GOLDEN_UPDATE`-ed.)

- [ ] **Step 2: Models + runtime compile and test, formatting + API gate clean**

```bash
cd <repo-root>
./gradlew :models:jvmTest :runtime:jvmTest spotlessCheck :models:apiCheck
```
Expected: BUILD SUCCESSFUL across all. (Skip iOS sim targets — unavailable on this CLT-only machine per memory `ios-sim-tests-need-xcode`.)

- [ ] **Step 3: Push and update the PR**

```bash
cd <repo-root>
git push
gh pr ready 150
```
Expected: branch pushed; PR #150 flipped out of draft. Optionally tick the PR body checklist.

---

## Self-Review (completed by plan author)

- **Spec coverage:** vendor 5 files (T1) ✓; manifest with two retire conditions (T1 Step 4) ✓; union wiring verified (T1 Step 6) ✓; typed-member behavior (T2) ✓; API dump (T3) ✓; drift/golden/test verification (T5) ✓; drift-report side effect is automatic once overlay-handled. All spec sections map to a task.
- **Placeholder scan:** `<SHA>` is an explicit, instructed substitution (captured in T1 Step 1), not a TODO. No "add error handling" / "similar to" placeholders; all code and commands are concrete.
- **Type consistency:** test references `RecordWithMediaMediaUnion`, `RecordWithMediaViewMediaUnion`, `Gallery`, `GalleryView` — matching the generated names verified against existing `RecordWithMediaMediaUnion.kt` and the `Images`/`ImagesView` short-name convention. T1 Step 6 and T2 Step 3 include guards to confirm exact generated signatures before relying on them.
