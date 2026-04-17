## 1. Navigation state refactor

- [ ] 1.1 Introduce `sealed interface LoggedInScreen` with `Feed`,
      `Thread(rootUri: AtUri)`, and `Compose(replyTo: PostView? = null)`
      variants in `MainActivity.kt`
- [ ] 1.2 Replace `var showCompose by mutableStateOf(false)` with
      `var screen by mutableStateOf<LoggedInScreen>(Feed)`
- [ ] 1.3 Route each screen variant to its composable in the `when`
      block; wire back-handlers to return to `Feed`

## 2. Thread screen

- [ ] 2.1 Create `ThreadViewModel` (`@HiltViewModel`) with
      `uiState: StateFlow<ThreadUiState>` and a `loadThread(uri: AtUri)`
      entry point that calls
      `FeedService(client).getPostThread(GetPostThreadRequest(uri))`
- [ ] 2.2 Define `ThreadUiState` as a sealed interface:
      `Loading`, `Loaded(ancestors, focused, replies)`,
      `Unavailable(message)`, `Error(message)`
- [ ] 2.3 Handle all four arms of `GetPostThreadResponseThread`:
      `ThreadViewPost` → populate `Loaded`; `NotFoundPost` /
      `BlockedPost` / `ThreadViewPostUnknown` → populate
      `Unavailable` with the matching placeholder string
- [ ] 2.4 Ancestor walk: recurse up `ThreadViewPost.parent` handling
      its own union arms; stop at root or first non-happy arm
- [ ] 2.5 Replies: flatten `ThreadViewPost.replies` once (no deeper
      recursion in v1); each entry dispatches over
      `ThreadViewPostReply` arms, happy becomes a `ReplyRow` model,
      non-happy becomes a `PlaceholderRow`
- [ ] 2.6 Create `ThreadScreen` composable: `LazyColumn` with
      ancestors block, focused post block (styled prominently),
      replies list; placeholder composable for unavailable arms
- [ ] 2.7 Reuse `PostRow` for ancestors and replies; extract a
      `FocusedPostCard` composable for the prominent center slot
- [ ] 2.8 Add a "Back" button to the thread screen that returns to
      `Feed`

## 3. Feed tap → thread

- [ ] 3.1 `FeedScreen` accepts an `onPostTap: (AtUri) -> Unit`
      callback
- [ ] 3.2 `MainActivity` passes a callback that sets
      `screen = Thread(uri)` on the tapped post's URI
- [ ] 3.3 `PostRow` wires `Modifier.clickable { onPostTap(post.uri) }`
      at the row level (but not on action buttons, to avoid swallowing
      Like/Reply taps)

## 4. Reply composition

- [ ] 4.1 Extend `ComposeViewModel` with a private `replyTo: PostView?`
      and a `setReplyTo(post: PostView?)` method
- [ ] 4.2 Add a private `buildReplyRef(target: PostView): ReplyRef`
      helper that constructs `parent = StrongRef(target.uri, target.cid)`
      and resolves `root` from `target.record.decodeRecord<Post>()?.reply?.root`
      with fallback to `parent`
- [ ] 4.3 Wrap the decode in `runCatching` so records with unknown
      fields don't crash reply construction
- [ ] 4.4 In `createPost`, include `reply = replyTo?.let { buildReplyRef(it) }`
      on the constructed `Post`
- [ ] 4.5 `ComposeScreen` accepts a nullable `replyTo: PostView?`
      parameter and renders a "Replying to @handle" banner when set
- [ ] 4.6 Banner includes a "Cancel reply" link that calls
      `viewModel.setReplyTo(null)` and hides the banner, continuing
      as a top-level post

## 5. Reply entry points

- [ ] 5.1 `FocusedPostCard` includes a Reply action alongside Like /
      Delete (if own)
- [ ] 5.2 `ReplyRow` (the reply entries in the thread list) includes
      a Reply action
- [ ] 5.3 Placeholder rows for `NotFoundPost` / `BlockedPost` /
      `*Unknown` do NOT render a Reply action
- [ ] 5.4 Tapping Reply sets `screen = Compose(replyTo = post)` and
      calls `composeViewModel.setReplyTo(post)` so the banner and
      submission logic pick it up

## 6. Post-reply refetch

- [ ] 6.1 `ComposeViewModel.posted` (existing `SharedFlow<Boolean>`)
      already emits true on success; `ComposeScreen` collects it and
      calls a supplied `onPosted` callback
- [ ] 6.2 When `replyTo != null` at submission time, route `onPosted`
      to navigate to `Thread(rootUri = replyTo's root)` instead of
      back to `Feed`
- [ ] 6.3 The thread screen's `ThreadViewModel` re-triggers
      `loadThread` whenever `rootUri` changes (already the default
      for a fresh `Thread(...)` screen state)

## 7. Tests

- [ ] 7.1 Add `thread_happy.json` fixture under
      `samples/android/src/test/resources/fixtures/thread/` covering
      a post with one ancestor and three replies
- [ ] 7.2 Add `thread_not_found.json` fixture with `thread` as
      `NotFoundPost`
- [ ] 7.3 Add `thread_unknown.json` fixture with `thread` as a
      `ThreadViewPostUnknown` arm (unknown `$type`)
- [ ] 7.4 `ThreadViewModelTest`: happy fixture → `Loaded` state
      with correct ancestor/focused/reply ordering
- [ ] 7.5 `ThreadViewModelTest`: not-found fixture → `Unavailable`
      state with "This post was deleted." message
- [ ] 7.6 `ThreadViewModelTest`: unknown fixture → `Unavailable`
      state with "Thread unavailable." message
- [ ] 7.7 `ComposeViewModelReplyTest`: `buildReplyRef` with a
      top-level `PostView` → `root == parent`
- [ ] 7.8 `ComposeViewModelReplyTest`: `buildReplyRef` with a
      `PostView` whose decoded `Post.reply.root` is set → produced
      `root` matches the source root, not the direct parent
- [ ] 7.9 Run `./gradlew :samples:android:testDebugUnitTest` and
      confirm all new tests pass alongside existing ones

## 8. Build + manual verification

- [ ] 8.1 `./gradlew :samples:android:assembleDebug` builds
- [ ] 8.2 `./gradlew spotlessCheck` passes
- [ ] 8.3 Manual test on device: log in, tap a post in the feed,
      verify thread loads with ancestors + replies, tap Reply,
      submit a reply, verify the thread refreshes with your new
      reply visible
- [ ] 8.4 Manual test: tap a post whose author has since deleted it
      (or force by stubbing) — verify "This post was deleted."
      renders

## 9. Archive

- [ ] 9.1 `openspec status --change sample-replies-threading` reports
      4/4 artifacts complete
- [ ] 9.2 Archive change to
      `openspec/changes/archive/<date>-sample-replies-threading/` and
      sync the delta into `openspec/specs/android-sample/`
