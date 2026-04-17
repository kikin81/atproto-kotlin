## Overview

Add a generic `paginate()` Flow builder to `:at-protocol-runtime` and
teach the generator to emit `*Flow()` convenience extensions on Service
classes for all 18 cursor-paginated AT Protocol queries.

## Runtime: paginate()

### Location

`at-protocol-runtime/src/commonMain/kotlin/.../runtime/Pagination.kt`

### Implementation

Two variants — item-level and page-level:

```kotlin
fun <R, T> paginate(
    fetch: suspend (cursor: String?) -> R,
    getCursor: (R) -> String?,
    getItems: (R) -> List<T>,
): Flow<T> = flow {
    var currentCursor: String? = null
    do {
        val response = fetch(currentCursor)
        val nextCursor = getCursor(response)
        if (nextCursor == currentCursor) break // guard against buggy PDS
        val items = getItems(response)
        for (item in items) emit(item)
        currentCursor = nextCursor
    } while (currentCursor != null)
}

fun <R, T> paginatePages(
    fetch: suspend (cursor: String?) -> R,
    getCursor: (R) -> String?,
    getItems: (R) -> List<T>,
): Flow<List<T>> = flow {
    var currentCursor: String? = null
    do {
        val response = fetch(currentCursor)
        val nextCursor = getCursor(response)
        if (nextCursor == currentCursor) break
        emit(getItems(response))
        currentCursor = nextCursor
    } while (currentCursor != null)
}
```

The `paginatePages()` variant emits whole pages (`List<T>`) instead of
individual items. This is important for UI consumers: collecting
per-item into a Compose `StateFlow` via `.scan { acc, item -> acc + item }`
would trigger N state emissions per page, causing unnecessary
recompositions. The page-level variant lets the ViewModel accumulate
cleanly: `acc + page` = one state update per page.

Generated `*Flow()` extensions use `paginate()` (items). Generated
`*PageFlow()` extensions use `paginatePages()` (pages).

### Safety: repeated cursor guard

The `if (nextCursor == currentCursor) break` guard protects against
buggy PDS implementations in the decentralized AT Protocol network that
return the same cursor forever instead of advancing or returning null.

Key properties:
- **Lazy**: pages are fetched only when the collector pulls items
- **Cancellable**: Kotlin Flow cooperative cancellation stops fetching
  when the collector is done (e.g. `.take(n)`)
- **Safe**: repeated cursor detection prevents infinite loops
- **KMP**: uses only `kotlinx.coroutines.flow.flow` — no platform deps
- **No retry built-in**: retry is a collector concern via `.retry()` or
  `.retryWhen()` operators, keeping the primitive simple

### Dependencies

`kotlinx-coroutines-core` is already a transitive dependency via Ktor
in `:at-protocol-runtime`. No new dependencies needed.

## Generator: Flow extensions

### Detection logic

In `ServiceGenerator`, after building each query method, check:
1. Does the Request type have a `cursor: String?` property?
2. Does the Response type have a `cursor: String?` property?
3. Does the Response type have exactly one `List<*>` property (the items)?

If all three: emit a `*Flow()` extension function.

### Naming convention

Strip the verb prefix and add `Flow` suffix:
- `getTimeline` → `timelineFlow`
- `getAuthorFeed` → `authorFeedFlow`
- `searchPosts` → `searchPostsFlow`
- `listNotifications` → `listNotificationsFlow`

Rule: strip leading `get` (if present), camelCase the rest, append `Flow`.

### Items field detection

The generator inspects the Response's ObjectType properties for the
single `List<*>` field. In all 18 AT Protocol paginated responses,
there's exactly one list field alongside `cursor`:
- `GetTimelineResponse.feed: List<FeedViewPost>`
- `GetFollowersResponse.followers: List<ProfileView>`
- `SearchPostsResponse.posts: List<PostView>`
- etc.

### Generated output

For each paginated query, emit an extension function in the same file
as the Service class:

```kotlin
public fun FeedService.timelineFlow(
    request: GetTimelineRequest = GetTimelineRequest(),
): Flow<FeedViewPost> = paginate(
    fetch = { cursor -> getTimeline(request.copy(cursor = cursor)) },
    getCursor = { it.cursor },
    getItems = { it.feed },
)
```

The `request.copy(cursor = cursor)` pattern works because all generated
Request classes are data classes with a `cursor: String? = null` property.

### Edge cases

- **Multiple list fields**: If a Response has more than one `List<*>`
  property, skip Flow generation and log a warning. (Currently none
  exist in the AT Protocol lexicon.)
- **No list field**: Skip Flow generation. (Shouldn't happen for
  cursor-paginated responses.)

## Sample app: infinite scroll

### FeedViewModel changes

Replace the single `getTimeline()` call with `timelinePageFlow()`:

```kotlin
private fun loadTimeline() {
    viewModelScope.launch {
        _uiState.value = FeedUiState.Loading
        val accumulated = mutableListOf<FeedViewPost>()
        feedService.timelinePageFlow(GetTimelineRequest(limit = 25L))
            .catch { t -> _uiState.value = FeedUiState.Error(t.message.orEmpty()) }
            .collect { page ->
                accumulated.addAll(page)
                _uiState.value = FeedUiState.Loaded(accumulated.toList())
            }
    }
}
```

Using the page-level `*PageFlow()` variant ensures one state update per
page (not per item), avoiding unnecessary Compose recompositions.

### LazyColumn integration

Use `LazyColumn` with a load-more trigger when the user scrolls near
the bottom. The Flow handles cursor management; the ViewModel
accumulates items.

### Retry

Network errors surface via `Flow.catch`. The sample uses a simple
retry button that re-launches the flow. Advanced retry can use
`.retryWhen { cause, attempt -> delay(backoff); true }`.

## Testing

### Runtime unit tests

- `paginate()` with a mock fetcher returning 3 pages
- `paginate()` with empty first page (cursor = null)
- `paginate()` with `.take(n)` verifying cancellation
- `paginate()` with fetcher that throws, verifying exception propagation

### Generator tests

- Golden file update: verify `*Flow()` extensions appear in the
  generated Service class output
- Verify non-paginated queries don't get Flow extensions

## Non-goals

- **Bidirectional paging** (loading older + newer): AT Protocol cursor
  pagination is forward-only
- **Caching/persistence**: out of scope, consumer responsibility
- **Android Paging 3 integration**: consumers can wrap the Flow in a
  `PagingSource` themselves if needed
- **Rate limiting / backpressure**: the `flow { }` builder is naturally
  demand-driven; no explicit rate limiting needed
