---
name: atproto-types-reference
description: >
  Use this skill for the cross-cutting type rules that apply across
  every ATProto operation in the kikin81/atproto-kotlin library.
  Covers AtField<T> three-state optionality for mutation payloads,
  the runtime value classes (Did, Handle, AtUri, Cid, Datetime, Nsid,
  RecordKey, AtIdentifier) and when to use each, and a consolidated
  list of cross-skill pitfalls. Load this once per session alongside
  whichever task-specific skill you're using.
license: MIT (see repo LICENSE)
metadata:
  author: kikin81
  library-version: "4.6.0"
  keywords:
    - AT Protocol
    - Bluesky
    - AtField
    - value class
    - Did
    - Handle
    - AtUri
    - Cid
    - Datetime
    - types
    - pitfalls
---

## Objective

Document cross-cutting type semantics that every other ATProto skill
assumes the consumer knows: `AtField` three-state optionality, runtime
value classes, and the pitfalls that span multiple tasks.

## AtField: three-state optionality

Most optional fields in the library are plain `T?` — null means
"absent." But some AT Protocol mutations distinguish **three** states:

| State | Meaning | Kotlin form |
|---|---|---|
| **Absent** | Key not in JSON | `AtField.Missing` |
| **Null** | Key present, value `null` | `AtField.Null` |
| **Set** | Key present, value present | `AtField.Defined(value)` |

The distinction matters for `put*` mutations like `putPreferences`,
where "leave this field unchanged" (`Missing`) differs from "clear this
field" (`Null`).

### Reading

Reads always return plain `T?`. You won't see `AtField` on response
types — only on mutation request types.

### Writing

When building a mutation payload, use the factory:

```kotlin
import io.github.kikin81.atproto.runtime.AtField

val input = PutPreferencesInput(
    did = myDid,
    displayName = AtField.Defined("new name"),  // set
    avatar = AtField.Null,                      // clear
    // description left as default (Missing)    // leave unchanged
)
```

If you only see plain `T?` on a request type, ignore this section —
that endpoint doesn't need three-state semantics.

### Critical: don't break explicitNulls

The `AtField` serializer relies on `Json { explicitNulls = true }`
(the default). Never pass a `Json` instance with `explicitNulls = false`
to any library call — it would collapse `Null` into `Missing` on the
wire and silently lose data.

## Runtime value classes

The library ships zero-overhead value classes for every AT Protocol
string format. Use them everywhere — never raw strings.

| Type | Wire shape | Use for |
|---|---|---|
| `Did` | `did:plc:abc123` or `did:web:example.com` | Any DID. |
| `Handle` | `alice.bsky.social` | User handles. |
| `AtIdentifier` | Either a `Did` or a `Handle` | `repo` field on `CreateRecordRequest`, anywhere "DID-or-handle" is accepted. |
| `AtUri` | `at://did:plc:.../app.bsky.feed.post/3kxy…` | Any AT-URI to a record. |
| `Cid` | `bafyrei…` | Content hashes on records. |
| `Datetime` | ISO 8601 string | `createdAt`, `indexedAt`, etc. |
| `Nsid` | `app.bsky.feed.post` | Collection identifiers, union `$type` values. |
| `RecordKey` | `3kxy…` | The rkey (last path segment of an `AtUri`). |
| `Language` | `en`, `en-US` | Language tags on posts. |

### Construction and unwrap

```kotlin
val handle = Handle("alice.bsky.social")
val did = Did("did:plc:abc123")
val uri = AtUri("at://did:plc:.../app.bsky.feed.post/3kxy")

// Unwrap to raw String:
handle.raw  // "alice.bsky.social"
did.raw     // "did:plc:abc123"
uri.raw     // "at://..."
```

### Validation

Validation is deferred — invalid values construct fine but error at
serialization time. Don't rely on the constructor to validate format.

### Extracting rkey from AtUri

```kotlin
val rkey = RecordKey(atUri.raw.substringAfterLast('/'))
```

## Cross-skill pitfalls

These apply regardless of which task you're doing.

### Never edit generated sources

Files in `at-protocol-models-<version>.jar` are the output of a
KotlinPoet-based generator that runs against the upstream AT Protocol
lexicon corpus. If a generated type is wrong, open an issue at
https://github.com/kikin81/atproto-kotlin/issues — don't patch locally.

### Use services, not raw XrpcClient

```kotlin
// ❌ Don't
client.query("app.bsky.feed.getTimeline", params, ...)

// ✅ Do
FeedService(client).getTimeline(GetTimelineRequest(...))
```

Generated services wire NSID, request/response serializers, and the
auth provider correctly. `XrpcClient.query()` / `procedure()` are
public only for escape-hatch use against non-lexicon endpoints.

### Always name arguments on generated classes

```kotlin
// ❌ Don't
val post = Post("hello", datetimeNow(), null, null, null, null)

// ✅ Do
val post = Post(
    text = "hello",
    createdAt = datetimeNow(),
)
```

Generated data classes can gain new fields between releases. Positional
construction is a silent break on upgrade.

### Always handle *Unknown arms

Every open union has an `*Unknown` fallback for `$type` values the
library doesn't model. `when { }` branches without it compile but
throw at runtime when upstream ships a new type.

### Don't reuse XrpcClient across logout/login

Each `oauth.createClient()` binds a fresh `DpopAuthProvider` to the
current session. After `logout()`, the old client's DPoP key is
cleared — requests will fail. Call `createClient()` again after
`completeLogin()` finishes.

### Never set explicitNulls = false

On any `Json` instance you hand to the library. Breaks `AtField`.

### $type is required on records

Always go through `encodeRecord(serializer, value, type)` when building
the `record: JsonObject` for `createRecord`. A raw
`Json.encodeToJsonElement(post)` misses `$type` and the server rejects.

### Library doesn't include a Ktor engine

Declare `io.ktor:ktor-client-cio` (or another engine) in your
`build.gradle.kts`. The runtime only pulls in `ktor-client-core`.

## Related skills

- `atproto-setup` — Gradle + Ktor engine
- `atproto-oauth` — AtField doesn't apply; OAuth uses plain `T?`
- `atproto-read` — value classes show up on every response
- `atproto-write-records` — `encodeRecord` pattern, rkey extraction
