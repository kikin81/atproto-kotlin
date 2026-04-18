## Why

When a user you follow replies to a post, the official Bluesky app shows
the parent post as a compact context row above the reply, with a thread
connector line between the two avatars. Our sample app renders the reply
as a standalone row, so a one-word reply like "❤️" appears with no
indication of what it's replying to — and the original post ends up
scattered elsewhere in the feed (or missing entirely if no one you
follow reposted it).

This isn't a feed-algorithm difference. The AT Protocol `getTimeline`
response already carries the parent post on every reply entry via
`FeedViewPost.reply.parent` (a `ReplyRefParentUnion`). The Bluesky app
renders that inline; our sample ignores it.

Fixing this is pure consumer rendering — no library surface changes,
no new XRPC calls — and dramatically improves feed readability.

## What Changes

- **Feed row gains a parent-context slot.** When `entry.reply != null`,
  `PostRow` renders a compact author+text excerpt above the main post
  before the existing author / timestamp / content / actions block.
- **Open-union dispatch for `ReplyRefParentUnion`**: happy arm
  (`PostView`) becomes a full context row; `NotFoundPost` / `BlockedPost`
  / `ReplyRefParentUnion.Unknown` render a short localized placeholder
  ("Replying to [deleted post]", "Replying to [blocked account]",
  "Replying to [unavailable]") so the reply is never presented as if
  it were top-level.
- **Tapping the parent context navigates to the parent's thread.**
  Reuses the existing `onPostTap(AtUri)` callback plumbed in the
  threading change — the parent's own `uri` routes the tap to its
  `ThreadScreen`.
- **Repost + reply entries render all three layers in order**: repost
  header, parent-context row, then the reply. A feed can legitimately
  contain a repost of someone's reply; all three signals are relevant.
- **Tests**: canned `GetTimelineResponse` fixtures exercising each
  union arm plus the repost+reply combo.

## Capabilities

### Modified Capabilities

- `android-sample`: feed rows gain parent-context rendering for reply
  entries with graceful handling of unavailable-parent arms.

## Impact

- **Modified files**: `FeedScreen.kt` (new `ParentContextRow` and
  `extractReplyParent` helpers, wired into `PostRow`), plus one or
  two new test cases in `FeedScreenTest.kt`.
- **No library-level changes.** `FeedViewPost.reply`,
  `ReplyRefParentUnion`, `PostView`, `NotFoundPost`, `BlockedPost`
  are all existing generator output.
- **No thread-screen changes.** The thread screen already renders the
  full ancestor chain above the focused post; inline parent context
  there would be redundant.
- **Breaking changes**: none.
