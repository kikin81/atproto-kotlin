## ADDED Requirements

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
