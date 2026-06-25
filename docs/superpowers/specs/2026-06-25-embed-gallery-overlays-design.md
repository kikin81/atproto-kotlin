# Design: Add `app.bsky.embed.gallery` + `getEmbedExternalView` via fully-wired overlays

**Date:** 2026-06-25
**Status:** Proposed — awaiting review
**Approach:** B (additive overlays + union wiring)

## Problem

Bluesky has shipped the **10-image gallery** embed (`app.bsky.embed.gallery`) and the
`app.bsky.embed.getEmbedExternalView` query to production. Real posts carry gallery embeds
today and the official app + third-party iOS clients render them — the **data plane is live**.

However, neither schema is published as a `com.atproto.lexicon.schema` **record** on the
authority DID (`did:plc:4v4y5r3lwsbtmsxhile2ljac`). Verified 2026-06-25: that DID hosts 167
schema records; its `app.bsky.embed.*` set is exactly
`{defs, external, images, record, recordWithMedia, video}` — gallery and getEmbedExternalView
are absent. Because our SDK's codegen input comes from `npx lex install` (which resolves those
schema *records*, not GitHub), the normal path returns `RecordNotFound` and cannot generate
these types. See memory `lexicon-resolution-network-vs-github`.

This is precisely the gap the `overlay-lexicons.json` mechanism exists for: vendor a schema
from a pinned GitHub commit so codegen can emit typed support that matches live network data,
flagged for retirement once the schema record is published.

The drift issue (#128) listed these under `app.bsky.embed.* (7)`, but that report compares
against GitHub `main` (not the network) and over-lists types we already generate; the true
delta is only these two NSIDs.

## Goal

Emit typed, fully-wired Kotlin support for gallery and getEmbedExternalView:
- `EmbedGallery` / `EmbedGalleryView` model classes.
- `gallery` as a **typed member** of every union that accepts it (so authoring and reading a
  gallery post is typesafe, not relegated to the `Unknown` open-union fallback).
- The `getEmbedExternalView` XRPC query method on the embed service.

## Approach: B — additive overlays + union wiring

Vendor five files into `generator/overlay-lexicons/` **verbatim** from a single pinned
`bluesky-social/atproto@main` commit, with five matching entries in
`generator/overlay-lexicons.json`. Overlays merge into the corpus by NSID with **overlay-wins**
semantics (shadowing an installed NSID logs a stderr WARN), so the three core files are
overridden in place.

| Overlay file | Role | Effect on generated code |
|---|---|---|
| `app/bsky/embed/gallery.json` | **new** standalone type | emits `EmbedGallery`, `EmbedGalleryView` |
| `app/bsky/embed/getEmbedExternalView.json` | **new** query | emits XRPC query method on the embed service |
| `app/bsky/feed/post.json` | **shadow** | `+ gallery` in post embed authoring union |
| `app/bsky/embed/recordWithMedia.json` | **shadow** | `+ gallery` / `gallery#view` in both media unions |
| `app/bsky/feed/defs.json` | **shadow** | `+ gallery#view` in postView embed union |

### Why full-verbatim vendoring is safe here

Diff of our network-resolved copies vs upstream (2026-06-25) shows the three shadow files
differ from ours by **only** the gallery union members, plus a `$type` field the network adds
that GitHub-vendored overlays correctly omit (matching the existing `getConvoMembers`
precedent). So vendoring verbatim changes generated code by exactly "gallery added to three
unions" — no unrelated churn.

Ref-resolution check (2026-06-25): the only refs in the three upstream files absent from our
corpus are the gallery ones, which the gallery overlay supplies. Nothing dangles.

### Union sites wired

| Site | Authoring union | View union |
|---|---|---|
| `app.bsky.feed.post` | `+ app.bsky.embed.gallery` | — |
| `app.bsky.embed.recordWithMedia` | `+ app.bsky.embed.gallery` | `+ app.bsky.embed.gallery#view` |
| `app.bsky.feed.defs` (postView) | — | `+ app.bsky.embed.gallery#view` |

Concretely, each generated open-union serializer gains a typed branch, e.g. in
`RecordWithMediaMediaUnion`:
```kotlin
"app.bsky.embed.gallery" -> Gallery.serializer()   // was: else -> null (Unknown fallback)
```
and `EmbedGallery` comes to implement the union interface.

## Manifest semantics (two retire conditions)

`overlay-lexicons.json` entries must distinguish two cases, because `DetectStaleOverlays`
keys off a per-overlay publishable verdict:

1. **New files** (`gallery.json`, `getEmbedExternalView.json`) — classic
   `removeWhenPublished: true`; retire when the NSID's schema record appears on-network.
2. **Shadow files** (`post.json`, `recordWithMedia.json`, `feed/defs.json`) — these are
   **already published** on-network (the older, gallery-less version); they will never go
   `RecordNotFound`. Their retire trigger is "on-network version **includes gallery**," not
   "exists." The `reason` field must state this explicitly so the overlay isn't mis-flagged
   as stale and whoever retires later knows the real condition.

Each entry pins `source` + `commit` (`85c25efde14c1d0383f32bba21b21bdad2ae2619`) +
`vendoredAt: 2026-06-25`.

New files are overlay-only — they do **not** need entries in `lexicons.json` (confirmed by the
`getConvoMembers` precedent, which is overlay-only).

## Open item to resolve during implementation

The `generator/overlay-lexicons/` **directory does not currently exist on disk**, yet
`overlay-lexicons.json` already lists `getConvoMembers`. The generator treats a missing
overlay dir as an empty overlay set (`takeIf { it.exists() }`), so overlays are presently a
no-op. Implementation must determine whether the `getConvoMembers` vendored file is expected
to exist (and is simply missing/retired) or whether the dir is created fresh by this change —
and ensure we don't accidentally resurrect or orphan it.

## Testing / verification

- Run `:generator:generateModels`; confirm the three union serializers gain a typed `gallery`
  branch, `EmbedGallery`/`EmbedGalleryView` are emitted, and the `getEmbedExternalView` query
  method appears on the embed service. Confirm only the expected files change.
- Regenerate golden files if any golden fixture covers these embeds
  (`GOLDEN_UPDATE=1 ./gradlew :generator:test --tests '*GoldenFileTest*'`).
- Confirm `DetectStaleOverlaysTest` passes; extend it if it asserts on manifest contents.
- Build `:models` and run `:runtime:jvmTest` to confirm compilation and open-union round-trip.
- Note iOS sim test-link is unavailable on this machine (CLT-only); use targeted JVM
  verification (memory `ios-sim-tests-need-xcode`).

## Side effects

Once vendored, the drift detector excludes overlay-handled NSIDs, so gallery and
getEmbedExternalView drop off issue #128's list.

## Rejected alternatives

- **A — additive overlays only (no union wiring):** emits the model classes but leaves gallery
  in the `Unknown` fallback for all unions; can't author a typed gallery post. Rejected because
  gallery is live in production *now* and typed authoring is the whole point.
- **C — getEmbedExternalView only:** skips gallery; doesn't meet the goal.
- **D — wait for network publication:** zero maintenance, but blocks typed gallery support
  indefinitely while real gallery posts already flow through production. Rejected for the same
  reason as A.
