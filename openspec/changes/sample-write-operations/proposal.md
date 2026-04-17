## Why

The sample app is read-only — it authenticates and renders a timeline but
can't interact with it. Write operations are the ultimate stress test for
the generated SDK: they exercise `ProcedureDef` service methods,
`AtField<T>` three-state serialization (Missing vs Null vs Defined),
`createRecord`/`deleteRecord` flows, and DPoP-authenticated mutations
against a real PDS.

Adding like/unlike, post creation, and post deletion to the sample
validates that every mutation path in the generated API works end-to-end
and gives consumers a reference implementation for the most common
write patterns.

## What Changes

- **Like/Unlike toggle**: Tap a heart icon on any feed post to create or
  delete a `app.bsky.feed.like` record via `com.atproto.repo.createRecord` /
  `com.atproto.repo.deleteRecord`. Exercises basic procedure call
  serialization, `StrongRef` construction, and `Nsid`/`RecordKey` handling.
- **Post creation**: A compose screen with a text field and "Post" button
  that calls `createRecord` with a serialized `app.bsky.feed.post` record.
  Exercises `AtField` on optional fields (reply, embed, langs, labels).
- **Post deletion**: Swipe-to-delete or long-press delete on the user's own
  posts via `deleteRecord`. Exercises URI parsing to extract the rkey.
- **Record JSON helper**: A `JsonObject.encodeRecord<T>()` runtime extension
  (inverse of the existing `decodeRecord<T>()`) to serialize typed record
  classes into the `JsonObject` that `createRecord.record` expects, including
  the required `$type` discriminator field.

## Capabilities

### New Capabilities

_(none — this extends the existing sample, no new modules)_

### Modified Capabilities

- `android-sample`: Sample gains write operations (like, post, delete) and
  a compose/create screen.

## Impact

- **at-protocol-runtime**: Adds `JsonObject.encodeRecord<T>()` extension
  (inverse of `decodeRecord<T>()`). Small, additive.
- **samples/android**: New screens, ViewModels, and UI for compose and
  interactions. FeedScreen gains like button and delete action.
- **at-protocol-models/generator**: No changes. The generated
  `RepoService.createRecord`, `RepoService.deleteRecord`, `Like`, and `Post`
  classes are used as-is.
- **Breaking changes**: None.
