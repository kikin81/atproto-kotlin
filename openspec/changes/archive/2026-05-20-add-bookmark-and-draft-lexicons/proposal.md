## Why

The downstream `kikin81/nubecita` client wants two next-up retention features users have come to expect from Bluesky clients: **bookmarks** (saving posts for later) and **drafts** (composing posts across devices/sessions). Both are first-class server-supported features in `bluesky-social/atproto@main` (lexicon schemas live under `lexicons/app/bsky/bookmark/` and `lexicons/app/bsky/draft/`), but the SDK doesn't yet generate clients for them — they're not in `generator/lexicons.json`. Adding the NSIDs lets nubecita build a bookmarks list and persistent-drafts UX immediately, with no runtime work in this SDK.

## What Changes

- Append 9 NSIDs to `generator/lexicons.json`:
  - `app.bsky.bookmark.defs` (shared types — `bookmarkView`)
  - `app.bsky.bookmark.createBookmark`
  - `app.bsky.bookmark.deleteBookmark`
  - `app.bsky.bookmark.getBookmarks`
  - `app.bsky.draft.defs` (shared types — `draft`)
  - `app.bsky.draft.createDraft`
  - `app.bsky.draft.deleteDraft`
  - `app.bsky.draft.getDrafts`
  - `app.bsky.draft.updateDraft`
- Run `npx lex install` to fetch the JSON schemas from upstream and pin CIDs. The first run must omit `--ci` because that flag verifies an already-pinned manifest; once new NSIDs are pinned, subsequent CI builds can use `npx lex install --ci` for verification.
- Regenerate via `:generator:generateModels` — emits `BookmarkService`, `DraftService`, and the `BookmarkView` / `Draft` model types under the existing `io.github.kikin81.atproto.app.bsky.{bookmark,draft}` packages.
- Refresh `:models:apiDump` — additive only (new public classes + accessors).
- Add 2 MockEngine smoke tests in `:models` proving wire-shape: one query (`BookmarkService.getBookmarks`) and one procedure (`DraftService.createDraft`).
- Release as a **minor bump** (v8.0.0 → v8.1.0). Purely additive — no `BREAKING CHANGE:` footer, no breaking-changes doc.

## Capabilities

### New Capabilities

(none — extends the existing `lexicon-codegen` capability with two added scope requirements)

### Modified Capabilities

- `lexicon-codegen`: Adds requirements stating that the generated SDK ships `BookmarkService` (with `getBookmarks` / `createBookmark` / `deleteBookmark`) and `DraftService` (with `getDrafts` / `createDraft` / `deleteDraft` / `updateDraft`) bound to their respective `app.bsky.bookmark.*` and `app.bsky.draft.*` lexicon NSIDs. No change to how codegen *works* — these are pure scope additions verifiable through the existing emission pipeline.

## Impact

- **`:generator`**: 9 manifest entries added to `lexicons.json` + corresponding CIDs after `lex install`. No code changes.
- **`:models`**: 2 new auto-generated service classes + their data models (`BookmarkView`, `Draft`, paginated `getBookmarks` response, etc.). Public API surface grows additively.
- **Tests**: 2 new MockEngine smoke tests in `:models` (query + procedure) to catch wire-shape regressions beyond what GoldenFileTest covers.
- **Binary compatibility**: `:models:apiCheck` will require a refreshed `.api` file. Strictly additive — minor version bump, not major.
- **Release notes**: One sentence noting that bookmark/draft client models ship in v8.1.0 and that server-side production deployment of these endpoints on `bsky.social` may still be rolling out; runtime XRPC calls may surface `XrpcError` until the server side is live.
- **Downstream consumer guidance** (for nubecita): the `DraftService` exposes a remote-record API; offline-first behaviour should be implemented at the consumer layer (DataStore/Room cache wrapping the service) so users can compose drafts without network. Out of scope for this SDK.
- **Risk: Atmosphere lex-resolver**: Per repo memory, a single broken NSID can abort the whole `npx lex install` batch (chat.bsky.convo.getConvoMembers precedent). Mitigation: install everything else first, add the broken one separately. Documented in implementation tasks.
- **Out of scope**: sample Android UI for either feature; helper extensions on the generated services (presumptive ergonomics); offline-first wrappers (downstream concern).
