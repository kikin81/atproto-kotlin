# Open union arms reference

Every open union in the library has known data-class arms plus an
`*Unknown` fallback. Package prefix `io.github.kikin81.atproto` omitted
for brevity. Always handle the `*Unknown` arm in your `when { }`.

## Feed namespace (`app.bsky.feed`)

### `FeedViewPost.reason`: sealed interface `Reason`

| Arm | Meaning |
|---|---|
| `ReasonRepost` | User X reposted this. Access via `reason.by: ProfileViewBasic`. |
| `ReasonPin` | Pinned to a profile / feed. |
| `ReasonUnknown` | Newer reason type the library doesn't know. `null` for plain feed entries — don't forget to handle `null` too. |

### `PostView.embed`: sealed interface `PostViewEmbed`

| Arm | Fields | Meaning |
|---|---|---|
| `ImagesView` | `images: List<ViewImage>` | 1–4 image attachments. Each has `thumb`, `fullsize`, `alt`, `aspectRatio`. |
| `ExternalView` | `external: ViewExternal` | Link card. Has `uri`, `title`, `description`, `thumb`. |
| `RecordView` | `record: RecordViewRecordUnion` | Quote post — see below. |
| `RecordWithMediaView` | `record: RecordView`, `media: PostViewEmbed` (images or video) | Quote + image/video. |
| `VideoView` | `playlist: String`, `thumbnail: String?`, `aspectRatio` | HLS video. |
| `EmbedUnknown` | `type: String`, `raw: JsonObject` | Fallback. |
| `null` | — | No embed (plain text post). |

### `RecordView.record`: sealed interface `RecordViewRecordUnion`

| Arm | Meaning |
|---|---|
| `RecordViewRecord` | Real record, fully loaded. `value: JsonObject` is the embedded record — `decodeRecord<Post>()` it. Also has `author`, `uri`, `cid`, `embeds`, `indexedAt`, `likeCount`, `repostCount`, `replyCount`. |
| `RecordViewNotFound` | Target record was deleted. |
| `RecordViewBlocked` | Target author blocked the current user (or vice versa). |
| `RecordViewDetached` | Original author detached their record from yours. |
| `RecordViewUnknown` | Unknown `$type`. |

### `FeedViewPost.reply`: `ReplyRef`

Not a union — `ReplyRef` is a regular class with `parent: ReplyRefParent`
and `root: ReplyRefRoot`. Each of those **is** a union:

| Union | Arms |
|---|---|
| `ReplyRefParent` | `PostView`, `NotFoundPost`, `BlockedPost`, `ReplyRefParentUnknown` |
| `ReplyRefRoot` | Same as parent |

### `Post.embed` (record-level, when composing)

| Arm | Meaning |
|---|---|
| `Images` | Composing an image-attached post. |
| `External` | Composing a link card. |
| `Record` | Composing a quote. |
| `RecordWithMedia` | Composing a quote with media. |
| `Video` | Composing a video post. |
| `PostEmbedUnknown` | Fallback. |

## Graph namespace (`app.bsky.graph`)

### `ListView.purpose`: plain enum-like string

Not a union — `ListPurpose` is a typed value class wrapping
`app.bsky.graph.defs#modlist` / `curatelist` / `referencelist`.

## Labeler namespace (`app.bsky.labeler`)

### `LabelerView.*`: various label-value unions

If you're building a moderation-aware client, inspect these per label
service. Most consumer apps can ignore and render labels as-is.

## Notification namespace (`app.bsky.notification`)

### `Notification.reason`: typed value class, not a union

Values: `"like"`, `"repost"`, `"follow"`, `"mention"`, `"reply"`,
`"quote"`, `"starterpack-joined"`. Handle unknown strings gracefully —
new reasons ship over time.

## Decoding records

When you see `record: JsonObject` on any view type, that's a
polymorphic record. Use `decodeRecord<T>()` with the expected type:

```kotlin
import io.github.kikin81.atproto.runtime.decodeRecord
import io.github.kikin81.atproto.app.bsky.feed.Post

val post = postView.record.decodeRecord<Post>()
```

Record types by NSID:

| NSID (collection) | Record class |
|---|---|
| `app.bsky.feed.post` | `Post` |
| `app.bsky.feed.like` | `Like` |
| `app.bsky.feed.repost` | `Repost` |
| `app.bsky.feed.generator` | `Generator` |
| `app.bsky.graph.follow` | `Follow` |
| `app.bsky.graph.block` | `Block` |
| `app.bsky.graph.list` | `List` |
| `app.bsky.graph.listitem` | `Listitem` |
| `app.bsky.actor.profile` | `Profile` |

When in doubt, wrap in `runCatching { ... }.getOrNull()` so unknown
record types don't crash your rendering.
