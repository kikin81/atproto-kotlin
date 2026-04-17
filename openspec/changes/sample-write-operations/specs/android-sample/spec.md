## ADDED Requirements

### Requirement: Sample SHALL support liking and unliking posts

The sample SHALL display a like button on each post in the feed. Tapping
the button on an unliked post SHALL call `com.atproto.repo.createRecord`
with a serialized `app.bsky.feed.like` record containing a `StrongRef` to
the post. Tapping the button on a liked post SHALL call
`com.atproto.repo.deleteRecord` to remove the like record. The like state
SHALL update optimistically in the UI.

#### Scenario: Liking a post

- **WHEN** the user taps the like button on an unliked post
- **THEN** the sample calls `RepoService.createRecord` with
  `collection = "app.bsky.feed.like"` and a record containing the post's
  `uri` and `cid` as a `StrongRef`
- **AND** the like count increments and the heart icon fills in the UI

#### Scenario: Unliking a post

- **WHEN** the user taps the like button on a previously liked post
- **THEN** the sample calls `RepoService.deleteRecord` with the like
  record's rkey extracted from the viewer's like URI
- **AND** the like count decrements and the heart icon empties in the UI

### Requirement: Sample SHALL support creating text posts

The sample SHALL provide a compose screen where the user can enter text
and create a new post via `com.atproto.repo.createRecord` with a
serialized `app.bsky.feed.post` record. The record SHALL include `text`
and `createdAt` fields. Optional fields (reply, embed, langs) SHALL be
omitted via `AtField.Missing`.

#### Scenario: Creating a text post

- **WHEN** the user types text and taps "Post" on the compose screen
- **THEN** the sample calls `RepoService.createRecord` with
  `collection = "app.bsky.feed.post"` and a record containing the text
  and current timestamp
- **AND** the user returns to the feed screen

### Requirement: Sample SHALL support deleting own posts

The sample SHALL allow the user to delete their own posts via
`com.atproto.repo.deleteRecord`. The delete action SHALL only appear on
posts authored by the logged-in user.

#### Scenario: Deleting own post

- **WHEN** the user triggers delete on a post they authored
- **THEN** the sample calls `RepoService.deleteRecord` with the post's
  collection and rkey extracted from the post URI
- **AND** the post is removed from the feed UI

### Requirement: Runtime SHALL provide encodeRecord for typed record serialization

The `:at-protocol-runtime` module SHALL provide a
`JsonObject.encodeRecord<T>()` extension that serializes a typed record
data class into a `JsonObject` suitable for `CreateRecordRequest.record`,
automatically injecting the `$type` discriminator field. This is the
inverse of the existing `decodeRecord<T>()`.

#### Scenario: Encoding a Like record

- **WHEN** `encodeRecord(Like.serializer(), like, "app.bsky.feed.like")` is called
- **THEN** the returned `JsonObject` contains the serialized Like fields
  plus a `$type` key with value `"app.bsky.feed.like"`
