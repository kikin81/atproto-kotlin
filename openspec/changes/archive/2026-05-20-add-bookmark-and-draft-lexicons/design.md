## Context

The SDK's generated surface is governed by `generator/lexicons.json` — a curated manifest of NSIDs the codegen pipeline emits Kotlin for. Each manifest entry carries a CID pinned at install time so codegen is reproducible. Adding new namespaces is purely a manifest exercise; the generator's behaviour (parsing, IR, naming, emission) is unchanged.

Two upstream lexicon namespaces are not yet in the manifest:
- `lexicons/app/bsky/bookmark/` (3 endpoints + 1 defs file) — saved-posts feature
- `lexicons/app/bsky/draft/` (4 endpoints + 1 defs file) — server-side draft posts

Both are first-class user features in the AT Protocol and a known target for nubecita's next launch slice.

The same pattern was used for prior namespace additions (e.g. `chat.bsky.*`); this change is mechanically identical, with the small twist that one of those installs once tripped over the upstream Atmosphere lex-resolver (`chat.bsky.convo.getConvoMembers` proof-of-existence failure), which is now a documented workflow risk.

## Goals / Non-Goals

**Goals:**
- Ship `BookmarkService` and `DraftService` as part of `:models` so nubecita can compile against typed clients today.
- Cover the wire shape with at least one query + one procedure MockEngine smoke test, beyond what `GoldenFileTest` already proves.
- Keep this release a minor bump (v8.1.0) — no source-level or binary-level breakage of existing consumer code.
- Make the manifest addition idempotent: re-running `lex install` should produce the same `lexicons.json` (same CIDs) as long as upstream doesn't republish.

**Non-Goals:**
- Sample Android UI for either feature (deferred to a separate change).
- Helper extensions on the generated services (e.g. paginated `Flow<BookmarkView>` wrappers).
- Offline-first behaviour for drafts (downstream consumer concern — DataStore/Room cache wrapping the service, not an SDK abstraction).
- Any runtime change to `:runtime` or `:oauth`.

## Decisions

### Decision 1: Single combined change, not two separate ones

**Chosen:** One OpenSpec change, one PR, one release.

**Rationale:** The two namespaces are mechanically identical to integrate — same workflow, same generator path, same test pattern. Bundling halves the review + release overhead. The two are independent enough that a problem in one (e.g. an upstream schema not yet resolvable) can be partially mitigated by shipping only the working half via the documented Atmosphere workaround.

**Alternative considered:** Two sequential changes (bookmark first, draft second). Rejected — extra ceremony for zero added value.

### Decision 2: Spec delta under `lexicon-codegen` rather than a new capability

**Chosen:** Add two `## ADDED Requirements` entries to `lexicon-codegen/spec.md`, one per namespace, each with a scenario asserting the generated service is callable.

**Rationale:** `lexicon-codegen` is the capability that owns "what the SDK exposes from upstream lexicons." Creating a brand-new capability for two namespaces would fragment the canonical spec for no benefit. The added requirements document the user-facing shape of the new clients without changing codegen behaviour.

**Alternative considered:** Create a new `bookmark-draft-support` capability. Rejected — tiny, doesn't compose with anything, future namespace adds would fragment further.

### Decision 3: MockEngine smoke tests in `:models`, not `:samples:android`

**Chosen:** Add `BookmarkServiceTest` and `DraftServiceTest` (or pick one combined file) under `models/src/test/kotlin/...` that exercise the generated XRPC clients through a `MockEngine`-backed `XrpcClient`.

**Rationale:** The tests prove generator output works correctly at the wire-shape level — independent of any Android plumbing. They live next to the generated code they cover and run in `:models:test`.

**Alternative considered:** Reuse the existing `:samples:android` test setup. Rejected — slower, drags in Android-platform deps, and the test is about the model layer, not the app.

### Decision 4: Minor version bump (v8.1.0), no breaking-changes doc

**Chosen:** Refresh `:models:apiDump`, commit, and rely on semantic-release's default minor behaviour (no `BREAKING CHANGE:` footer, no `feat!:` prefix).

**Rationale:** All API surface changes are additive — new public classes and accessors. The `kotlinx-binary-compatibility-validator` will see additions but no incompatible signature changes. Per the existing release pipeline, additive `feat:` commits produce a minor bump.

**Alternative considered:** Bundle bookmark/draft with the next breaking change to amortize the v9 cost. Rejected — would block this release indefinitely for a hypothetical future breakage; better to ship minor today.

### Decision 5: Document the Atmosphere lex-resolver workaround in the tasks file

**Chosen:** Add an explicit task that says: if `npx lex install --ci` fails for any of the 9 new NSIDs, install everything else first, pin those CIDs, then add the failing NSID(s) separately.

**Rationale:** Repo memory captured this exact failure mode for `chat.bsky.convo.getConvoMembers` previously. Putting the workaround in the tasks file means an implementer (human or agent) won't waste an hour rediscovering it.

## Risks / Trade-offs

- **[Upstream Atmosphere lex-resolver flake]** A single broken NSID in the batch aborts the whole install, leaving `lexicons.json` unmodified. → Mitigation: Decision 5 above. Documented in tasks.md.

- **[Server-side endpoints not yet deployed on bsky.social]** Consumers calling the generated clients before the production AppView/PDS rolls these out will see `XrpcError` at runtime. → Mitigation: a sentence in the release notes warning of this; nothing structural the SDK can do. Lexicons being in `bluesky-social/atproto@main` is the canonical "client-side ready" signal in this ecosystem.

- **[Generated KDoc may surface upstream schema descriptions verbatim]** If upstream descriptions reference Bluesky-specific UX (e.g. "as displayed in the iOS sheet"), the KDoc inherits that wording. → Acceptable: matches existing chat.bsky.* / com.atproto.* behaviour. Not worth a sanitization pass.

- **[Pagination shape for getBookmarks]** Returns `{ cursor, bookmarks: [bookmarkView] }`. The existing `pagination` capability already handles cursor-bearing responses generically — the bookmark client should "just work" with whatever pagination helpers consumers reach for. → No work needed; tests assert the response decodes.

## Migration Plan

1. Append 9 NSIDs to `generator/lexicons.json`.
2. `cd generator && npx lex install` — pins CIDs for the newly added NSIDs. **Note:** the first run must omit `--ci` since `--ci` verifies an already-pinned manifest and errors out when new entries lack CIDs. After the new NSIDs are pinned and committed, subsequent CI verifications can use `npx lex install --ci`. If a single NSID fails on the initial install, fall back to Decision 5's workaround.
3. `./gradlew :generator:generateModels` — emits new sources into `models/build/generated/source/lexicon/`.
4. `./gradlew :models:apiDump` — refresh the public API surface file.
5. Write 2 MockEngine smoke tests in `:models`.
6. `./gradlew :generator:test --tests '*GoldenFileTest*'` — regenerate goldens if codegen produced new output for shared paths (run with `GOLDEN_UPDATE=1` if needed).
7. `./gradlew spotlessApply build` — clean.
8. Update spec delta under `openspec/changes/add-bookmark-and-draft-lexicons/specs/lexicon-codegen/spec.md`.
9. PR + merge + semantic-release cuts v8.1.0.

**Rollback:** Pure revert. No persistent state. Downstream consumers stay on v8.0.0.

## Open Questions

- Should we backfill a generic Kotlin extension that pages `BookmarkService.getBookmarks` into a `Flow<BookmarkView>` for ergonomic consumption? Lean **no for v8.1.0** — premature; let nubecita ask for it after they wire it up. The existing `pagination` capability gives them the primitives.
- Are there any upstream Lexicon validation quirks specific to `app.bsky.draft.defs#draft` (e.g. nested unions, optional embed types) that might trip the generator? Lean **find out empirically during step 3 of the migration plan**; if so, file a separate generator-bug ticket and surface via openspec.
