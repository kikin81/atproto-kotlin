# Module atproto-kotlin

Code-generated [AT Protocol](https://atproto.com) Kotlin Multiplatform SDK
for Bluesky and atproto-powered apps. Parses the upstream `@atproto/lex`
lexicon corpus at build time and emits idiomatic Kotlin: immutable `data
class` records, sealed-equivalent open unions with `$type` dispatch,
typed value classes for every string format, and `suspend fun` XRPC
service interfaces ready to drop into a Ktor client.

## Quick start

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.kikin81.atproto:runtime:<version>")
    implementation("io.github.kikin81.atproto:models:<version>")
    implementation("io.github.kikin81.atproto:oauth:<version>") // optional
    implementation("io.ktor:ktor-client-cio:3.x") // or your preferred engine
}
```

```kotlin
val client = XrpcClient(
    baseUrl = "https://bsky.social",
    httpClient = HttpClient(CIO),
    authProvider = myAuthProvider,
)
val timeline = FeedService(client).getTimeline(GetTimelineRequest(limit = 25L))
```

See each module's documentation for details:

- **runtime** — Hand-written base: value classes, `AtField`,
  `XrpcClient`, `AuthProvider`, pagination helpers.
- **models** — Generated records, requests/responses, open
  unions, and `*Service` classes.
- **oauth** — OAuth 2.0 with PAR + PKCE + DPoP for public
  clients (Android / JVM).
- **compose** — Jetpack Compose helpers for rendering Bluesky
  post text + facet annotations as an `AnnotatedString`. No
  Material dependency.
- **compose-material3** — `rememberBlueskyAnnotatedString`
  composable with Material 3 defaults. Optional add-on layered
  on top of `compose`.

For LLM agents consuming this library, fetch task-oriented skills from
[`skills/`](https://github.com/kikin81/atproto-kotlin/tree/main/skills)
instead of browsing this API reference.
