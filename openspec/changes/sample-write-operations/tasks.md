## 1. Runtime: encodeRecord helper

- [x] 1.1 Add `encodeRecord(serializer, record, type)` to `RecordDecoder.kt` that serializes a typed record to `JsonObject` with `$type` injected
- [x] 1.2 Unit-test: encode a `Like` record and verify the output contains `$type`, `subject`, and `createdAt`
- [x] 1.3 Unit-test: encode a record with `AtField.Missing` fields and verify those keys are absent from the output

## 2. Datetime.now() helper

- [x] 2.1 Add a `datetimeNow()` helper in the sample module (JVM `Instant.now()` → `Datetime`)

## 3. Like/Unlike toggle

- [x] 3.1 Add a like button (heart icon) to `PostRow` in `FeedScreen.kt` that shows filled/unfilled state based on `post.viewer?.like`
- [x] 3.2 Add `likePost(post)` method to `FeedViewModel` that calls `RepoService.createRecord` with a serialized `Like` record
- [x] 3.3 Add `unlikePost(post)` method to `FeedViewModel` that extracts the rkey from `post.viewer?.like` and calls `RepoService.deleteRecord`
- [x] 3.4 Implement optimistic UI update: toggle like state and count in the local feed StateFlow immediately, revert on failure
- [x] 3.5 Display like count next to the heart icon
- [ ] 3.6 Manual test: like a post on device, verify it appears liked in the Bluesky app
- [ ] 3.7 Manual test: unlike a post on device, verify it appears unliked in the Bluesky app

## 4. Post creation

- [x] 4.1 Create `ComposeScreen.kt` with a text field, character counter (300 grapheme limit), and Post button
- [x] 4.2 Create `ComposeViewModel.kt` with `text` StateFlow, `posting` loading state, and `createPost()` method
- [x] 4.3 Implement `createPost()`: serialize a `Post` record via `encodeRecord` and call `RepoService.createRecord`
- [x] 4.4 Add a FAB (floating action button) to `FeedScreen` that navigates to `ComposeScreen`
- [x] 4.5 Wire navigation: on successful post, pop back to feed and trigger refresh
- [ ] 4.6 Manual test: create a post on device, verify it appears in the Bluesky app

## 5. Post deletion

- [x] 5.1 Add a delete action (icon or menu) on posts where `post.author.did == session.did`
- [x] 5.2 Add `deletePost(post)` method to `FeedViewModel` that extracts rkey from `post.uri` and calls `RepoService.deleteRecord`
- [x] 5.3 Remove the post from the local feed StateFlow after successful deletion
- [ ] 5.4 Manual test: delete own post on device, verify it's removed from the Bluesky app

## 6. ViewerState typed access

- [x] 6.1 Add helper to extract viewer like URI from `PostView.viewer` (currently `JsonObject?`, needs `decodeRecord` or field access)

## 7. Build verification

- [x] 7.1 `./gradlew :samples:android:assembleDebug` builds successfully
- [x] 7.2 `./gradlew :samples:android:testDebugUnitTest` passes
- [x] 7.3 `./gradlew :at-protocol-runtime:jvmTest` passes (encodeRecord tests)
- [x] 7.4 `./gradlew spotlessCheck` passes
- [x] 7.5 `pre-commit run --all-files` passes
