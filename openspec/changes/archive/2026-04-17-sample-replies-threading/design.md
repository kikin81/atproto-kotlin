## Overview

Add thread reading and reply composition to the Android sample. Pure
consumer-side work — the library already emits every type and
procedure required.

## Screen model

Current screens (via `showCompose: Boolean` in `MainActivity`):

1. **Login** (logged-out)
2. **Feed** (logged-in, default)
3. **Compose** (logged-in, when `showCompose == true`)

New screen:

4. **Thread** (logged-in, when a thread URI is selected)

The sample is single-activity / single-composition state. Promote the
navigation state in `MainActivity` from `Boolean showCompose` to a
sealed interface so we can encode the thread selection:

```kotlin
sealed interface LoggedInScreen {
    data object Feed : LoggedInScreen
    data class Thread(val rootUri: AtUri) : LoggedInScreen
    data class Compose(val replyTo: PostView? = null) : LoggedInScreen
}
```

`MainActivity` holds a `var screen by remember { mutableStateOf<LoggedInScreen>(Feed) }`
and switches on it. Back handlers return to `Feed`.

Jetpack Navigation is out of scope — the state-machine approach keeps
parity with the current code and avoids pulling in nav-compose.

## Thread data loading

```kotlin
FeedService(client).getPostThread(
    GetPostThreadRequest(uri = rootUri, depth = 6, parentHeight = 4),
)
```

Response is `GetPostThreadResponse(thread: GetPostThreadResponseThread)`
where the union has four arms:

- `ThreadViewPost` — normal case, recursive: `post: PostView`,
  `parent: ThreadViewPostParent?`, `replies: List<ThreadViewPostReply>?`.
- `NotFoundPost` — deleted / unreachable.
- `BlockedPost` — block in the way.
- `ThreadViewPostUnknown` — unknown `$type`.

For the first three non-happy arms, render a centered placeholder:

- `NotFoundPost` → "This post was deleted."
- `BlockedPost` → "This post is unavailable."
- `ThreadViewPostUnknown` → "Thread unavailable."

## Thread rendering (happy path)

`ThreadViewPost` is a recursive tree. Flatten for a scrollable screen:

### Ancestors

Walk `thread.parent` up via its own union (same arms) to build a list
of ancestor posts from root down to the focused post's direct parent.
Render each ancestor as a compact row: author handle, short text
excerpt, muted styling. Stop walking when a non-`ThreadViewPost` arm
appears — render a "Context unavailable" line and break.

### Focused post

Render the focused `ThreadViewPost.post` prominently: full text,
embeds, action row (Like / Reply / Delete if own). Action button set
is richer than in the feed — Reply is new.

### Replies

Render `thread.replies` as a flat `LazyColumn`. Each reply is a
normal post row (reuse `PostRow` composable) with a left-border indent
(single depth — no deeper nesting in v1). Handle non-happy arms
per-row with the same placeholders.

## Reply composition

### `ComposeViewModel` changes

Today:

```kotlin
class ComposeViewModel @Inject constructor(
    private val oauth: AtOAuth,
    private val sessionStore: OAuthSessionStore,
) : ViewModel() {
    fun createPost() { /* builds Post(text, createdAt) */ }
}
```

Change to accept optional reply context:

```kotlin
class ComposeViewModel @Inject constructor(
    private val oauth: AtOAuth,
    private val sessionStore: OAuthSessionStore,
) : ViewModel() {

    private var replyTo: PostView? = null

    fun setReplyTo(post: PostView?) {
        replyTo = post
    }

    fun createPost() {
        val post = Post(
            text = _text.value.trim(),
            createdAt = datetimeNow(),
            reply = replyTo?.let { buildReplyRef(it) },
        )
        /* ... createRecord call as today ... */
    }

    private fun buildReplyRef(target: PostView): ReplyRef {
        // Parent is the post we're replying to.
        val parent = StrongRef(uri = target.uri, cid = target.cid)

        // Root: if the target itself is a reply, inherit its thread root.
        // Otherwise the target IS the root.
        val targetRecord = runCatching {
            target.record.decodeRecord<Post>()
        }.getOrNull()
        val root = targetRecord?.reply?.root ?: parent

        return ReplyRef(parent = parent, root = root)
    }
}
```

**Root resolution**: AT Protocol requires the reply's `root` to be the
thread's originating post, not just the direct parent. The spec
enforces this server-side. Cheap client implementation: decode the
target's `record: JsonObject` to a typed `Post` and read its
`reply.root` — if the target was itself a reply, that's the root;
otherwise the target post is the root. Wrapped in `runCatching`
because decode may fail for records with unknown fields.

### `ComposeScreen` changes

Show a compact "Replying to @handle" banner above the text field when
`replyTo != null`. A small "Cancel reply" link clears the reply state
and continues as a top-level post (or returns to feed — designer call;
default: clear and stay on compose).

### Entry points

- **Thread screen** — Reply button on focused post opens `Compose(replyTo = focusedPost)`
- **Thread screen** — Reply button on each reply row opens `Compose(replyTo = replyPost)`
- **Feed screen** — no reply button directly; user taps post → thread
  screen → reply. Keeps the feed uncluttered.

## Open-union arm handling

All thread-related unions expose these arms to handle:

| Union | Happy arm | Other arms |
|---|---|---|
| `GetPostThreadResponseThread` | `ThreadViewPost` | `NotFoundPost`, `BlockedPost`, `ThreadViewPostUnknown` |
| `ThreadViewPostParent` | `ThreadViewPost` | `NotFoundPost`, `BlockedPost`, `ThreadViewPostParentUnknown` |
| `ThreadViewPostReply` | `ThreadViewPost` | `NotFoundPost`, `BlockedPost`, `ThreadViewPostReplyUnknown` |

Every `when` branch on these MUST handle its `*Unknown` arm. Add a
single helper:

```kotlin
private fun renderPlaceholder(arm: Any): String = when (arm) {
    is NotFoundPost -> "This post was deleted."
    is BlockedPost -> "This post is unavailable."
    else -> "Thread unavailable."
}
```

## Optimistic UI for replies

Out of scope. After `createRecord` succeeds in `ComposeViewModel`, the
app navigates back to `ThreadScreen` which triggers a fresh
`getPostThread` call. Simpler than optimistic insertion, acceptable
latency (~500ms).

## Error handling

- **Thread fetch failure**: red banner with "Retry" button in
  `ThreadScreen`. Standard `runCatching` pattern used elsewhere in
  the sample.
- **Reply post failure**: existing `ComposeViewModel` error flow
  (surface to UI, keep text populated so user can retry).
- **`getPostThread` returns non-happy arm at the root URI**: show the
  placeholder and a "Back" button.

## Testing

### Unit tests

- `ThreadViewModel.loadThread` with a canned `GetPostThreadResponse`
  containing a happy `ThreadViewPost` with 2 ancestors and 3 replies
  → UI state contains all 5 posts in the correct order.
- `ThreadViewModel.loadThread` with `NotFoundPost` at the root → UI
  state is a `Unavailable` placeholder state.
- `ThreadViewModel.loadThread` with `ThreadViewPostUnknown` at the
  root → UI state is the generic-unavailable placeholder.
- `ComposeViewModel.createPost` with a `replyTo` that is a
  top-level post → resulting `Post.reply.root == Post.reply.parent`.
- `ComposeViewModel.createPost` with a `replyTo` that itself is a
  reply (its own `record.reply` is set) → resulting `Post.reply.root`
  equals the ancestor's root, not the direct parent.

### MockEngine fixture

Add a `thread_happy.json` and `thread_not_found.json` fixture pair
under `samples/android/src/test/resources/fixtures/thread/`. Match
the existing fixture style from the `sample-repost-embeds` work.

## Non-goals

- **Rich text facets** (mentions, links, hashtags in composed text) —
  separate change.
- **Image / video embeds in replies** — text-only MVP. Ship image
  embeds in a dedicated follow-up.
- **Deep nesting in replies UI** — single-depth indent only. Infinite
  nesting requires a design call about collapse/expand.
- **Optimistic reply insertion** — refetch on success is sufficient.
- **Deep-linking to thread URIs from outside the app** — no intent
  filter for `at://` URIs or HTTPS bsky.app URLs.
- **Push notifications when a reply arrives** — separate capability.
- **Thread pagination** (long threads > `depth/parentHeight` default)
  — reuses library defaults; deeper threads truncate.
