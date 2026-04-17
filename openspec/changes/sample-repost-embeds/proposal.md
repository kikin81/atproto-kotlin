## Why

The Android sample currently collapses two distinct Bluesky feed concepts
into a plain text row:

1. **Reposts** (`FeedViewPost.reason = ReasonRepost`) render with the
   original author's handle and no attribution to the account that
   reposted them. From the viewer's perspective the post appears in the
   timeline "out of nowhere" — there is no "Reposted by @alice" header.
2. **Quote posts** (`PostView.embed = RecordView` /
   `RecordWithMediaView`) render the quoting user's comment text but
   silently drop the embedded original post. The feed shows a bare
   comment with no indication of what it refers to, which makes the
   thread unreadable.

Both variants are modeled correctly in the generated code (the union
arms exist in `FeedViewPostReasonUnion` and `PostViewEmbedUnion`), so
this is strictly a sample-UI gap. Surfacing reposts and quoted records
demonstrates how a downstream consumer pattern-matches the generated
open unions — the primary value the sample exists to show.

## What Changes

- **Repost attribution**: When a `FeedViewPost` carries a `ReasonRepost`
  reason, render a compact "Reposted by @handle" header above the post
  row using `reason.by.handle`.
- **Quote post rendering**: When a `PostView.embed` resolves to
  `RecordView` (quote-only) or `RecordWithMediaView` (quote + media),
  render the embedded `RecordViewRecord` as a bordered sub-card inside
  the outer post row, showing the quoted author handle, the quoted
  post's text (decoded from `value: JsonObject` via `decodeRecord<Post>()`),
  and the quoted post's first image thumbnail if present in `embeds`.
- **Graceful fallbacks for union variants we don't own**:
  `RecordViewNotFound`, `RecordViewBlocked`, `RecordViewDetached`, and
  the open-union `Unknown` arm all render a small italic placeholder
  ("Quoted post unavailable") instead of being dropped silently.
- **Media embeds inside quote+media**: The media half of
  `RecordWithMediaView` reuses the existing `ImagesView` thumbnail
  extractor so quote-with-image posts render both the quoted card and
  the new image.

## Capabilities

### Modified Capabilities

- `android-sample`: Feed row renders the `FeedViewPost.reason` header
  for reposts and the `PostView.embed` record/recordWithMedia variants
  as an embedded quoted card. Existing text / image / like / delete
  behavior is unchanged.

## Impact

- **samples/android**: `FeedScreen.kt` gains a repost header composable
  and a quoted-record sub-card composable. `extractFirstImageThumb` is
  extended (or a sibling helper is added) to unwrap
  `RecordWithMediaView.media` before falling through to `ImagesView`.
  `FeedScreenTest` gains fixtures exercising repost + quote-post JSON.
- **at-protocol-runtime / at-protocol-models / at-protocol-generator**:
  No changes. Existing generated models already expose every field the
  sample needs.
- **Breaking changes**: None. This change is additive to the sample
  module only and does not alter any library module or published API.
