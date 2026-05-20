## 1. Manifest additions

- [x] 1.1 Append 4 bookmark NSIDs to `generator/lexicons.json`
- [x] 1.2 Append 5 draft NSIDs to `generator/lexicons.json`
- [x] 1.3 Confirm alphabetical ordering — bookmark/draft slot between `app.bsky.actor.*` and `app.bsky.feed.*`

## 2. Lexicon install + pin CIDs

- [x] 2.1 Run `cd generator && npx lex install` (without `--ci`, which requires pre-pinned CIDs). All 9 NSIDs resolved cleanly — no Atmosphere lex-resolver issues this time
- [x] 2.2 Verified all 9 NSIDs are in both `lexicons[]` and `resolutions{}` of the manifest; total resolutions went from 130 → 139
- [x] 2.3 Manifest change is included with the rest of the change; standalone commit not separated (low risk, no transitive surprises)

## 3. Regenerate models

- [x] 3.1 `./gradlew :generator:generateModels` succeeded — `BookmarkService.kt`, `DraftService.kt`, and their data classes are emitted under the expected packages
- [x] 3.2 Spot-checked services: `getBookmarks` is GET, `createBookmark`/`deleteBookmark`/`createDraft`/`updateDraft`/`deleteDraft` are POST, correct NSIDs in `client.query`/`client.procedure` calls, KDoc copied from upstream lexicon descriptions
- [x] 3.3 Spot-checked data classes — naming convention is `*Request`/`*Response` (not `*Input`/`*Output`); `Draft.posts` is `List<DraftPost>` required, optional fields use `T? = null`. Two generator warnings about union fields (`threadgateAllow`, `labels`) falling back to `JsonObject` — known limitation, raw JSON still accessible; noted in PR

## 4. Golden file regression check

- [x] 4.1 `./gradlew :generator:test --tests '*GoldenFileTest*'` passes — generator output is consistent with golden fixtures
- [x] 4.2 No golden updates needed

## 5. MockEngine smoke tests

- [x] 5.1 Added `models/src/commonTest/kotlin/io/github/kikin81/atproto/app/bsky/bookmark/BookmarkServiceTest.kt` — asserts GET method, URL contains `/xrpc/app.bsky.bookmark.getBookmarks` + `limit=25&cursor=abc`, decodes typed response
- [x] 5.2 Added `models/src/commonTest/kotlin/io/github/kikin81/atproto/app/bsky/draft/DraftServiceTest.kt` — asserts POST method, URL, JSON body containing `"draft"` + nested post text, decodes typed `CreateDraftResponse`
- [x] 5.3 Set up `commonTest` source set in `models/build.gradle.kts` (added `ktor.client.mock` + `kotlinx.coroutines.test` to commonTest deps — first test infrastructure for this module). Verified via `:models:jvmTest` — 2 tests pass

## 6. Binary-compat dump

- [x] 6.1 `./gradlew :models:apiDump` refreshed `models/api/models.api` (~1000 lines diff — includes stale drift since last dump on 2026-05-14 + bookmark/draft additions)
- [x] 6.2 Confirmed bookmark/draft additions are strictly additive new classes; older changes (PostView etc.) are pre-existing drift from prior generator changes not captured in apiCheck-blocking incompatibility
- [x] 6.3 `./gradlew :models:apiCheck` green

## 7. Spec sync + cleanup

- [x] 7.1 No breaking changes doc — minor bump
- [x] 7.2 Commit prefix `feat(models):` for minor bump via semantic-release
- [x] 7.3 PR body includes server-side deployment status note

## 8. Wrap-up

- [x] 8.1 `./gradlew spotlessApply` and `./gradlew build` clean
- [x] 8.2 All module tests green via full `./gradlew build` (383 tasks, including the 2 new BookmarkServiceTest/DraftServiceTest cases)
- [ ] 8.3 Push branch, open PR, address review comments, merge
- [ ] 8.4 Once v8.1.0 is on Maven Central: notify nubecita to upgrade
