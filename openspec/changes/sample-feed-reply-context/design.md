## Overview

Pull the pre-loaded parent post off `FeedViewPost.reply.parent` and
render it as a compact context row above each reply entry in the feed,
matching the parent-context pattern the official Bluesky app uses.

## Data already in the payload

From the lexicon (`app.bsky.feed.defs#replyRef`) and the generated
model:

```kotlin
data class ReplyRef(
    val parent: ReplyRefParentUnion,       // ← what we render
    val root: ReplyRefRootUnion,
    val grandparentAuthor: ProfileViewBasic? = null,
)
```

`ReplyRefParentUnion` arms (from the generator):

| Arm | Render as |
|---|---|
| `PostView` | Full compact row: author handle + text excerpt |
| `NotFoundPost` | "Replying to [deleted post]" |
| `BlockedPost` | "Replying to [blocked account]" |
| `ReplyRefParentUnion.Unknown` | "Replying to [unavailable]" |

No network call needed — the AppView denormalizes the parent onto
every reply entry in `getTimeline` / `getAuthorFeed` / etc.

## Rendering in `PostRow`

Current structure (simplified):

```
Column {
    RepostHeader(entry.reason)
    Row { handle, timestamp }
    Text(postText)
    QuotedRecordCard / quotedPlaceholder?
    Image thumb?
    ActionRow { like, delete }
}
```

Target:

```
Column {
    RepostHeader(entry.reason)
    ParentContextRow(entry.reply)     // NEW — renders only when reply != null
    Row { handle, timestamp }
    Text(postText)
    ...
}
```

`ParentContextRow` is a single composable that dispatches over
`ReplyRefParentUnion`. Layout:

- Small left border (1dp or 2dp) to suggest a thread connector — no
  literal connector line between avatars in v1 (that requires avatar
  positioning we don't do yet)
- Bold author handle (muted color)
- One-line text excerpt (maxLines = 2)
- Tappable: clicking navigates to the parent's thread via the
  existing `onPostTap(AtUri)` callback
- Reduced vertical padding so the parent + main post read as one
  compound unit

For non-happy arms, the same row skeleton renders with a single italic
line ("Replying to [deleted post]" etc.) and is **not** tappable —
there's no parent URI to route to.

## Tap navigation

`onPostTap` already exists on `FeedScreen` / `PostRow` (added in the
threading change). Wire the parent row's `onClick` to
`onPostTap(parent.uri)` on the `PostView` arm. The user expectation:
tapping the parent shows the full thread of the parent post; tapping
the main (reply) post shows the thread of the reply. Both are valid
thread-screen navigations.

## Repost + reply combinations

A feed entry can have both `reason: ReasonRepost` (someone reposted
this) and `reply: ReplyRef` (the thing that was reposted was itself a
reply). Render order, top to bottom:

1. `RepostHeader("Reposted by @X")` — smallest type
2. `ParentContextRow(entry.reply.parent)` — compact parent excerpt
3. Main post body (the reply itself)

Each layer is self-contained in the Column; no special combination
logic needed beyond calling all three composables in order.

## Helpers

Two pure helpers in `FeedScreen.kt`:

```kotlin
internal fun extractReplyParent(entry: FeedViewPost): ReplyParentInfo? = entry.reply?.let { ref ->
    when (val parent = ref.parent) {
        is PostView -> ReplyParentInfo.Post(parent)
        is NotFoundPost -> ReplyParentInfo.Unavailable("Replying to [deleted post]")
        is BlockedPost -> ReplyParentInfo.Unavailable("Replying to [blocked account]")
        is ReplyRefParentUnion.Unknown -> ReplyParentInfo.Unavailable("Replying to [unavailable]")
        else -> ReplyParentInfo.Unavailable("Replying to [unavailable]")
    }
}

sealed interface ReplyParentInfo {
    data class Post(val view: PostView) : ReplyParentInfo
    data class Unavailable(val message: String) : ReplyParentInfo
}
```

The helper is `internal` so the test module can exercise it without
instantiating any composable.

## Non-goals

- **Visual connector line** between parent avatar and reply avatar —
  requires explicit avatar rendering alignment we don't do yet. The
  left border is a sufficient hint for v1.
- **Grandparent chain** (`ReplyRef.grandparentAuthor`) — a deeper
  context breadcrumb the Bluesky app shows occasionally. Skip in v1.
- **Parent context in the thread screen** — thread screen already
  renders the full ancestor chain; inline context there is redundant.
- **Images or embeds on the parent context row** — text-only excerpt
  for v1. The parent's full rendering is one tap away via the
  thread-navigation behavior.
- **Clickable handles / mentions inside the parent text** — rich
  text facets are a separate change.

## Testing

New cases in `FeedScreenTest`:

1. Timeline with a reply-to-known-post entry → `extractReplyParent`
   returns `ReplyParentInfo.Post` with the expected author handle.
2. Timeline with a reply-to-deleted-post entry → `extractReplyParent`
   returns `ReplyParentInfo.Unavailable` with the deleted-post message.
3. Timeline with a reply-to-blocked-post entry → blocked-account message.
4. Timeline with a reply whose parent `$type` is unknown → unavailable
   fallback.
5. Timeline with a top-level post (no `reply` field) →
   `extractReplyParent` returns null; existing rendering unchanged.
6. Timeline with repost+reply combo → both `ReasonRepost` and
   `ReplyRefParentUnion` parent are extractable from the entry.
