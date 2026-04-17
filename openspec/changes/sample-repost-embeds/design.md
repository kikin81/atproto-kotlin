## Overview

Extend `FeedScreen`'s `PostRow` to surface two feed concepts the sample
currently ignores: the `FeedViewPost.reason` header for reposts, and
the quoted-record arms of `PostView.embed` for quote posts. No library
module is touched — the generated models already model these cases
via the open unions `FeedViewPostReasonUnion` and `PostViewEmbedUnion`.

## Data model recap (from generated code)

```kotlin
// app.bsky.feed.FeedViewPost
data class FeedViewPost(
    val post: PostView,
    val reason: FeedViewPostReasonUnion? = null,  // ReasonRepost | ReasonPin | Unknown
    val reply: ReplyRef? = null,
    ...
)

// app.bsky.feed.ReasonRepost  (one arm of FeedViewPostReasonUnion)
data class ReasonRepost(
    val by: ProfileViewBasic,   // contains .handle, .displayName, .avatar
    val indexedAt: Datetime,
    val uri: AtUri? = null,
    val cid: Cid? = null,
) : FeedViewPostReasonUnion

// app.bsky.embed.RecordView  (one arm of PostViewEmbedUnion)
data class RecordView(
    val record: RecordViewRecordUnion,  // RecordViewRecord | NotFound | Blocked | Detached | Unknown
) : PostViewEmbedUnion

// app.bsky.embed.RecordWithMediaView  (another arm)
data class RecordWithMediaView(
    val record: RecordView,
    val media: RecordWithMediaViewMediaUnion,  // ImagesView | VideoView | ExternalView | Unknown
) : PostViewEmbedUnion

// The actual quoted post payload
data class RecordViewRecord(
    val author: ProfileViewBasic,
    val value: JsonObject,               // decode to app.bsky.feed.Post for text/createdAt
    val embeds: List<RecordViewRecordEmbedsUnion>? = null,
    ...
)
```

## Repost header

In `PostRow`, before the existing author/handle row, pattern-match on
`entry.reason`:

```kotlin
@Composable
private fun RepostHeader(reason: FeedViewPostReasonUnion?) {
    val repost = reason as? ReasonRepost ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        Icon(Icons.Filled.Repeat, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.size(6.dp))
        Text(
            "Reposted by @${repost.by.handle.raw}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

`ReasonPin` and the `Unknown` arm render nothing (the header just does
not appear). This keeps the change focused on reposts; pinned-post UI
can follow later.

Call site in `PostRow` becomes:

```kotlin
Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
    RepostHeader(entry.reason)   // NEW
    Row(...) { /* existing author + timestamp */ }
    ...
}
```

That requires threading `FeedViewPost` (not just `PostView`) into
`PostRow`. The existing `entry` already carries it at the call site.

## Quoted record card

Add a helper that extracts the quoted record, regardless of which
embed arm is in use:

```kotlin
internal fun extractQuotedRecord(post: PostView): RecordViewRecord? =
    when (val embed = post.embed) {
        is RecordView -> embed.record as? RecordViewRecord
        is RecordWithMediaView -> embed.record.record as? RecordViewRecord
        else -> null
    }
```

The `as? RecordViewRecord` cast filters `NotFound` / `Blocked` /
`Detached` / `Unknown` arms of `RecordViewRecordUnion` — those render
as a separate placeholder composable:

```kotlin
internal fun extractQuotedPlaceholder(post: PostView): String? =
    when (val embed = post.embed) {
        is RecordView -> placeholderFor(embed.record)
        is RecordWithMediaView -> placeholderFor(embed.record.record)
        else -> null
    }

private fun placeholderFor(union: RecordViewRecordUnion): String? = when (union) {
    is RecordViewNotFound -> "Quoted post not found"
    is RecordViewBlocked -> "Quoted post from a blocked account"
    is RecordViewDetached -> "Quoted post was detached by its author"
    is RecordViewRecordUnion.Unknown -> "Quoted post unavailable"
    is RecordViewRecord -> null
}
```

The quoted card is rendered between the comment text and the existing
image thumbnail row:

```kotlin
@Composable
private fun QuotedRecordCard(record: RecordViewRecord) {
    val quoted = runCatching { record.value.decodeRecord<Post>() }.getOrNull()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text("@${record.author.handle.raw}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        quoted?.text?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        extractFirstImageThumbFromEmbeds(record.embeds)?.let { url ->
            Spacer(Modifier.height(8.dp))
            AsyncImage(model = url, contentDescription = null, /* ... */)
        }
    }
}
```

## Media embed on quote+media

`RecordWithMediaView.media` is its own open union. Reuse the same
extractor logic for `ImagesView` that `extractFirstImageThumb` already
uses:

```kotlin
internal fun extractFirstImageThumb(post: PostView): String? =
    when (val embed = post.embed) {
        is ImagesView -> embed.images.firstOrNull()?.thumb?.raw
        is RecordWithMediaView -> (embed.media as? ImagesView)?.images?.firstOrNull()?.thumb?.raw
        else -> null
    }
```

This keeps the single `thumbUrl` value in `PostRow` — no layout
shuffling required. The quoted card renders above it.

## Test fixtures

`FeedScreenTest` currently exercises the plain-post and
`ImagesView`-embed paths. Add two fixture timelines:

1. **Repost**: `FeedViewPost` with `reason = ReasonRepost(by = <profile>, indexedAt = ...)`
   and a regular post. Assert `extractFirstImageThumb` behavior unchanged
   and that a `Composable`-facing helper reports the reposter handle.
2. **Quote post**: `PostView.embed = RecordView(record = RecordViewRecord(...))`
   where the inner `value` JSON deserializes to an `app.bsky.feed.Post`.
   Assert `extractQuotedRecord` returns a non-null `RecordViewRecord`
   with the expected author handle and decoded text.
3. **Quote+media**: `PostView.embed = RecordWithMediaView(record = ..., media = ImagesView(...))`.
   Assert both `extractQuotedRecord` and `extractFirstImageThumb`
   return non-null.
4. **Unknown/Blocked/NotFound**: Each rendered via
   `RecordView(record = <placeholder arm>)`. Assert `extractQuotedRecord`
   returns null and `extractQuotedPlaceholder` returns the expected
   string.

All fixtures use canned JSON decoded through the existing MockEngine
helpers — no live network, in line with the sample smoke-test rule.

## Non-goals

- **ReasonPin UI**: rendering a "Pinned" chip. Unions are handled
  (no crash on the `ReasonPin` arm), but we deliberately don't add
  the chip in this change.
- **Nested quotes**: a quoted post that itself quotes another post.
  `RecordViewRecord.embeds` is available but ignored — only the
  first-level quote is rendered.
- **Video and external link embeds** inside
  `RecordWithMediaView.media`. Only `ImagesView` is surfaced; other
  arms fall through silently.
- **Tap-to-navigate** into a quoted post's thread. The quoted card is
  display-only for now.
- **Repost action button** (adding a repost affordance next to like).
  Out of scope; this change is purely about rendering.
