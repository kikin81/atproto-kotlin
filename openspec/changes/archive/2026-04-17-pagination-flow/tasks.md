## 1. Runtime: paginate() primitive

- [x] 1.1 Create `Pagination.kt` in `:at-protocol-runtime` commonMain with `paginate()` (item-level) and `paginatePages()` (page-level) functions
- [x] 1.2 Add repeated cursor guard: break if `nextCursor == currentCursor` to prevent infinite loops from buggy PDS servers
- [x] 1.3 Ensure `kotlinx-coroutines-core` is in the runtime's dependencies (likely already transitive via Ktor — verify)
- [x] 1.4 Unit-test: paginate with 3 pages of mock data, verify all items emitted in order and fetcher called with correct cursors
- [x] 1.5 Unit-test: paginate with empty first page (null cursor), verify Flow terminates with zero emissions
- [x] 1.6 Unit-test: paginate with `.take(5)` on a 10-item-per-page feed, verify fetcher called at most once
- [x] 1.7 Unit-test: paginate with fetcher that throws on page 2, verify exception propagates to collector
- [x] 1.8 Unit-test: paginate with repeated cursor (fetcher returns same cursor it received), verify Flow terminates
- [x] 1.9 Unit-test: paginatePages emits `List<T>` per page (3 pages → 3 list emissions)

## 2. Generator: detect paginated queries

- [x] 2.1 In `ServiceGenerator`, add logic to detect paginated queries: Response has `cursor: String?` AND Request has `cursor: String?` AND Response has exactly one `List<*>` property
- [x] 2.2 Extract the items field name and type from the Response's single `List<*>` property
- [x] 2.3 Derive the Flow extension method name: strip leading `get` (if present), append `Flow`

## 3. Generator: emit Flow extensions

- [x] 3.1 Emit a `*Flow()` (item-level) and `*PageFlow()` (page-level) extension function on the Service class for each detected paginated query
- [x] 3.2 The extensions take the same Request type (with default if all-optional) and return `Flow<ItemType>` / `Flow<List<ItemType>>`
- [x] 3.3 The body wires `paginate()`/`paginatePages()` with `fetch = { cursor -> method(request.copy(cursor = cursor)) }`, `getCursor = { it.cursor }`, `getItems = { it.<fieldName> }`
- [x] 3.4 Add required imports: `kotlinx.coroutines.flow.Flow`, `io.github.kikin81.atproto.runtime.paginate`, `io.github.kikin81.atproto.runtime.paginatePages`

## 4. Generator: golden file updates

- [x] 4.1 Add a paginated query to the golden lexicon test corpus (synthetic query with cursor + list response)
- [x] 4.2 Regenerate golden files with `GOLDEN_UPDATE=1`
- [x] 4.3 Verify the golden output includes the `*Flow()` extension
- [x] 4.4 Verify non-paginated queries in the golden corpus do NOT get a Flow extension

## 5. Sample app: infinite scroll

- [x] 5.1 Update `FeedViewModel` to use `timelinePageFlow()` instead of single `getTimeline()` call
- [x] 5.2 Accumulate items in the StateFlow as pages arrive
- [x] 5.3 Add a load-more trigger in `FeedScreen` when the user scrolls near the bottom of the `LazyColumn`
- [x] 5.4 Handle errors from the Flow with a retry mechanism
- [x] 5.5 Manual test: scroll through the feed on device, verify new pages load automatically

## 6. Build verification

- [x] 6.1 `./gradlew :at-protocol-runtime:jvmTest` passes
- [x] 6.2 `./gradlew :at-protocol-generator:test` passes (golden files updated)
- [x] 6.3 `./gradlew :at-protocol-models:compileKotlinJvm` succeeds (generated Flow extensions compile)
- [x] 6.4 `./gradlew :samples:android:assembleDebug` builds
- [x] 6.5 `./gradlew spotlessCheck` passes
- [x] 6.6 `pre-commit run --all-files` passes
