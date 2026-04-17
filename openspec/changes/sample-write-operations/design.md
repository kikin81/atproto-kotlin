## Overview

Add like/unlike, post creation, and post deletion to the Android sample.
Requires a small runtime addition (`encodeRecord`) and several new UI
components in the sample module.

## Runtime: encodeRecord

### The problem

`CreateRecordRequest.record` is typed as `JsonObject`. Consumers need to
serialize a typed record class (e.g. `Like`, `Post`) into a `JsonObject`
and inject the required `$type` discriminator. Without a helper, this is:

```kotlin
val json = Json { explicitNulls = true; ignoreUnknownKeys = true }
val obj = json.encodeToJsonElement(Like.serializer(), like).jsonObject
val withType = JsonObject(obj + ("${'$'}type" to JsonPrimitive("app.bsky.feed.like")))
```

### The solution

Add to `RecordDecoder.kt` (alongside the existing `decodeRecord`):

```kotlin
fun <T> encodeRecord(
    serializer: KSerializer<T>,
    record: T,
    type: String,
    json: Json = recordDecoderJson,
): JsonObject {
    val obj = json.encodeToJsonElement(serializer, record).jsonObject
    return JsonObject(obj + ("\$type" to JsonPrimitive(type)))
}
```

The `json` instance reuses the same `recordDecoderJson` from `decodeRecord`
but note that for encoding we need `explicitNulls = true` so `AtField`
three-state semantics are preserved (Missing fields are omitted by
`@EncodeDefault(NEVER)`, Null fields are emitted as `null`, Defined
fields are emitted with their value).

## Like/Unlike flow

### Data model

Each `PostView` in the timeline carries:
- `viewer.like: AtUri?` — if non-null, the current user has liked this post.
  The URI is `at://<did>/app.bsky.feed.like/<rkey>`.
- `likeCount: Long?` — the total like count.

### Like (create)

```kotlin
val like = Like(
    subject = StrongRef(uri = post.uri, cid = post.cid),
    createdAt = Datetime.now(),
)
repoService.createRecord(CreateRecordRequest(
    repo = AtIdentifier(session.did),
    collection = Nsid("app.bsky.feed.like"),
    record = encodeRecord(Like.serializer(), like, "app.bsky.feed.like"),
))
```

### Unlike (delete)

```kotlin
val rkey = post.viewer?.like?.raw?.substringAfterLast('/')
    ?: return
repoService.deleteRecord(DeleteRecordRequest(
    repo = AtIdentifier(session.did),
    collection = Nsid("app.bsky.feed.like"),
    rkey = RecordKey(rkey),
))
```

### Optimistic UI

Update the local `PostView` in the StateFlow immediately on tap:
- Like: increment `likeCount`, set `viewer.like` to a placeholder URI
- Unlike: decrement `likeCount`, clear `viewer.like`

If the network call fails, revert. This keeps the UI responsive.

## Post creation

### Compose screen

A new `ComposeScreen` composable with:
- `TextField` for post text (max 300 graphemes, matching the Lexicon limit)
- Character counter
- "Post" button (disabled when empty or over limit)
- Back/cancel navigation

### ViewModel

`ComposeViewModel` with:
- `text: MutableStateFlow<String>`
- `posting: MutableStateFlow<Boolean>` (loading indicator)
- `createPost()` method that calls `createRecord`

### Record construction

```kotlin
val post = Post(
    text = text,
    createdAt = Datetime.now(),
)
repoService.createRecord(CreateRecordRequest(
    repo = AtIdentifier(session.did),
    collection = Nsid("app.bsky.feed.post"),
    record = encodeRecord(Post.serializer(), post, "app.bsky.feed.post"),
))
```

Optional fields (`reply`, `embed`, `langs`, `labels`, `tags`) are omitted
via `AtField.Missing` — the default. This exercises the three-state
serialization: `@EncodeDefault(NEVER)` ensures they don't appear in the
JSON payload.

## Post deletion

### UI trigger

Only show a delete action (icon or long-press menu) on posts where
`post.author.did == session.did`.

### Implementation

```kotlin
val rkey = post.uri.raw.substringAfterLast('/')
repoService.deleteRecord(DeleteRecordRequest(
    repo = AtIdentifier(session.did),
    collection = Nsid("app.bsky.feed.post"),
    rkey = RecordKey(rkey),
))
```

Remove the post from the local feed StateFlow after successful deletion.

## Datetime.now()

The `Datetime` value class doesn't have a `now()` factory yet. Add one:

```kotlin
// In StringFormats.kt or as an extension
fun Datetime.Companion.now(): Datetime =
    Datetime(java.time.Instant.now().toString())
```

Since the runtime is KMP, this needs an `expect/actual` or a JVM-only
extension. For the sample (JVM/Android only), a top-level helper in the
sample module is simplest:

```kotlin
fun datetimeNow(): Datetime = Datetime(java.time.Instant.now().toString())
```

## Hilt DI

The `RepoService` needs an `XrpcClient` from the OAuth session. The
existing `AtOAuth.createClient()` returns an `XrpcClient`, and
`FeedService` already wraps it. Add `RepoService` the same way — either
create it alongside `FeedService` in the ViewModel, or inject via Hilt.

Simplest: create `RepoService(client)` in each ViewModel that needs it,
using the same `AtOAuth.createClient()` flow.

## Navigation

Add a FAB on the feed screen that navigates to `ComposeScreen`. On
successful post creation, pop back to feed and trigger a refresh.

## Affected files

| File | Change |
|------|--------|
| `at-protocol-runtime/.../RecordDecoder.kt` | Add `encodeRecord()` |
| `samples/android/.../ui/FeedScreen.kt` | Like button, delete action |
| `samples/android/.../ui/FeedViewModel.kt` | Like/unlike/delete methods, optimistic state |
| `samples/android/.../ui/ComposeScreen.kt` | New compose screen |
| `samples/android/.../ui/ComposeViewModel.kt` | New compose ViewModel |
| `samples/android/.../MainViewModel.kt` | Navigation to compose |
| `samples/android/.../di/AppModule.kt` | No changes needed (RepoService created in ViewModel) |
