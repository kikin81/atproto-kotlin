## Why

The Android sample app currently lets a user scroll a feed, like posts,
compose a new top-level post, and delete their own posts. The single
biggest feature a real client needs next is **threading**: tapping a
post to see the replies, and writing a reply that targets a specific
post in a thread.

Everything required is already in the library:

- `FeedService.getPostThread(GetPostThreadRequest(uri))` is
  generator-emitted and returns a `GetPostThreadResponse` whose
  `thread` is an open union over `ThreadViewPost`, `NotFoundPost`,
  `BlockedPost`, and `ThreadViewPostUnknown`.
- `Post.reply: ReplyRef?` is the existing mutation field for creating
  a reply, with `StrongRef` entries for the thread root and the direct
  parent.
- The `ComposeViewModel` already creates `Post` records via
  `RepoService.createRecord` + `encodeRecord()`.

So the work is pure sample-app wiring: navigation, a thread screen, a
"reply" entry point that hydrates the existing compose flow with the
right `ReplyRef`. No new capability on the runtime, generator, or
models side.

## What Changes

- **New `ThreadScreen` + `ThreadViewModel`** in the Android sample.
  Loads a post thread via `FeedService.getPostThread(uri)` and renders
  ancestors → focused post → flat reply list.
- **Feed tap navigates to thread.** Tapping a post in `FeedScreen`
  opens `ThreadScreen` for that post's URI.
- **Reply entry point.** A "Reply" button on the focused post (and on
  each reply in the thread) opens `ComposeScreen` prefilled with the
  target post as `replyTo`.
- **`ComposeViewModel` gains `replyTo: PostView?` mode.** When set,
  the Post it creates includes a correctly-constructed `ReplyRef`
  (parent = the replied-to post; root = the replied-to post's
  existing thread root, falling back to the parent itself for
  top-level replies).
- **Open-union dispatch for thread arms.** `NotFoundPost`,
  `BlockedPost`, and `ThreadViewPostUnknown` render placeholders
  instead of crashing.
- **Deprecation banner removed**. The existing `Requirement: Sample
  SHALL authenticate via the generated createSession procedure` in
  `android-sample` spec is historical — the sample migrated to OAuth
  long ago but the requirement wasn't updated. Not in this change's
  scope to remove; flagged for a future cleanup.

## Capabilities

### Modified Capabilities

- `android-sample`: gains thread-reading and reply-composition
  requirements.

## Impact

- **New files**: `ThreadScreen.kt`, `ThreadViewModel.kt` under
  `samples/android/.../ui/`.
- **Modified files**: `FeedScreen.kt` (tap handler), `MainActivity.kt`
  (navigation case for thread), `ComposeScreen.kt` +
  `ComposeViewModel.kt` (accept `replyTo`), plus tests.
- **No library-level changes.** Runtime, generator, models, OAuth are
  untouched.
- **Breaking changes**: none. The sample isn't published.
- **Test additions**: `ThreadViewModel` unit tests with MockEngine
  against canned `getPostThread` responses covering each open-union
  arm.
