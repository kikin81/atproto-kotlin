---
name: atproto-read
description: >
  Use this skill when reading data from the AT Protocol (Bluesky
  timeline, author feeds, profiles, notifications, search) using the
  kikin81/atproto-kotlin library. Covers three related techniques:
  calling a generated *Service query method, paginating cursor-based
  endpoints with the generated *PageFlow() extensions, and dispatching
  over open unions (feed reasons, post embeds, quoted records) safely
  including the *Unknown fallback arm. Use after atproto-oauth (you
  need an authenticated XrpcClient).
license: MIT (see repo LICENSE)
metadata:
  author: kikin81
  library-version: "5.0.0"
  keywords:
    - AT Protocol
    - Bluesky
    - XRPC
    - query
    - timeline
    - pagination
    - Flow
    - open union
    - embed
    - quoted record
    - decodeRecord
---

## Objective

Read data from Bluesky endpoints: call queries via generated service
classes, paginate with Flow, and safely handle the open-union types
that show up in feed responses (reasons, embeds, quoted records).

## Prerequisites

- Authenticated `XrpcClient` from `oauth.createClient()` (see
  `atproto-oauth`)
- Optional: Hilt for DI, Compose for UI

## 1. Calling a service query

Every NSID namespace has a generated `<Namespace>Service` class that
wraps an `XrpcClient`. Construct on demand — they're cheap.

```kotlin
import io.github.kikin81.atproto.app.bsky.feed.FeedService
import io.github.kikin81.atproto.app.bsky.feed.GetTimelineRequest

val client = oauth.createClient()
val response = FeedService(client).getTimeline(
    GetTimelineRequest(limit = 25L),
)
response.feed.forEach { entry ->
    println(entry.post.record)  // record is a JsonObject — see section 3
}
```

Request types are data classes with sensible defaults. Use named
arguments — generated classes can have many fields and positional
ordering is not stable.

**Common services:**
- `FeedService` — `getTimeline`, `getAuthorFeed`, `getFeed`,
  `getPostThread`, `searchPosts`, `getLikes`, `getRepostedBy`
- `ActorService` — `getProfile`, `getProfiles`, `searchActors`,
  `getPreferences`
- `GraphService` — `getFollowers`, `getFollows`, `getBlocks`, `getMutes`
- `NotificationService` — `listNotifications`, `getUnreadCount`
- `RepoService` (`com.atproto.repo`) — used for writes, see
  `atproto-write-records`

**Don't** call `XrpcClient.query()` / `procedure()` directly for ATProto
endpoints. The generated service wraps NSID, serializers, and auth
provider selection — bypassing it is error-prone.

## 2. Pagination with `*PageFlow()`

Every cursor-paginated query has two generated Flow extensions:

| Extension | Returns | Use when |
|---|---|---|
| `*Flow()` | `Flow<Item>` | You want individual items (e.g. batch processing) |
| `*PageFlow()` | `Flow<List<Item>>` | **Always for UI.** One emission per page. |

`*Flow()` on a Compose `StateFlow` triggers one recomposition per item.
`*PageFlow()` gives you one update per page.

**Naming rule:** strip leading `get`, camelCase, append `Flow`/`PageFlow`:
- `getTimeline` → `timelineFlow` / `timelinePageFlow`
- `searchPosts` → `searchPostsFlow` / `searchPostsPageFlow`
- `listNotifications` → `listNotificationsFlow` /
  `listNotificationsPageFlow`

### Basic collection

```kotlin
import io.github.kikin81.atproto.app.bsky.feed.timelinePageFlow

FeedService(client)
    .timelinePageFlow(GetTimelineRequest(limit = 25L))
    .catch { t -> /* surface error */ }
    .collect { page ->
        state.update { it + page }  // one state update per page
    }
```

### Infinite scroll with load-more gating

The Flow is lazy — the next page is only fetched when your `collect { }`
block returns. Suspend inside `collect` until the UI asks for more:

```kotlin
val loadMore = Channel<Unit>(Channel.CONFLATED)

FeedService(client).timelinePageFlow(request)
    .catch { t -> _ui.value = FeedUiState.Error(t.message ?: "") }
    .collect { page ->
        val current = (_ui.value as? FeedUiState.Loaded)?.feed.orEmpty()
        _ui.value = FeedUiState.Loaded(current + page)
        loadMore.receive()  // block until UI emits LoadMore
    }

// Elsewhere in the ViewModel:
fun onLoadMore() { loadMore.trySend(Unit) }
```

See `references/pagination-ui-pattern.kt` for a complete ViewModel.

### Safety properties of the pagination primitive

- **Lazy**: pages fetched only when the collector pulls
- **Cancellable**: Flow cancellation stops fetching
- **Repeated-cursor guard**: if the PDS returns the same cursor it
  received (a buggy server), the Flow terminates rather than looping
- **No built-in retry**: use `.retry()` / `.retryWhen()` operators at
  the call site if you want it

## 3. Open unions: $type dispatch

AT Protocol unions carry a `$type` discriminator. The library decodes
into **sealed interfaces** where each known arm is a data class and
there's always an `*Unknown` fallback for types the library doesn't
model.

**Rule: always pattern-match with `is` or `as?`. Never `!!`, never
assume a specific arm.**

### Feed entry reason (repost / pin / none)

```kotlin
feedViewPost.reason?.let { reason ->
    when (reason) {
        is ReasonRepost -> "Reposted by @${reason.by.handle.raw}"
        is ReasonPin -> "Pinned"
        is ReasonUnknown -> null  // newer reason type we don't know yet
    }
}
```

### Post embeds

```kotlin
when (val embed = postView.embed) {
    is ImagesView -> embed.images.firstOrNull()?.thumb
    is ExternalView -> embed.external.uri
    is RecordView -> renderQuoted(embed.record)
    is RecordWithMediaView -> {
        val media = embed.media as? ImagesView
        val quoted = embed.record
        /* render both */
    }
    is VideoView -> embed.playlist
    is EmbedUnknown -> null
    null -> null
}
```

### Quoted records (`RecordView.record` is itself a union)

```kotlin
when (val inner = recordView.record) {
    is RecordViewRecord -> {
        // real quoted post — decode its record
        val quotedPost = inner.value.decodeRecord<Post>()
        QuoteCard(inner.author, quotedPost)
    }
    is RecordViewNotFound -> "(deleted)"
    is RecordViewBlocked -> "(blocked)"
    is RecordViewDetached -> "(detached by author)"
    is RecordViewUnknown -> null
}
```

See `references/open-union-arms.md` for a complete table of every open
union in the library and its known arms.

## 4. Decoding typed records from `unknown`

Record values on the wire come in as `JsonObject` (because they're
polymorphic — any record type can show up). Use the extension:

```kotlin
import io.github.kikin81.atproto.runtime.decodeRecord

val post: Post = postView.record.decodeRecord<Post>()

// Tolerate failure — the record might be a type you don't model
val post = runCatching { postView.record.decodeRecord<Post>() }.getOrNull()
```

Or with an explicit serializer (useful when `T` isn't known statically):

```kotlin
val post = postView.record.decodeRecord(Post.serializer())
```

## Common pitfalls

- **Using `*Flow()` for UI.** Triggers one recomposition per item. Use
  `*PageFlow()`.
- **Not handling `*Unknown` arms.** Every open union has one. A missing
  branch in your `when { }` compiles but throws at runtime when upstream
  ships a new `$type`.
- **Assuming Jetpack Paging 3 integration.** None is shipped. Wrap a
  `*PageFlow()` in your own `PagingSource` if you need Paging 3.
- **Calling `XrpcClient.query()` directly.** Use the generated
  `*Service` methods — they wire NSID, serializer, and auth provider
  correctly.
- **Running the Flow on `Dispatchers.Main`.** The HTTP call suspends.
  Use `viewModelScope` (default dispatcher) or a specific IO scope.
- **Ignoring `.catch { }` on the Flow.** A network error mid-scroll
  will crash the ViewModel without it.
- **Mutating `record` before decoding.** `JsonObject` is immutable, but
  you might be tempted to strip `$type` — don't, the serializer needs it.

## Related skills

- `atproto-oauth` — how to get the `XrpcClient` this skill consumes
- `atproto-write-records` — after reading a post, how to like or delete
  it
- `atproto-types-reference` — value classes (`AtUri`, `Did`, `Handle`)
  you'll encounter on every response

## Reference: working sample

Full working `FeedViewModel` with OAuth client, `timelinePageFlow`
pagination, load-more gating, and optimistic like/unlike:

https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/samples/android/src/main/kotlin/io/github/kikin81/atproto/samples/bluesky/ui/FeedViewModel.kt
