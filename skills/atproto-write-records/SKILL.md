---
name: atproto-write-records
description: >
  Use this skill when writing records to the AT Protocol via the
  kikin81/atproto-kotlin library — creating posts, likes, reposts,
  follows, blocks, or deleting any of those. Every record write goes
  through com.atproto.repo.createRecord (or deleteRecord), using the
  encodeRecord() helper to wrap a typed record with its $type
  discriminator. Use after atproto-oauth (you need an authenticated
  XrpcClient) and atproto-read (for reading the records you want to
  act on).
license: MIT (see repo LICENSE)
metadata:
  author: kikin81
  library-version: "4.6.0"
  keywords:
    - AT Protocol
    - Bluesky
    - createRecord
    - deleteRecord
    - encodeRecord
    - post
    - like
    - repost
    - follow
    - block
    - RepoService
---

## Objective

Create or delete AT Protocol records (posts, likes, reposts, follows,
blocks) using the `RepoService` + `encodeRecord()` pattern.

## Prerequisites

- Authenticated `XrpcClient` from `oauth.createClient()`
  (see `atproto-oauth`)
- The authenticated user's DID (available from
  `sessionStore.load()?.did`)

## The universal pattern

Every record write is the same shape:

```kotlin
val record = /* construct typed record, e.g. Post(...) */
RepoService(client).createRecord(
    CreateRecordRequest(
        repo = AtIdentifier(did),
        collection = Nsid("<lexicon.nsid>"),
        record = encodeRecord(<Type>.serializer(), record, "<lexicon.nsid>"),
    ),
)
```

The `encodeRecord()` helper is the key piece — it serializes your
typed record into a `JsonObject` and injects `"$type"` so the server
can discriminate. You get back a `CreateRecordResponse` with the new
record's `uri: AtUri` and `cid: Cid`.

## Creating a post

```kotlin
import io.github.kikin81.atproto.app.bsky.feed.Post
import io.github.kikin81.atproto.com.atproto.repo.CreateRecordRequest
import io.github.kikin81.atproto.com.atproto.repo.RepoService
import io.github.kikin81.atproto.runtime.AtIdentifier
import io.github.kikin81.atproto.runtime.Nsid
import io.github.kikin81.atproto.runtime.encodeRecord
import io.github.kikin81.atproto.runtime.Datetime

val post = Post(
    text = "Hello, atproto!",
    createdAt = Datetime(Instant.now().toString()),  // ISO 8601
)

RepoService(client).createRecord(
    CreateRecordRequest(
        repo = AtIdentifier(did),
        collection = Nsid("app.bsky.feed.post"),
        record = encodeRecord(Post.serializer(), post, "app.bsky.feed.post"),
    ),
)
```

## Liking a post

```kotlin
import io.github.kikin81.atproto.app.bsky.feed.Like
import io.github.kikin81.atproto.com.atproto.repo.StrongRef

val like = Like(
    subject = StrongRef(uri = post.uri, cid = post.cid),
    createdAt = Datetime(Instant.now().toString()),
)

RepoService(client).createRecord(
    CreateRecordRequest(
        repo = AtIdentifier(did),
        collection = Nsid("app.bsky.feed.like"),
        record = encodeRecord(Like.serializer(), like, "app.bsky.feed.like"),
    ),
)
```

## Unliking (delete the like record)

The like record's URI is on `PostView.viewer.like` when the current
user has liked the post. Extract the rkey (last path segment) and
delete:

```kotlin
import io.github.kikin81.atproto.com.atproto.repo.DeleteRecordRequest
import io.github.kikin81.atproto.runtime.RecordKey

val likeUri = postView.viewer?.like ?: return  // user hasn't liked
val rkey = likeUri.raw.substringAfterLast('/')

RepoService(client).deleteRecord(
    DeleteRecordRequest(
        repo = AtIdentifier(did),
        collection = Nsid("app.bsky.feed.like"),
        rkey = RecordKey(rkey),
    ),
)
```

## Reposting

```kotlin
import io.github.kikin81.atproto.app.bsky.feed.Repost

val repost = Repost(
    subject = StrongRef(uri = post.uri, cid = post.cid),
    createdAt = Datetime(Instant.now().toString()),
)

RepoService(client).createRecord(
    CreateRecordRequest(
        repo = AtIdentifier(did),
        collection = Nsid("app.bsky.feed.repost"),
        record = encodeRecord(Repost.serializer(), repost, "app.bsky.feed.repost"),
    ),
)
```

## Following a user

```kotlin
import io.github.kikin81.atproto.app.bsky.graph.Follow
import io.github.kikin81.atproto.runtime.Did

val follow = Follow(
    subject = Did("did:plc:targetuserhere"),
    createdAt = Datetime(Instant.now().toString()),
)

RepoService(client).createRecord(
    CreateRecordRequest(
        repo = AtIdentifier(did),
        collection = Nsid("app.bsky.graph.follow"),
        record = encodeRecord(Follow.serializer(), follow, "app.bsky.graph.follow"),
    ),
)
```

## Blocking a user

Same shape — different collection:

```kotlin
import io.github.kikin81.atproto.app.bsky.graph.Block

val block = Block(
    subject = Did("did:plc:targetuserhere"),
    createdAt = Datetime(Instant.now().toString()),
)

RepoService(client).createRecord(
    CreateRecordRequest(
        repo = AtIdentifier(did),
        collection = Nsid("app.bsky.graph.block"),
        record = encodeRecord(Block.serializer(), block, "app.bsky.graph.block"),
    ),
)
```

## Deleting a post (your own)

```kotlin
val rkey = post.uri.raw.substringAfterLast('/')

RepoService(client).deleteRecord(
    DeleteRecordRequest(
        repo = AtIdentifier(did),
        collection = Nsid("app.bsky.feed.post"),
        rkey = RecordKey(rkey),
    ),
)
```

The server validates that the authenticated user owns the record.
Attempting to delete someone else's record returns an error.

## Optimistic UI pattern

For likes/reposts/follows, update the UI immediately, then revert on
failure:

```kotlin
val newCount = (post.likeCount ?: 0) + 1
val newViewer = (post.viewer ?: ViewerState()).copy(
    like = AtUri("pending"),  // placeholder, real URI known after server response
)
updatePostInState(post.uri) { it.copy(
    post = it.post.copy(viewer = newViewer, likeCount = newCount),
) }

viewModelScope.launch {
    runCatching { /* createRecord call */ }.onFailure {
        // revert the optimistic update
        updatePostInState(post.uri) { it.copy(
            post = it.post.copy(viewer = null, likeCount = post.likeCount),
        ) }
    }
}
```

See the sample app's `FeedViewModel.kt` for a complete optimistic
like/unlike implementation.

## Common pitfalls

- **Forgetting `encodeRecord()` and passing a plain `Json.encodeToJsonElement(record)`.**
  Misses `"$type"` — the server rejects with a validation error.
- **Passing the wrong `type` string.** Must exactly match the lexicon
  NSID. Copy-paste from the `collection` arg; don't retype.
- **Using positional args on `Post(...)`.** `Post` has many optional
  fields (`facets`, `langs`, `labels`, `embed`, `reply`). Use named
  args.
- **Omitting `createdAt`.** Required on every Bluesky record. Use
  `Datetime(Instant.now().toString())` — ISO 8601 string.
- **Parsing rkey with `.split("/").last()`.** Fine, but
  `uri.raw.substringAfterLast('/')` is faster and matches the sample
  app style.
- **Trying to use `putRecord` for updates.** Rare in practice —
  Bluesky records are typically immutable (deleted + recreated). Use
  `createRecord` / `deleteRecord`.
- **Not handling the refresh case.** If `createClient()` is called on
  a stale session, tokens refresh transparently — but if the refresh
  token itself is expired, you'll get an `OAuthSessionExpiredException`.
  Catch and route to a re-login flow.

## Related skills

- `atproto-oauth` — get the authenticated client and the user DID
- `atproto-read` — read posts to like/repost/delete
- `atproto-types-reference` — `AtField` three-state semantics for the
  rare `putPreferences`-style mutations where "clear field" matters

## Reference

- Record-type cheat sheet: `references/common-records.md`
- Working sample with post creation:
  https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/samples/android/src/main/kotlin/io/github/kikin81/atproto/samples/bluesky/ui/ComposeViewModel.kt
- Working sample with optimistic like/delete:
  https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/samples/android/src/main/kotlin/io/github/kikin81/atproto/samples/bluesky/ui/FeedViewModel.kt
