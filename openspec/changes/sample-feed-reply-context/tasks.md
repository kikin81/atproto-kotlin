## 1. Extraction helper

- [ ] 1.1 Add a sealed `ReplyParentInfo` interface in `FeedScreen.kt`
      (or a neighboring file) with `Post(view: PostView)` and
      `Unavailable(message: String)` variants
- [ ] 1.2 Add `internal fun extractReplyParent(entry: FeedViewPost):
      ReplyParentInfo?` that dispatches over
      `entry.reply?.parent` for `PostView` / `NotFoundPost` /
      `BlockedPost` / `ReplyRefParentUnion.Unknown` arms
- [ ] 1.3 Helper returns null for top-level posts (entry.reply == null)

## 2. `ParentContextRow` composable

- [ ] 2.1 Add a `@Composable ParentContextRow` in `FeedScreen.kt`
      accepting `info: ReplyParentInfo`, `onTap: (AtUri) -> Unit`
- [ ] 2.2 `ReplyParentInfo.Post` arm: small left-border column
      showing `@handle` (bold, muted) plus text excerpt (maxLines=2)
- [ ] 2.3 Wire `Modifier.clickable(onClick = { onTap(view.uri) })` on
      the `Post` arm; no click modifier on `Unavailable`
- [ ] 2.4 `ReplyParentInfo.Unavailable` arm: italic single-line text
      in muted color, no border decoration
- [ ] 2.5 Keep vertical padding tight so the context + main post read
      as one compound unit

## 3. Wire into `PostRow`

- [ ] 3.1 Compute `val parent = extractReplyParent(entry)` at the top
      of `PostRow`
- [ ] 3.2 Render `ParentContextRow(parent, onPostTap)` below
      `RepostHeader` and above the author / timestamp row, only when
      `parent != null`
- [ ] 3.3 Thread the existing `onPostTap(AtUri)` callback through to
      the context row (don't introduce a new callback)
- [ ] 3.4 Verify the main post's clickable modifier still handles
      its own `onTap` on the reply's URI — the parent tap and
      reply tap are independent routes

## 4. Tests

- [ ] 4.1 Add `TIMELINE_REPLY_WITH_KNOWN_PARENT` fixture: feed entry
      with `reply.parent` as `#postView`
- [ ] 4.2 Add `TIMELINE_REPLY_WITH_NOT_FOUND_PARENT` fixture
- [ ] 4.3 Add `TIMELINE_REPLY_WITH_BLOCKED_PARENT` fixture
- [ ] 4.4 Add `TIMELINE_REPLY_WITH_UNKNOWN_PARENT` fixture (custom
      `$type` like `app.bsky.feed.defs#futureReplyParent`)
- [ ] 4.5 Add `TIMELINE_REPOST_OF_REPLY` fixture carrying both
      `reason` and `reply` fields
- [ ] 4.6 `FeedScreenTest.extractReplyParent_*` test cases asserting
      the correct `ReplyParentInfo` arm + payload for each fixture
- [ ] 4.7 Regression test: top-level post yields null from
      `extractReplyParent`
- [ ] 4.8 Repost+reply combo test: entry still exposes both
      `ReasonRepost` (via existing helpers) and a non-null
      `ReplyParentInfo`
- [ ] 4.9 `./gradlew :samples:android:testDebugUnitTest` passes

## 5. Build + manual verification

- [ ] 5.1 `./gradlew :samples:android:assembleDebug` succeeds
- [ ] 5.2 `./gradlew spotlessCheck` passes
- [ ] 5.3 Manual on-device: find a feed that includes a reply from
      someone you follow; verify the parent context appears above
      the reply and tapping it opens the parent's thread —
      **deferred to maintainer smoke test**

## 6. Archive

- [ ] 6.1 `openspec status --change sample-feed-reply-context` reports
      4/4 artifacts complete
- [ ] 6.2 `openspec archive sample-feed-reply-context -y` succeeds
- [ ] 6.3 Confirm the delta lands as ADDED requirements under
      `openspec/specs/android-sample/spec.md`
