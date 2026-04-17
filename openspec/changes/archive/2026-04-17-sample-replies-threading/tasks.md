## 1. Navigation state refactor

- [x] 1.1 Introduce `sealed interface LoggedInScreen` with `Feed`,
      `Thread(rootUri: AtUri)`, and `Compose(replyTo: PostView? = null)`
      variants in `MainActivity.kt` — landed in `MainUiState.kt` next
      to the existing sealed `MainUiState` for cohesion
- [x] 1.2 Replace `var showCompose by mutableStateOf(false)` with
      `var screen by mutableStateOf<LoggedInScreen>(Feed)`
- [x] 1.3 Route each screen variant to its composable in the `when`
      block; wire back-handlers to return to `Feed` (Compose back
      routes to Thread when a reply is in progress)

## 2. Thread screen

- [x] 2.1 Create `ThreadViewModel` (`@HiltViewModel`) with
      `uiState: StateFlow<ThreadUiState>` and a `loadThread(uri: AtUri)`
      entry point calling
      `FeedService(client).getPostThread(GetPostThreadRequest(uri, depth=6, parentHeight=4))`
- [x] 2.2 Define `ThreadUiState` as a sealed interface:
      `Loading`, `Loaded(ancestors, focused, replies, ancestorsTruncated)`,
      `Unavailable(message)`, `Error(message)`
- [x] 2.3 Handle all four arms of `GetPostThreadResponseThreadUnion`:
      `ThreadViewPost` → populate `Loaded`; `NotFoundPost` /
      `BlockedPost` / `Unknown` → populate `Unavailable` with the
      matching placeholder string
- [x] 2.4 Ancestor walk: recurse up `ThreadViewPost.parent` handling
      its own union arms; stop at root or first non-happy arm; flag
      `ancestorsTruncated` when the walk aborts
- [x] 2.5 Replies: flatten `ThreadViewPost.replies` once (no deeper
      recursion in v1); each entry dispatches over
      `ThreadViewPostRepliesUnion` arms, happy becomes
      `ThreadReplyItem.Reply`, non-happy becomes
      `ThreadReplyItem.Placeholder(message)`
- [x] 2.6 Create `ThreadScreen` composable: `LazyColumn` with
      ancestors block, focused post card, replies list; placeholder
      composable for unavailable arms; earlier-context-unavailable
      row when ancestor walk truncated
- [x] 2.7 Reuse `formatDatetime` + `extractFirstImageThumb` helpers
      (already `internal` on `FeedScreen.kt`); extract a
      `FocusedPostCard` composable
- [x] 2.8 Add a "Back" button via `TopAppBar` navigation icon on the
      thread screen

## 3. Feed tap → thread

- [x] 3.1 `FeedScreen` accepts an `onPostTap: (AtUri) -> Unit`
      callback
- [x] 3.2 `MainActivity` passes a callback that sets
      `screen = Thread(uri)` on the tapped post's URI
- [x] 3.3 `PostRow` wires `Modifier.clickable(onClick = onTap)` at
      the column level; `IconButton` children consume their own
      clicks so Like / Delete don't propagate

## 4. Reply composition

- [x] 4.1 Extend `ComposeViewModel` with a `MutableStateFlow<PostView?>
      _replyTo` (exposed as `StateFlow<PostView?> replyTo`) and a
      `setReplyTo(post: PostView?)` method
- [x] 4.2 Add a `companion object` `buildReplyRef(target: PostView)`
      helper that constructs `parent = StrongRef(target.uri, target.cid)`
      and resolves `root` from
      `target.record.decodeRecord<Post>().reply as? AtField.Defined` —
      falls back to `parent`
- [x] 4.3 Wrap the decode in `runCatching` so records with unknown
      fields or required-field gaps don't crash reply construction
- [x] 4.4 In `createPost`, set `reply = replyTo?.let { AtField.Defined(buildReplyRef(it)) } ?: AtField.Missing`
      (Post.reply is `AtField<PostReplyRef>`, not a plain nullable)
- [x] 4.5 `ComposeScreen` observes `viewModel.replyTo` and renders a
      banner when set; title flips to "Reply" and button label flips
      to "Reply"
- [x] 4.6 Banner includes a "Cancel reply" text button that calls
      `viewModel.setReplyTo(null)` — continues as a top-level post

## 5. Reply entry points

- [x] 5.1 `FocusedPostCard` includes a Reply action in the action row
- [x] 5.2 `ReplyRow` (the reply entries in the thread list) includes
      a Reply action
- [x] 5.3 `PlaceholderRow` (NotFoundPost / BlockedPost / Unknown)
      renders no action row — pure text
- [x] 5.4 Tapping Reply routes through `ThreadScreen`'s `onReply`
      callback → `MainActivity` sets `screen = Compose(replyTo=post)`
      and primes `composeViewModel.setReplyTo(post)`

## 6. Post-reply refetch

- [x] 6.1 `ComposeViewModel.posted` remains the success signal;
      `ComposeScreen` calls `onPosted` on `true`
- [x] 6.2 `MainActivity.onPosted` routes to `Thread(threadRootFor(replyTo))`
      when a reply context was set, or to `Feed` otherwise
- [x] 6.3 `ThreadScreen.LaunchedEffect(rootUri)` calls
      `viewModel.loadThread(rootUri)` so re-entering the screen with
      the same root triggers a fresh fetch

## 7. Tests

- [x] 7.1 Five `THREAD_*` inline-JSON fixtures in
      `ThreadViewModelTest` — matches the sample repo's existing
      `FeedScreenTest` style rather than introducing a separate
      resources-file fixture system
- [x] 7.2 Happy thread fixture covers ancestor + focused + 2 replies
- [x] 7.3 `notFoundAtRootYieldsUnavailablePlaceholder` exercises
      `NotFoundPost` root arm
- [x] 7.4 `unknownAtRootYieldsUnavailablePlaceholder` exercises
      `GetPostThreadResponseThreadUnion.Unknown` arm
- [x] 7.5 `blockedInReplyListProducesPlaceholderRow` verifies
      per-row `BlockedPost` handling inside the replies list
- [x] 7.6 `ancestorWalkStopsAtNotFound` verifies truncation when the
      direct parent is unavailable
- [x] 7.7 `ComposeViewModelReplyTest.replyToTopLevelPostSetsRootEqualToParent`
      — target has no `record.reply`
- [x] 7.8 `ComposeViewModelReplyTest.replyToReplyPreservesOriginalThreadRoot`
      — target has `record.reply.root` pointing elsewhere
- [x] 7.9 `replyToTargetWithUnparseableRecordFallsBackToTargetAsRoot`
      — decode failure path; `buildReplyRef` doesn't crash
- [x] 7.10 `./gradlew :samples:android:testDebugUnitTest` passes —
      8 new tests green alongside the existing FeedScreenTest suite

## 8. Build + manual verification

- [x] 8.1 `./gradlew :samples:android:assembleDebug` succeeds
- [x] 8.2 `./gradlew spotlessCheck` passes
- [ ] 8.3 Manual test on device: log in, tap a post in the feed,
      verify thread loads with ancestors + replies, tap Reply, submit
      a reply, verify the thread refreshes with the new reply —
      **deferred to maintainer smoke test** (cannot run on CI)
- [ ] 8.4 Manual test: tap a post whose author has since deleted it
      (or force by stubbing) — verify "This post was deleted."
      renders — **deferred to maintainer smoke test**

## 9. Archive

- [x] 9.1 `openspec status --change sample-replies-threading` reports
      4/4 artifacts complete
- [x] 9.2 Archive change to
      `openspec/changes/archive/<date>-sample-replies-threading/` and
      sync the delta into `openspec/specs/android-sample/`
