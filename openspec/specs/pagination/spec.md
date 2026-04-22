## ADDED Requirements

### Requirement: Runtime SHALL provide a generic cursor-pagination Flow builder

The `:runtime` module SHALL provide a `paginate()` function
that accepts a fetcher lambda, a cursor extractor, and an items extractor,
and returns a `Flow<T>` that lazily fetches pages as the collector
consumes items.

```kotlin
fun <R, T> paginate(
    fetch: suspend (cursor: String?) -> R,
    getCursor: (R) -> String?,
    getItems: (R) -> List<T>,
): Flow<T>
```

The Flow SHALL:
- Start with `cursor = null` (first page)
- After each page, extract the cursor via `getCursor(response)`
- Emit each item from `getItems(response)` individually
- Terminate when `getCursor` returns `null` (end of feed)
- Not prefetch — pages are fetched only when the collector is ready

#### Scenario: Paginating a three-page feed

- **WHEN** a consumer collects from a `paginate()` Flow backed by a
  fetcher that returns 3 pages of 10 items each (with cursors
  `"c1"`, `"c2"`, `null`)
- **THEN** the Flow emits 30 items total
- **AND** the fetcher is called exactly 3 times with cursors
  `null`, `"c1"`, `"c2"`
- **AND** the Flow terminates naturally after the third page

#### Scenario: Empty first page terminates immediately

- **WHEN** the fetcher returns a response with `cursor = null` and
  an empty items list on the first call
- **THEN** the Flow emits zero items and terminates

#### Scenario: Collector cancellation stops fetching

- **WHEN** a consumer takes only 5 items from a Flow backed by pages
  of 10 items each
- **THEN** the fetcher is called at most once
- **AND** no subsequent pages are fetched after cancellation

#### Scenario: Repeated cursor terminates the Flow

- **WHEN** the fetcher returns the same cursor value it received
  (indicating a buggy PDS that doesn't advance)
- **THEN** the Flow terminates without entering an infinite loop

### Requirement: Runtime SHALL provide a page-level pagination Flow

The `:runtime` module SHALL provide a `paginatePages()`
function with the same signature as `paginate()` but returning
`Flow<List<T>>` where each emission is one full page of items. This
avoids excessive state updates when collecting into a UI StateFlow.

#### Scenario: Page-level Flow emits complete pages

- **WHEN** a consumer collects from a `paginatePages()` Flow backed
  by 3 pages of 10 items each
- **THEN** the Flow emits exactly 3 `List<T>` values, each containing
  10 items

### Requirement: Generator SHALL emit Flow extensions for paginated queries

The code generator SHALL emit a `*Flow()` extension function on the
Service class for every query whose Response type contains a
`cursor: String?` property and whose Request type also contains a
`cursor: String?` property.

The generated extension SHALL:
- Accept the same Request type as the original method (minus cursor)
- Return a `Flow<T>` where `T` is the list item type
- Wire the `paginate()` primitive with the correct cursor and items
  extraction

#### Scenario: Generated timelineFlow extension

- **WHEN** the generator processes `app.bsky.feed.getTimeline` (which
  has `GetTimelineRequest.cursor` and `GetTimelineResponse.cursor`)
- **THEN** it emits two extension functions on `FeedService`:

```kotlin
fun FeedService.timelineFlow(
    request: GetTimelineRequest = GetTimelineRequest(),
): Flow<FeedViewPost> = paginate(
    fetch = { cursor -> getTimeline(request.copy(cursor = cursor)) },
    getCursor = { it.cursor },
    getItems = { it.feed },
)

fun FeedService.timelinePageFlow(
    request: GetTimelineRequest = GetTimelineRequest(),
): Flow<List<FeedViewPost>> = paginatePages(
    fetch = { cursor -> getTimeline(request.copy(cursor = cursor)) },
    getCursor = { it.cursor },
    getItems = { it.feed },
)
```

#### Scenario: Non-paginated query gets no Flow extension

- **WHEN** the generator processes a query whose Response has no
  `cursor: String?` property
- **THEN** no Flow extension is emitted for that query

### Requirement: Flow extensions SHALL be KMP-compatible

The generated Flow extensions and the `paginate()` primitive SHALL
compile for both JVM and iOS targets. They SHALL NOT depend on
Android Paging 3, AndroidX, or any platform-specific library.

#### Scenario: iOS compilation

- **WHEN** `./gradlew :runtime:compileKotlinIosArm64` runs
  (on a macOS host)
- **THEN** the pagination code compiles without errors
