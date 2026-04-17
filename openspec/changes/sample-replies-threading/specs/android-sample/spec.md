## ADDED Requirements

### Requirement: Sample SHALL load a thread via getPostThread when a feed post is tapped

The sample SHALL wire a tap handler on every feed row that navigates
to a thread screen for that post's URI. The thread screen SHALL call
`FeedService.getPostThread(GetPostThreadRequest(uri = postUri))` via
the authenticated `XrpcClient` and render the response.

#### Scenario: Feed tap opens the thread screen

- **GIVEN** the user is on the feed screen with posts loaded
- **WHEN** the user taps a post row
- **THEN** the sample navigates to `ThreadScreen` with that post's
  URI
- **AND** `FeedService.getPostThread` is called with the tapped
  post's URI
- **AND** the thread screen shows a loading indicator until the
  response arrives

### Requirement: Thread screen SHALL render ancestors, focused post, and replies

For a happy-path `ThreadViewPost` response, the thread screen SHALL
render three sections in order: ancestors (root post down to the
focused post's direct parent), the focused post itself with emphasis,
and the focused post's direct replies as a flat list.

Ancestor walking SHALL follow `ThreadViewPost.parent` recursively
through the `ThreadViewPostParent` union until either a root post
(no parent) or a non-`ThreadViewPost` arm is reached.

#### Scenario: Thread with one ancestor and two replies renders correctly

- **GIVEN** `getPostThread` returns a `ThreadViewPost` whose
  `parent` is a `ThreadViewPost` (the thread root) and whose
  `replies` is a list of two `ThreadViewPost` items
- **WHEN** the user is on the thread screen
- **THEN** the screen displays four post cards in order: ancestor,
  focused post (highlighted), reply 1, reply 2
- **AND** each reply is visually distinguished from the focused
  post (e.g. indented or depth-marked)

### Requirement: Thread screen SHALL render placeholders for non-happy thread union arms

The thread screen SHALL handle every arm of the
`GetPostThreadResponseThread`, `ThreadViewPostParent`, and
`ThreadViewPostReply` unions. For non-happy arms:

- `NotFoundPost` → "This post was deleted."
- `BlockedPost` → "This post is unavailable."
- `*Unknown` → "Thread unavailable."

Placeholders SHALL be rendered in the same slot as the post they
replace so the thread's structural order is preserved.

#### Scenario: Thread root is a NotFoundPost

- **GIVEN** `getPostThread` returns `thread = NotFoundPost(...)`
- **WHEN** the thread screen finishes loading
- **THEN** the screen displays the "This post was deleted."
  placeholder centered with a "Back" button
- **AND** does not crash or throw

#### Scenario: A reply in the list is a BlockedPost

- **GIVEN** `getPostThread` returns a `ThreadViewPost` with a
  `replies` list containing two `ThreadViewPost` replies and one
  `BlockedPost`
- **WHEN** the thread screen renders
- **THEN** the blocked entry's slot shows the "This post is
  unavailable." placeholder rather than a post card

#### Scenario: Root arm is ThreadViewPostUnknown

- **GIVEN** `getPostThread` returns a `thread` of the
  `ThreadViewPostUnknown` arm
- **WHEN** the thread screen renders
- **THEN** the screen displays the generic "Thread unavailable."
  placeholder and does not attempt to load ancestors or replies

### Requirement: Sample SHALL support composing replies with correct ReplyRef

The compose screen SHALL accept an optional `replyTo: PostView`
context. When set, `ComposeViewModel.createPost` SHALL construct
`Post.reply` as a `ReplyRef` where:

- `parent` is a `StrongRef` over `replyTo.uri` + `replyTo.cid`
- `root` is the value of `replyTo`'s own `record.reply.root` if
  `replyTo` is itself a reply; otherwise `root` equals `parent`

#### Scenario: Replying to a top-level post

- **GIVEN** a `PostView` that is not itself a reply (its decoded
  `Post` has `reply == null`)
- **WHEN** the user taps Reply, enters text, and taps Post
- **THEN** the created `Post` has
  `reply.parent.uri == replyTo.uri`,
  `reply.parent.cid == replyTo.cid`,
  and `reply.root == reply.parent`
- **AND** `createRecord` is called with this record

#### Scenario: Replying to a reply preserves the thread root

- **GIVEN** a `PostView` whose decoded `Post.reply.root` points at
  a different post (i.e. it's a nested reply)
- **WHEN** the user submits a reply
- **THEN** the created `Post.reply.root` equals the original
  thread's root (from the `replyTo`'s own `record.reply.root`)
- **AND** `reply.parent` equals the direct `replyTo`, not the root

### Requirement: Thread screen SHALL expose Reply entry points for each post

The thread screen SHALL render a "Reply" action on the focused post
and on every reply row that renders as a happy `ThreadViewPost`.
Tapping "Reply" SHALL open the compose screen with the tapped post
as `replyTo`. Placeholder arms (`NotFoundPost`, `BlockedPost`,
`*Unknown`) SHALL NOT render a Reply action.

#### Scenario: Reply button on focused post

- **WHEN** the user is on the thread screen and taps Reply on the
  focused post
- **THEN** the sample navigates to the compose screen
- **AND** the compose screen displays a "Replying to @handle" banner
- **AND** submitting the compose creates a Post whose `reply.parent`
  is the focused post's `StrongRef`

#### Scenario: Reply button omitted on blocked arms

- **GIVEN** a reply row in the thread screen rendered from a
  `BlockedPost` placeholder
- **WHEN** the user inspects the action row on that placeholder
- **THEN** no Reply action is present

### Requirement: Sample SHALL refetch the thread after a successful reply

The sample SHALL, on successful reply creation (when
`ComposeViewModel.createPost` returns from `createRecord` without
error while a `replyTo` context was set), navigate back to the thread
screen for the reply's root URI and SHALL trigger a fresh
`getPostThread` call. Optimistic insertion of the new reply into the
existing UI state is NOT required in v1.

#### Scenario: Successful reply triggers thread refetch

- **GIVEN** the user is on the compose screen with a `replyTo` context
- **WHEN** `createPost` returns successfully
- **THEN** the sample navigates to the thread screen for the thread's
  root URI
- **AND** a fresh `getPostThread` call is issued
- **AND** the new reply appears in the refreshed reply list (assuming
  server consistency)

### Requirement: Sample SHALL unit-test thread rendering and reply construction

The sample module SHALL include JUnit 5 tests that cover:

1. `ThreadViewModel` with canned `GetPostThreadResponse` fixtures
   for happy path, `NotFoundPost` root, and `*Unknown` root arms.
2. `ComposeViewModel.buildReplyRef` (or equivalent) with a
   top-level target (verifies `root == parent`) and with a nested
   target (verifies `root` inherits from the target's
   `record.reply.root`).

Tests SHALL use MockEngine (Ktor) for HTTP and live under
`samples/android/src/test/`.

#### Scenario: Canned fixture drives ThreadViewModel

- **GIVEN** a JSON fixture `thread_happy.json` under
  `samples/android/src/test/resources/fixtures/thread/`
- **WHEN** `ThreadViewModel.loadThread(uri)` runs against a
  MockEngine that returns that fixture
- **THEN** the emitted UI state contains the expected ancestor,
  focused, and reply posts in the expected order
