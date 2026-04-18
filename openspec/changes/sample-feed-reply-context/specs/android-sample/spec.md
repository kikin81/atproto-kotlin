## ADDED Requirements

### Requirement: Feed SHALL render parent post context for reply entries

The feed SHALL, for every `FeedViewPost` whose `reply` field is
non-null, render a compact parent-context row above the main post
content. The parent SHALL be extracted from `FeedViewPost.reply.parent`
(a `ReplyRefParentUnion`) without any additional network call — the
AppView pre-loads the parent onto the reply entry.

The context row's position in the post card SHALL be: below the
repost-reason header (if any) and above the author / timestamp row
of the main post. Top-level posts (entry.reply == null) SHALL render
exactly as they do today.

#### Scenario: Reply entry renders parent context above the reply

- **GIVEN** a `FeedViewPost` with `reply.parent` as a `PostView`
  authored by `@alice.bsky.social` with text "original post"
- **WHEN** the feed row renders
- **THEN** a compact context row appears above the main post showing
  the `@alice.bsky.social` handle and an excerpt of "original post"
- **AND** the main post's author handle and text follow immediately
  below the context row

#### Scenario: Top-level post renders with no context row

- **GIVEN** a `FeedViewPost` whose `reply` field is null
- **WHEN** the feed row renders
- **THEN** no parent context row is rendered
- **AND** the post renders exactly as it does for standalone posts
  today

### Requirement: Parent-context row SHALL handle all ReplyRefParentUnion arms

The parent-context dispatch SHALL handle every arm of
`ReplyRefParentUnion`:

- `PostView` — render full context (author + text excerpt)
- `NotFoundPost` — render italic "Replying to [deleted post]"
- `BlockedPost` — render italic "Replying to [blocked account]"
- `ReplyRefParentUnion.Unknown` — render italic
  "Replying to [unavailable]"

Non-happy arms SHALL NOT expose a tap handler (there's no parent URI
to navigate to), and SHALL NOT cause the reply itself to be dropped
from the feed.

#### Scenario: Deleted parent renders a placeholder

- **GIVEN** a reply entry whose `reply.parent` is a `NotFoundPost`
- **WHEN** the feed row renders
- **THEN** the context slot shows italic text "Replying to
  [deleted post]"
- **AND** the reply's own content and actions render normally below

#### Scenario: Blocked parent renders a placeholder

- **GIVEN** a reply entry whose `reply.parent` is a `BlockedPost`
- **WHEN** the feed row renders
- **THEN** the context slot shows italic text "Replying to
  [blocked account]"

#### Scenario: Unknown parent arm renders fallback

- **GIVEN** a reply entry whose `reply.parent` is a
  `ReplyRefParentUnion.Unknown` (new lexicon variant the sample's
  model set doesn't know)
- **WHEN** the feed row renders
- **THEN** the context slot shows italic text "Replying to
  [unavailable]"
- **AND** the row does not crash or drop the reply

### Requirement: Parent-context row SHALL be tappable and navigate to the parent's thread

Parent-context rows SHALL call the existing `onPostTap(AtUri)`
callback with the parent's `uri` when tapped, but only for rows
backed by a `PostView` arm. Placeholder rows derived from
`NotFoundPost`, `BlockedPost`, or `ReplyRefParentUnion.Unknown` SHALL
NOT be tappable, because no parent URI is available to route to.

#### Scenario: Tap on parent context opens parent's thread

- **GIVEN** a feed row whose parent context is a `PostView` with
  `uri = "at://did:plc:alice/app.bsky.feed.post/root"`
- **WHEN** the user taps the context row
- **THEN** the sample navigates to the thread screen for
  `at://did:plc:alice/app.bsky.feed.post/root`

#### Scenario: Placeholder context is not tappable

- **GIVEN** a feed row whose parent context is a `NotFoundPost`
  placeholder
- **WHEN** the user taps anywhere on the placeholder text
- **THEN** no navigation occurs
- **AND** taps on the main post body below still route to the
  reply's own thread as today

### Requirement: Feed SHALL render repost-of-reply entries with all three layers

The feed row SHALL render three stacked elements from top to bottom
for any `FeedViewPost` that carries both a `reason` of `ReasonRepost`
and a non-null `reply` field:

1. Repost header ("Reposted by @handle")
2. Parent-context row (per `ReplyRefParentUnion` dispatch rules above)
3. The main post body (the reply itself)

Each element SHALL render using its own existing rendering logic —
no combined special case.

#### Scenario: Repost of a reply renders all three layers in order

- **GIVEN** a `FeedViewPost` with:
  - `reason` = `ReasonRepost(by = @bob.bsky.social)`
  - `reply.parent` = `PostView(author = @alice.bsky.social, text = "original")`
  - `post` = the reply itself authored by `@carol.bsky.social`
- **WHEN** the feed row renders
- **THEN** the visual order from top to bottom is:
  1. "Reposted by @bob.bsky.social"
  2. Parent context showing `@alice.bsky.social` and "original"
  3. `@carol.bsky.social` and the reply text

### Requirement: Sample SHALL unit-test parent-context extraction

`FeedScreenTest` SHALL include canned `GetTimelineResponse` fixtures
covering:

1. A reply entry with a known `PostView` parent
2. A reply entry with a `NotFoundPost` parent
3. A reply entry with a `BlockedPost` parent
4. A reply entry with a `ReplyRefParentUnion.Unknown` parent
5. A top-level post with no `reply` field (regression test)
6. A `FeedViewPost` carrying both `ReasonRepost` and a `reply` field

Tests SHALL exercise the `extractReplyParent` helper directly and
assert the resulting `ReplyParentInfo` shape.

#### Scenario: Extraction yields typed parent for known post

- **GIVEN** a canned `FeedViewPost` JSON with `reply.parent` as
  `app.bsky.feed.defs#postView`
- **WHEN** `extractReplyParent(entry)` runs
- **THEN** the return value is a `ReplyParentInfo.Post` whose `view`
  carries the expected author handle

#### Scenario: Extraction yields unavailable for non-happy arms

- **GIVEN** canned fixtures for `NotFoundPost`, `BlockedPost`, and
  `ReplyRefParentUnion.Unknown` parent arms
- **WHEN** `extractReplyParent(entry)` runs on each
- **THEN** each return value is a `ReplyParentInfo.Unavailable`
  carrying the matching localized message

#### Scenario: Top-level post extraction returns null

- **GIVEN** a canned `FeedViewPost` with no `reply` field
- **WHEN** `extractReplyParent(entry)` runs
- **THEN** the return value is null
