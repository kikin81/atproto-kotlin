# atproto-kotlin usage guide (LLM-oriented)

> **For LLM agents consuming this library from another project.** This is a
> curated cheat sheet. Prefer this document over browsing the Dokka API
> reference: it's task-oriented, copy-pasteable, and fits in one context load.
>
> - **Documented version:** `4.6.0`
> - **Raw URL (fetch this once per session):**
>   `https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/LLM_USAGE.md`
> - **Source of truth:** if this guide disagrees with the code, the code wins.
>   Open an issue at https://github.com/kikin81/atproto-kotlin/issues.

## What you get

Three artifacts on Maven Central (`io.github.kikin81.atproto:*`):

| Artifact | Targets | What's in it |
|---|---|---|
| `at-protocol-runtime` | KMP (JVM + iOS) | Value classes (`Did`, `Handle`, `AtUri`, `Cid`, `Datetime`, `Nsid`, `RecordKey`, `AtIdentifier`), `AtField<T>` three-state optionality, `OpenUnion` base types, `XrpcClient`, `AuthProvider`, `Pagination.paginate()`, record encode/decode helpers. |
| `at-protocol-models` | KMP (JVM + iOS) | Generated from the AT Protocol lexicon corpus: record types (`Post`, `Like`, `Follow`, `Profile`, …), request/response pairs for every XRPC endpoint, `<Namespace>Service` classes, open-union types (`Embed`, `Reason`, `RecordView`, …). |
| `at-protocol-oauth` | JVM-only | `AtOAuth` flow orchestrator, `DpopAuthProvider`, `OAuthSession(Store)`. Bluesky-compliant OAuth 2.0 with PAR + PKCE + DPoP. |

**iOS note:** runtime + models publish KMP metadata from Linux CI, but iOS
klibs are not yet on Maven Central. JVM + Android work today.

## Gradle setup

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { mavenCentral() }
}

// app/build.gradle.kts
dependencies {
    implementation("io.github.kikin81.atproto:at-protocol-runtime:4.6.0")
    implementation("io.github.kikin81.atproto:at-protocol-models:4.6.0")
    implementation("io.github.kikin81.atproto:at-protocol-oauth:4.6.0")

    // Ktor engine — pick one. CIO is fine for Android + JVM.
    implementation("io.ktor:ktor-client-cio:3.x")
}
```

The runtime transitively pulls in Ktor client, kotlinx-serialization-json,
and kotlinx-coroutines-core. You only need to add an engine.

## OAuth: login flow end-to-end

Bluesky requires **OAuth 2.0 with DPoP** (no app passwords for new apps).
The full flow is: handle → discovery → PAR → browser authorization →
code exchange → DPoP-authenticated client.

### 1. Host a client-metadata JSON

Required public HTTPS URL. Contents:

```json
{
  "client_id": "https://your-app.example.com/oauth/client-metadata.json",
  "application_type": "native",
  "client_name": "Your App Name",
  "client_uri": "https://your-app.example.com",
  "tos_uri": "https://your-app.example.com/tos",
  "policy_uri": "https://your-app.example.com/privacy",
  "dpop_bound_access_tokens": true,
  "grant_types": ["authorization_code", "refresh_token"],
  "redirect_uris": ["com.example.yourapp:/oauth-redirect"],
  "response_types": ["code"],
  "scope": "atproto transition:generic",
  "token_endpoint_auth_method": "none"
}
```

**Gotchas:**
- `client_id` **must** be the exact URL where this file is hosted.
- Redirect URIs use a **single slash** (`com.example.app:/path`), not `://`.
- Host via GitHub Pages, Cloudflare Pages, any static HTTPS.

### 2. Configure the Android redirect intent filter

```xml
<activity android:name=".MainActivity" android:launchMode="singleTask">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="com.example.yourapp" />
    </intent-filter>
</activity>
```

`singleTask` is required — redirect re-delivers to existing Activity.

### 3. Implement an `OAuthSessionStore`

```kotlin
class EncryptedSessionStore(context: Context) : OAuthSessionStore {
    private val prefs = EncryptedSharedPreferences.create(/* … */)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): OAuthSession? =
        prefs.getString("session", null)?.let { json.decodeFromString(it) }

    override suspend fun save(session: OAuthSession) {
        prefs.edit().putString("session", json.encodeToString(session)).apply()
    }

    override suspend fun clear() {
        prefs.edit().remove("session").apply()
    }
}
```

**Always use `EncryptedSharedPreferences` on Android.** The session contains
the DPoP private key.

### 4. Create `AtOAuth` (DI)

```kotlin
val oauth = AtOAuth(
    clientMetadataUrl = "https://your-app.example.com/oauth/client-metadata.json",
    sessionStore = sessionStore,
    httpClient = HttpClient(CIO),
)
```

### 5. Drive the flow

```kotlin
// Step 1: start login
val authUrl = oauth.beginLogin("alice.bsky.social")

// Step 2: open in Chrome Custom Tabs
CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(authUrl))

// Step 3: capture the redirect in onNewIntent, pass to completeLogin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val uri = intent.data ?: return
    if (uri.scheme == "com.example.yourapp") {
        viewModelScope.launch { oauth.completeLogin(uri.toString()) }
    }
}

// Step 4: get an authenticated XrpcClient whenever you need one
val client: XrpcClient = oauth.createClient()
```

`createClient()` returns a client with `DpopAuthProvider` — token refresh
and DPoP nonce rotation happen transparently.

### Session persistence on app restart

```kotlin
val existing = sessionStore.load()
if (existing != null) {
    // already logged in
    val client = oauth.createClient()
}
```

### Logout

```kotlin
oauth.logout() // clears the session via SessionStore.clear()
```

## Making XRPC calls

Every AT Protocol namespace has a generated `<Namespace>Service` class
that wraps an `XrpcClient`. Package layout:

- `io.github.kikin81.atproto.app.bsky.feed.FeedService`
- `io.github.kikin81.atproto.app.bsky.actor.ActorService`
- `io.github.kikin81.atproto.app.bsky.graph.GraphService`
- `io.github.kikin81.atproto.app.bsky.notification.NotificationService`
- `io.github.kikin81.atproto.com.atproto.repo.RepoService`
- …etc. One service per NSID prefix.

### Read (query)

```kotlin
val client = oauth.createClient()
val response = FeedService(client).getTimeline(
    GetTimelineRequest(limit = 25L),
)
response.feed.forEach { entry: FeedViewPost ->
    println(entry.post.record)
}
```

All request types are data classes with defaults — instantiate inline.

### Write (procedure)

Writes go through `RepoService` because AT Protocol records are all
`com.atproto.repo.createRecord` calls under the hood:

```kotlin
val post = Post(
    text = "Hello, atproto!",
    createdAt = datetimeNow(),
)

RepoService(client).createRecord(
    CreateRecordRequest(
        repo = AtIdentifier(did),                   // your DID
        collection = Nsid("app.bsky.feed.post"),
        record = encodeRecord(Post.serializer(), post, "app.bsky.feed.post"),
    ),
)
```

**Key pattern:** `encodeRecord(serializer, value, type)` wraps the value
in a `JsonObject` with `"$type"` injected. That's what the repo expects.

### Delete a record

```kotlin
RepoService(client).deleteRecord(
    DeleteRecordRequest(
        repo = AtIdentifier(did),
        collection = Nsid("app.bsky.feed.post"),
        rkey = RecordKey(postAtUri.raw.substringAfterLast('/')),
    ),
)
```

The rkey is the last path segment of the record's `at://` URI.

### Like / unlike

```kotlin
val like = Like(
    subject = StrongRef(uri = post.uri, cid = post.cid),
    createdAt = datetimeNow(),
)
RepoService(client).createRecord(
    CreateRecordRequest(
        repo = AtIdentifier(did),
        collection = Nsid("app.bsky.feed.like"),
        record = encodeRecord(Like.serializer(), like, "app.bsky.feed.like"),
    ),
)
```

Unlike: `deleteRecord` with `collection = Nsid("app.bsky.feed.like")` and
the rkey of the like record (stored on `PostView.viewer.like`).

## Pagination

Every cursor-paginated query has two auto-generated Flow extensions:

- `*Flow()` — `Flow<Item>`, one emission per item
- `*PageFlow()` — `Flow<List<Item>>`, one emission per page (use this for UI)

```kotlin
import io.github.kikin81.atproto.app.bsky.feed.timelinePageFlow

FeedService(client)
    .timelinePageFlow(GetTimelineRequest(limit = 25L))
    .catch { t -> /* surface error */ }
    .collect { page ->
        // append page to your state, one state update per page
    }
```

**For infinite-scroll UI:** use `*PageFlow()` and suspend in `collect { }`
until the user scrolls near the bottom — the Flow is lazy, so the next
page isn't fetched until `collect` returns:

```kotlin
val loadMore = Channel<Unit>(Channel.CONFLATED)

FeedService(client).timelinePageFlow(request).collect { page ->
    state.value = state.value + page
    loadMore.receive() // suspend until UI says "load more"
}

// In UI: loadMore.trySend(Unit) when scrolled to end
```

Generated naming rule: strip leading `get`, camelCase the rest, append
`Flow` / `PageFlow`. `getTimeline` → `timelineFlow` / `timelinePageFlow`.
`searchPosts` → `searchPostsFlow` / `searchPostsPageFlow`.
`listNotifications` → `listNotificationsFlow` / `listNotificationsPageFlow`.

## Open unions (embeds, reasons, record views)

AT Protocol unions carry a `$type` discriminator. Library decodes into
**sealed interfaces** with an `Unknown` fallback. Always pattern-match
with `is` / `as?` — never assume a specific arm.

### Feed entry reason (repost vs. pin vs. none)

```kotlin
feedViewPost.reason?.let { reason ->
    when (reason) {
        is ReasonRepost -> "Reposted by @${reason.by.handle.raw}"
        is ReasonPin -> "Pinned"
        is ReasonUnknown -> null  // newer reason type we don't know yet
    }
}
```

### Post embeds

```kotlin
when (val embed = postView.embed) {
    is ImagesView -> embed.images.firstOrNull()?.thumb
    is ExternalView -> embed.external.uri
    is RecordView -> /* quote post */ extractQuoted(embed.record)
    is RecordWithMediaView -> /* quote + media */
    is VideoView -> embed.playlist
    is EmbedUnknown -> null
    null -> null
}
```

### Quoted records (RecordView.record is also a union)

```kotlin
when (val inner = recordView.record) {
    is RecordViewRecord -> {
        // it's a real post — decode it
        val quotedPost = inner.value.decodeRecord<Post>()
    }
    is RecordViewNotFound -> "(deleted)"
    is RecordViewBlocked -> "(blocked)"
    is RecordViewDetached -> "(detached by author)"
    is RecordViewUnknown -> null
}
```

### Decoding a typed record from `unknown`

Records on the wire come in as `JsonObject`. Use the extension:

```kotlin
import io.github.kikin81.atproto.runtime.decodeRecord

val post: Post = postView.record.decodeRecord<Post>()
// or with explicit serializer:
val post: Post = postView.record.decodeRecord(Post.serializer())
```

Tolerate decode failures — the record might be a type your app doesn't
model:

```kotlin
val post = runCatching { postView.record.decodeRecord<Post>() }.getOrNull()
```

## `AtField<T>`: three-state optionality (mutations only)

Some AT Protocol mutations distinguish **absent**, **null**, and **set**:

- `AtField.Missing` — key absent from JSON, "don't touch this field"
- `AtField.Null` — key present with explicit `null`, "clear this field"
- `AtField.Defined(value)` — key present with value

This only matters for `put*` mutations (e.g. `putPreferences`). Reads
return plain `T?`. Don't construct `AtField` manually unless you're
building a mutation payload.

```kotlin
val input = PutPreferencesInput(
    did = myDid,
    displayName = AtField.Defined("new name"),   // set it
    // avatar = AtField.Null                     // clear it
    // description left as Missing by default    // leave unchanged
)
```

**Do NOT** set `explicitNulls = false` on any `Json` instance you hand
to the library — it collapses `Null` into `Missing` on the wire.

## Value classes: don't stringly-type

The runtime ships Kotlin value classes for every AT Protocol string
format. Use them — they're zero-overhead and prevent mixing:

| Type | Example wire value |
|---|---|
| `Did` | `did:plc:abc123…` |
| `Handle` | `alice.bsky.social` |
| `AtIdentifier` | Either a DID or a handle (for `repo` fields) |
| `AtUri` | `at://did:plc:…/app.bsky.feed.post/3kxyz` |
| `Cid` | `bafyrei…` |
| `Datetime` | ISO 8601 string |
| `Nsid` | `app.bsky.feed.post` |
| `RecordKey` | `3kxyz…` |

Get the raw string with `.raw`:

```kotlin
val handle = Handle("alice.bsky.social")
println(handle.raw)          // "alice.bsky.social"
println(post.uri.raw)        // at://...
```

Construct from a string with the constructor: `Did("did:plc:…")`.
Validation is deferred — invalid values error at serialization time.

## Feed timestamp formatting

Records carry `createdAt: Datetime` (ISO 8601). To show "2h ago"-style
relative times, parse the raw string — no helper is shipped:

```kotlin
// Android (API 26+)
val instant = Instant.parse(post.record.createdAt.raw)
val relative = DateUtils.getRelativeTimeSpanString(
    instant.toEpochMilli(),
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
)
```

## Common pitfalls

1. **Don't instantiate generated model classes with positional args you don't
   understand.** They're data classes — use named args. `Post(text = "…", createdAt = datetimeNow())`.
2. **Don't edit files under `at-protocol-models/build/generated/`.** They
   regenerate. Model bugs → open an issue.
3. **Don't call `XrpcClient` directly for AT Protocol endpoints.** Use the
   generated `*Service` classes — they handle NSID, serializer wiring, and
   auth provider selection.
4. **Don't reuse an `XrpcClient` across logout/login.** Call
   `oauth.createClient()` after each successful `completeLogin()`.
5. **Don't mix `*Flow()` and `*PageFlow()`** — `*Flow()` emits per item,
   which triggers one StateFlow update per item (recomposition storm in
   Compose). Use `*PageFlow()` for UI.
6. **Don't forget `$type` on records.** Always go through `encodeRecord()`
   when building a `record: JsonObject` for `createRecord`.
7. **Don't hardcode `bsky.social` as the PDS.** OAuth discovery handles
   PDS resolution per-account. Use `oauth.createClient()`, not a
   hand-built client.
8. **Don't assume Jetpack Paging 3 is wired up.** It isn't. The library
   provides Flow pagination; wrap it in a `PagingSource` yourself if needed.
9. **Don't set `explicitNulls = false` on any `Json` you pass in.** Breaks
   `AtField` three-state semantics.
10. **Unknown is a real arm.** Every open union has an `*Unknown` member.
    Always handle it in your `when { }` — never `!!` your way past it.

## Reference files (fetch on demand)

For working examples, read these from the sample app. Each file is
self-contained and under ~200 lines:

- **OAuth + redirect**: https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/samples/android/src/main/kotlin/io/github/kikin81/atproto/samples/bluesky/MainActivity.kt
- **Login ViewModel**: https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/samples/android/src/main/kotlin/io/github/kikin81/atproto/samples/bluesky/MainViewModel.kt
- **Hilt DI**: https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/samples/android/src/main/kotlin/io/github/kikin81/atproto/samples/bluesky/di/AppModule.kt
- **Encrypted session store**: https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/samples/android/src/main/kotlin/io/github/kikin81/atproto/samples/bluesky/session/AndroidOAuthSessionStore.kt
- **Feed + pagination + like + delete**: https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/samples/android/src/main/kotlin/io/github/kikin81/atproto/samples/bluesky/ui/FeedViewModel.kt
- **Compose post**: https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/samples/android/src/main/kotlin/io/github/kikin81/atproto/samples/bluesky/ui/ComposeViewModel.kt
- **OAuth module README**: https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/at-protocol-oauth/README.md
- **Dokka API reference (humans)**: https://kikin81.github.io/atproto-kotlin/api/

## Quick setup prompt for a consuming repo's CLAUDE.md

Paste this into the new client app's `CLAUDE.md`:

```markdown
## atproto-kotlin

This app consumes `io.github.kikin81.atproto:{at-protocol-runtime,at-protocol-models,at-protocol-oauth}:4.6.0`.

Before writing any ATProto-related code, fetch the usage guide once per session:
https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/LLM_USAGE.md

Key rules:
- Use generated `*Service` classes, never raw `XrpcClient` for ATProto endpoints
- Use `encodeRecord(serializer, value, type)` when building `createRecord` payloads
- Use `*PageFlow()` (not `*Flow()`) for UI pagination
- Always handle the `*Unknown` arm on open unions
- Use value classes (`Did`, `Handle`, `AtUri`, …) — never raw strings
```
