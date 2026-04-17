# Common records reference

Canonical record types you'll `createRecord` / `deleteRecord`. Package
prefix `io.github.kikin81.atproto` omitted.

## Post (`app.bsky.feed.post`)

Required fields:

| Field | Type | Notes |
|---|---|---|
| `text` | `String` | Up to 300 graphemes. |
| `createdAt` | `Datetime` | ISO 8601. `Datetime(Instant.now().toString())`. |

Common optional fields:

| Field | Type | Use when |
|---|---|---|
| `langs` | `List<Language>?` | Declaring post language for filtering. |
| `facets` | `List<Facet>?` | Rich-text annotations (mentions, links, tags). |
| `reply` | `ReplyRef?` | Making this a reply. Needs `root` + `parent` `StrongRef`s. |
| `embed` | `Post.Embed?` (open union) | Attaching images, video, quote, or external link. |
| `labels` | `SelfLabels?` | Self-applied content labels. |

## Like (`app.bsky.feed.like`)

```kotlin
Like(
    subject = StrongRef(uri = post.uri, cid = post.cid),
    createdAt = Datetime(Instant.now().toString()),
)
```

Delete by rkey of the like record (stored on `PostView.viewer.like`).

## Repost (`app.bsky.feed.repost`)

Same shape as Like:

```kotlin
Repost(
    subject = StrongRef(uri = post.uri, cid = post.cid),
    createdAt = Datetime(Instant.now().toString()),
)
```

Stored at `PostView.viewer.repost` when present.

## Follow (`app.bsky.graph.follow`)

```kotlin
Follow(
    subject = Did("did:plc:target"),
    createdAt = Datetime(Instant.now().toString()),
)
```

Note: `subject` is a bare `Did`, not a `StrongRef` (follows target an
actor, not a specific record).

## Block (`app.bsky.graph.block`)

```kotlin
Block(
    subject = Did("did:plc:target"),
    createdAt = Datetime(Instant.now().toString()),
)
```

Same shape as Follow.

## List (`app.bsky.graph.list`)

Creates a curated or moderation list. Less common in consumer apps:

```kotlin
List(
    name = "My curated list",
    purpose = ListPurpose("app.bsky.graph.defs#curatelist"),  // or modlist
    description = "What it is",
    createdAt = Datetime(Instant.now().toString()),
)
```

## Listitem (`app.bsky.graph.listitem`)

Adds an actor to a list:

```kotlin
Listitem(
    subject = Did("did:plc:target"),
    list = listAtUri,  // the list's AtUri
    createdAt = Datetime(Instant.now().toString()),
)
```

## Profile (`app.bsky.actor.profile`)

**Unusual** — profile is a singleton record with `rkey = "self"`, and
updates are typically done via `putRecord` (this library uses
`createRecord` with `rkey = "self"` which overwrites). Most consumer
apps don't need this.

## Helpful snippet: Datetime

The library ships `Datetime` as a value class over a `String`. The
sample app's `DatetimeUtil.kt` defines:

```kotlin
expect fun datetimeNow(): Datetime
// actual for JVM:
actual fun datetimeNow(): Datetime = Datetime(Instant.now().toString())
```

Use this rather than scattering `Instant.now().toString()` calls.
