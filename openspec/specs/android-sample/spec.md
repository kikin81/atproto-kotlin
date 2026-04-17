# android-sample

Minimal Android reference application that consumes `:at-protocol-runtime` and
`:at-protocol-models` to demonstrate how a downstream consumer wires the
generated AT Protocol API surface into a real UI. Covers the
login → session persistence → authenticated XRPC call → feed render loop.

## Requirements

### Requirement: Sample module SHALL depend on the library via project coordinates only

The `samples/android/` module SHALL consume `:at-protocol-runtime` and
`:at-protocol-models` through `project(...)` Gradle dependencies, never
through Maven coordinates. The sample SHALL NOT require any
`publishToMavenLocal` step or Maven Central artifact to build and run.

#### Scenario: Fresh clone can build and run the sample without publishing

- **WHEN** a developer clones the repository, runs
  `cd at-protocol-generator && npx lex install --ci && cd - && ./gradlew :samples:android:installDebug`
- **THEN** the sample APK builds and installs on a connected device without
  any `publishToMavenLocal` or Maven Central network access, and the
  installed app launches to the login screen.

#### Scenario: Generator change is immediately visible in the sample

- **WHEN** a developer modifies `:at-protocol-generator` emission logic and
  runs `./gradlew :samples:android:assembleDebug`
- **THEN** the Gradle task graph rebuilds `:at-protocol-models` from the
  regenerated sources and the sample picks up the new models on the next
  app launch — no intermediate publish step is required.

### Requirement: Sample SHALL authenticate via the generated createSession procedure

The sample SHALL authenticate users by calling the generated
`com.atproto.server.createSession` procedure through `XrpcClient`, using
the handle and app-password supplied on the login screen. The sample
SHALL NOT implement OAuth 2.0, DPoP, PAR, or PKCE in v1. The sample's
README and login screen SHALL label app-password as a deprecated stopgap
and direct readers to the follow-up `atproto-oauth-runtime` change.

#### Scenario: Successful login with handle and app-password

- **WHEN** the user enters a valid handle and app-password on the login
  screen and taps "Sign in"
- **THEN** the sample calls `createSession` via `XrpcClient`, receives a
  `CreateSessionResponse` containing `accessJwt`, `refreshJwt`, `did`, and
  `handle`, persists them to `EncryptedSharedPreferences`, and transitions
  to the feed screen.

#### Scenario: Invalid credentials surface a typed error

- **WHEN** the user enters an invalid handle or app-password and taps
  "Sign in"
- **THEN** the sample catches the typed `XrpcError` returned by
  `createSession`, surfaces the error message on the login screen, and
  leaves the login form populated so the user can retry.

#### Scenario: README warns about app-password deprecation

- **WHEN** a developer opens `samples/android/README.md`
- **THEN** the README contains a "NOT FOR PRODUCTION" banner in the first
  paragraph, explains that app-password is deprecated in favor of OAuth,
  and links to the `atproto-oauth-runtime` follow-up change.

### Requirement: Sample SHALL persist session state in EncryptedSharedPreferences

The sample SHALL persist the authenticated session (access JWT, refresh
JWT, DID, handle, PDS URL) in `EncryptedSharedPreferences` backed by the
Android Keystore master key. Plain `SharedPreferences`, files on external
storage, and in-memory-only storage are NOT acceptable. On subsequent
launches, the sample SHALL read the persisted session and skip the login
screen if it is present.

#### Scenario: Session survives app restart

- **WHEN** a user logs in successfully and then force-stops and relaunches
  the app
- **THEN** the sample reads the persisted session from
  `EncryptedSharedPreferences` during `MainActivity.onCreate`, constructs
  the authenticated `XrpcClient`, and renders the feed screen directly
  without prompting for credentials.

#### Scenario: Logout clears persisted session

- **WHEN** the user taps the logout action on the feed screen
- **THEN** the sample removes the session entry from
  `EncryptedSharedPreferences` and returns to the login screen. A
  subsequent app restart starts fresh at the login screen.

### Requirement: Sample SHALL render the getTimeline response via generated models

On successful login (or relaunch with persisted session), the sample SHALL
call the generated `app.bsky.feed.getTimeline` query through `XrpcClient`
and render the returned `GetTimelineResponse.feed` list as a Compose
`LazyColumn`. Each row SHALL display at minimum: the post author's
`handle`, the post `text`, and the `createdAt` timestamp rendered as a
human-readable string. If a post's `embed` field resolves to an
`app.bsky.embed.images#view` union variant, the row SHALL additionally
render the first image's thumbnail via the typed `Blob` reference.

#### Scenario: Feed renders text posts with author and timestamp

- **WHEN** `getTimeline` returns a list of posts with no embeds
- **THEN** the sample renders a `LazyColumn` where each row shows the
  author handle, the post text, and a human-readable `createdAt`
  timestamp, in that order, with no image thumbnails.

#### Scenario: Feed renders image embed when the post's embed is an images view

- **WHEN** `getTimeline` returns a post whose `embed` field deserializes
  to the `app.bsky.embed.images#view` arm of the post-embed open union
- **THEN** the sample pattern-matches the union, extracts the first image
  from the view's `images` list, and renders the `thumb` URL in the post
  row. Posts with other embed variants (record, external, recordWithMedia)
  SHALL render without an image but SHALL NOT crash.

#### Scenario: Unknown embed variant falls back gracefully

- **WHEN** `getTimeline` returns a post whose `embed` field deserializes
  to the `Unknown` arm of the post-embed open union (because the lexicon
  corpus the sample was built against is older than the server)
- **THEN** the sample detects the `Unknown` variant and renders the post
  row without an image. The sample SHALL NOT throw or drop the post from
  the feed.

### Requirement: Sample SHALL ship a unit smoke test using MockEngine

The sample module SHALL include at least one unit test that constructs
`XrpcClient` backed by Ktor's `MockEngine`, exercises the login
(`createSession`) → feed (`getTimeline`) flow against canned JSON
responses, and asserts the session store and UI state holder transition
correctly. The test SHALL NOT depend on network access or a live AT
Protocol server.

#### Scenario: Login + feed flow works against MockEngine

- **WHEN** a unit test runs the sample's `AuthState` reducer with a
  `MockEngine` that returns a canned `CreateSessionResponse` followed by a
  canned `GetTimelineResponse` with at least one post carrying an images
  embed
- **THEN** the final state is `AuthState.LoggedIn(session)` with the
  session fields populated from the mock response, the timeline list
  contains the mock post, and the post's embed has been correctly
  pattern-matched to the images-view union arm.

### Requirement: Sample SHALL NOT modify the library modules

The `samples/android/` change SHALL be additive only. It SHALL NOT modify
files under `at-protocol-runtime/`, `at-protocol-models/`, or
`at-protocol-generator/`. It SHALL NOT add an `androidTarget()` to the
KMP configuration of the runtime or models modules. It SHALL NOT
introduce AGP into the root `build.gradle.kts` or any module other than
`samples/android/`.

#### Scenario: Library modules are untouched

- **WHEN** the samples-android-bluesky-feed change is archived
- **THEN** a `git diff` between the pre-change and post-change main
  branches shows zero modifications to any file under
  `at-protocol-runtime/`, `at-protocol-models/`, or
  `at-protocol-generator/` other than (a) new samples module wiring and
  (b) one `include(":samples:android")` line in `settings.gradle.kts`.

#### Scenario: KMP targets are unchanged

- **WHEN** `./gradlew :at-protocol-runtime:tasks` and
  `./gradlew :at-protocol-models:tasks` are run before and after the
  samples change
- **THEN** the list of compile tasks for each KMP module is identical.
  No `compileKotlinAndroid` or `assembleRelease` tasks appear on the
  library modules.
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

### Requirement: Sample SHALL display repost attribution for ReasonRepost feed entries

When a `FeedViewPost` returned by `app.bsky.feed.getTimeline` carries a
`reason` that deserializes to the `ReasonRepost` arm of
`FeedViewPostReasonUnion`, the feed row SHALL render a "Reposted by
@{handle}" header above the original post content, sourced from
`reason.by.handle`. Entries with a `null` reason, a `ReasonPin` reason,
or the open-union `Unknown` reason arm SHALL render without the
repost header and SHALL NOT crash.

#### Scenario: Repost reason surfaces the reposter handle

- **WHEN** the timeline contains a `FeedViewPost` whose `reason`
  deserializes to `ReasonRepost(by = ProfileViewBasic(handle = "alice.bsky.social"), ...)`
- **THEN** the row renders the text "Reposted by @alice.bsky.social"
  above the existing author handle and timestamp row
- **AND** the original post's author handle, text, and any image embed
  continue to render unchanged below the header

#### Scenario: Non-repost reasons render without a header

- **WHEN** the timeline contains entries whose `reason` is `null`,
  a `ReasonPin`, or the `Unknown` arm of `FeedViewPostReasonUnion`
- **THEN** the row renders no repost header
- **AND** no exception is thrown during composition

### Requirement: Sample SHALL render the embedded quoted record for RecordView embeds

When a `PostView.embed` deserializes to the `RecordView` arm of
`PostViewEmbedUnion` and `RecordView.record` is a `RecordViewRecord`,
the feed row SHALL render a bordered sub-card containing the quoted
author handle, the quoted post text (decoded from `RecordViewRecord.value`
as `app.bsky.feed.Post`), and the first image thumbnail from the
quoted post's `embeds`, if one is present.

#### Scenario: Quote post renders both comment and embedded record

- **WHEN** the timeline contains a `PostView` whose own record has
  non-empty text "check this out" and whose `embed` field deserializes
  to `RecordView(record = RecordViewRecord(author = alice, value = {...text: "original post"...}))`
- **THEN** the row renders the outer comment text "check this out"
- **AND** below it, a bordered sub-card renders "@alice…" as the
  quoted author and "original post" as the quoted text

#### Scenario: Quoted record value that fails to decode renders only the author

- **WHEN** the quoted `RecordViewRecord.value` JSON cannot be decoded
  into `app.bsky.feed.Post` (e.g. because the record is a custom
  collection outside the sample's known types)
- **THEN** the quoted sub-card still renders with the quoted author
  handle
- **AND** the sample does not crash or drop the outer post from the feed

### Requirement: Sample SHALL render quoted record and media together for RecordWithMediaView embeds

When a `PostView.embed` deserializes to the `RecordWithMediaView` arm
of `PostViewEmbedUnion`, the feed row SHALL render both (a) the
embedded quoted record sub-card described above, extracted from
`RecordWithMediaView.record.record`, and (b) the first image thumbnail
extracted from `RecordWithMediaView.media` when that media union is
the `ImagesView` arm. Non-image media arms (video, external, Unknown)
SHALL render the quoted card with no outer thumbnail, without crashing.

#### Scenario: Quote + image embed renders the quoted card above the thumbnail

- **WHEN** the timeline contains a `PostView` whose `embed` deserializes
  to `RecordWithMediaView(record = RecordView(record = RecordViewRecord(...)), media = ImagesView(images = [img, ...]))`
- **THEN** the row renders the quoted record sub-card (author + text)
- **AND** below the sub-card renders the first image's `thumb` URL
  via `AsyncImage`

#### Scenario: Quote + non-image media renders only the quoted card

- **WHEN** the `media` arm of `RecordWithMediaView` is `VideoView`,
  `ExternalView`, or the open-union `Unknown` variant
- **THEN** the row renders the quoted record sub-card
- **AND** renders no outer media thumbnail
- **AND** does not crash

### Requirement: Sample SHALL render a graceful placeholder for unavailable quoted records

When a `PostView.embed` is `RecordView` or `RecordWithMediaView` but
the inner `record` resolves to `RecordViewNotFound`,
`RecordViewBlocked`, `RecordViewDetached`, or the `Unknown` arm of
`RecordViewRecordUnion`, the feed row SHALL render a small italic
placeholder string in place of the quoted sub-card rather than dropping
the embed silently. The outer post's text, author, and like/delete
controls SHALL render unchanged.

#### Scenario: Blocked quoted record renders a placeholder

- **WHEN** `PostView.embed = RecordView(record = RecordViewBlocked(...))`
- **THEN** the row renders an italic placeholder reading "Quoted post
  from a blocked account" (or equivalent) in the location the quoted
  sub-card would otherwise occupy
- **AND** the outer post renders normally

#### Scenario: Unknown quoted record arm renders a placeholder

- **WHEN** the inner `record` resolves to the `Unknown` arm of
  `RecordViewRecordUnion` because the server returned a newer lexicon
  type than the sample knows
- **THEN** the row renders an italic placeholder reading "Quoted post
  unavailable" (or equivalent) and does not drop the outer post or
  throw
