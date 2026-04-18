## 1. Extraction helper

- [x] 1.1 Add a sealed `ReplyParentInfo` interface in `FeedScreen.kt`
      with `Post(view: PostView)` and `Unavailable(message: String)`
      variants
- [x] 1.2 Add `internal fun extractReplyParent(entry: FeedViewPost):
      ReplyParentInfo?` that dispatches over
      `entry.reply?.parent` for `PostView` / `NotFoundPost` /
      `BlockedPost` / `ReplyRefParentUnion.Unknown` arms
- [x] 1.3 Helper returns null for top-level posts (entry.reply == null)

## 2. `ParentContextRow` composable

- [x] 2.1 Add a `@Composable ParentContextRow` in `FeedScreen.kt`
      accepting `info: ReplyParentInfo?`, `onOpenThread: (AtUri) -> Unit`
- [x] 2.2 `ReplyParentInfo.Post` arm: compact column showing
      `@handle` (bold, muted) plus text excerpt (maxLines=2), indented
      to suggest a thread-context hint
- [x] 2.3 Wire `Modifier.clickable { onOpenThread(view.uri) }` on the
      `Post` arm; no click modifier on `Unavailable`
- [x] 2.4 `ReplyParentInfo.Unavailable` arm: italic single-line text
      in muted color
- [x] 2.5 Keep vertical padding tight so the context + main post read
      as one compound unit

## 3. Wire into `PostRow`

- [x] 3.1 Compute `val replyParent = extractReplyParent(entry)` at the
      top of `PostRow`
- [x] 3.2 Render `ParentContextRow(replyParent, onOpenThread)` below
      `RepostHeader` and above the author / timestamp row, only when
      `replyParent != null` (short-circuit inside the composable)
- [x] 3.3 Refactored `PostRow` to take `onOpenThread: (AtUri) -> Unit`
      instead of the opaque `onTap: () -> Unit`, since the parent row
      and main row tap-target *different* URIs
- [x] 3.4 Main post's own clickable modifier routes through the same
      `onOpenThread(post.uri)` — parent tap and reply tap are
      independent navigation routes

## 4. Tests

- [x] 4.1 `TIMELINE_REPLY_WITH_KNOWN_PARENT` fixture (reply.parent as
      `#postView`)
- [x] 4.2 `TIMELINE_REPLY_WITH_NOT_FOUND_PARENT` fixture
- [x] 4.3 `TIMELINE_REPLY_WITH_BLOCKED_PARENT` fixture
- [x] 4.4 `TIMELINE_REPLY_WITH_UNKNOWN_PARENT` fixture (custom `$type`
      `app.bsky.feed.defs#futureReplyParent`)
- [x] 4.5 `TIMELINE_REPOST_OF_REPLY` fixture carrying both `reason`
      and `reply` fields
- [x] 4.6 `FeedScreenTest.replyWith*` cases asserting the correct
      `ReplyParentInfo` arm + payload for each fixture
- [x] 4.7 Regression test: top-level post yields null from
      `extractReplyParent` (reused `TIMELINE_WITH_NO_EMBED`)
- [x] 4.8 Repost+reply combo test: entry exposes both `ReasonRepost`
      (via existing helpers) and a non-null `ReplyParentInfo`
- [x] 4.9 `./gradlew :samples:android:testDebugUnitTest` passes
      (16 FeedScreenTest cases, 5 ThreadViewModelTest, 3
      ComposeViewModelReplyTest — all green)

## 5. Build + manual verification

- [x] 5.1 `./gradlew :samples:android:assembleDebug` succeeds
- [x] 5.2 `./gradlew spotlessCheck` passes
- [ ] 5.3 Manual on-device: find a feed that includes a reply from
      someone you follow; verify the parent context appears above
      the reply and tapping it opens the parent's thread —
      **deferred to maintainer smoke test**

## 6. Archive

- [x] 6.1 `openspec status --change sample-feed-reply-context` reports
      4/4 artifacts complete
- [x] 6.2 `openspec archive sample-feed-reply-context -y` succeeds
- [x] 6.3 Delta landed as ADDED requirements under
      `openspec/specs/android-sample/spec.md`
