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
    implementation("io.github.kikin81.atproto:at-protocol-runtime:<version>")
    implementation("io.github.kikin81.atproto:at-protocol-models:<version>")
    implementation("io.github.kikin81.atproto:at-protocol-oauth:<version>") // optional
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

- **at-protocol-runtime** — Hand-written base: value classes, `AtField`,
  `XrpcClient`, `AuthProvider`, pagination helpers.
- **at-protocol-models** — Generated records, requests/responses, open
  unions, and `*Service` classes.
- **at-protocol-oauth** — OAuth 2.0 with PAR + PKCE + DPoP for public
  clients (Android / JVM).

For LLM agents consuming this library, fetch task-oriented skills from
[`skills/`](https://github.com/kikin81/atproto-kotlin/tree/main/skills)
instead of browsing this API reference.
