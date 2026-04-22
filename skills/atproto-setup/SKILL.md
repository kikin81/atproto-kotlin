---
name: atproto-setup
description: >
  Use this skill when setting up a new Kotlin, Android, or KMP project
  to consume the kikin81/atproto-kotlin library. Covers Maven Central
  coordinates, the three published artifacts (runtime, models, oauth),
  Ktor engine selection, and minimum JDK/Android SDK requirements. Use
  this skill before any of the atproto-oauth, atproto-read, or
  atproto-write-records skills.
license: MIT (see repo LICENSE)
metadata:
  author: kikin81
  library-version: "5.0.0"
  keywords:
    - AT Protocol
    - Bluesky
    - Kotlin
    - Gradle
    - setup
    - Maven Central
    - Ktor
---

## Objective

Wire `io.github.kikin81.atproto:*` dependencies into a consumer project
so the other ATProto skills have a working build to target.

## Prerequisites

- A Kotlin (JVM or Android) or Kotlin Multiplatform project
- Gradle 8.x or 9.x
- JDK 17+
- Android SDK 36+ if building for Android

## The three artifacts

| Artifact | Targets | You need it when… |
|---|---|---|
| `runtime` | KMP (JVM + iOS) | Always. Value classes, `XrpcClient`, `AtField`, open-union base types. |
| `models` | KMP (JVM + iOS) | Always. Generated record types, request/response pairs, `*Service` classes. |
| `oauth` | JVM-only | If you need user login against Bluesky. |

`models` is **generated code** — never edit files under its
`build/generated/` directory. All three publish to Maven Central under
the `io.github.kikin81.atproto` group.

**iOS note:** JVM + KMP metadata publish from Linux CI, but iOS klibs
aren't on Maven Central yet. Android + JVM consumers work today.

## Gradle wiring

### `settings.gradle.kts`

```kotlin
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
```

### `app/build.gradle.kts`

```kotlin
dependencies {
    implementation("io.github.kikin81.atproto:runtime:5.0.0")
    implementation("io.github.kikin81.atproto:models:5.0.0")
    implementation("io.github.kikin81.atproto:oauth:5.0.0")

    // Pick one Ktor engine — CIO works on Android + JVM
    implementation("io.ktor:ktor-client-cio:3.0.0")
}
```

**You must pick a Ktor engine.** The runtime transitively depends on
Ktor client core, kotlinx-serialization-json, and kotlinx-coroutines-core,
but **not** an engine — that's your choice. Options:

- `io.ktor:ktor-client-cio` — pure Kotlin, works everywhere. Default pick.
- `io.ktor:ktor-client-okhttp` — OkHttp-backed, Android-friendly.
- `io.ktor:ktor-client-darwin` — iOS-native.

### Version catalog (optional, recommended)

```toml
# gradle/libs.versions.toml
[versions]
atproto = "5.0.0"
ktor = "3.0.0"

[libraries]
atproto-runtime = { module = "io.github.kikin81.atproto:runtime", version.ref = "atproto" }
atproto-models = { module = "io.github.kikin81.atproto:models", version.ref = "atproto" }
atproto-oauth = { module = "io.github.kikin81.atproto:oauth", version.ref = "atproto" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
```

Then reference via `implementation(libs.atproto.runtime)`, etc.

## Package namespaces

All generated code lives under `io.github.kikin81.atproto.<ns>`, where
`<ns>` mirrors the lexicon NSID with `.defs` stripped. Quick reference:

- `io.github.kikin81.atproto.app.bsky.feed` — `FeedService`, `Post`,
  `Like`, `FeedViewPost`, `PostView`, etc.
- `io.github.kikin81.atproto.app.bsky.actor` — `ActorService`, `Profile`.
- `io.github.kikin81.atproto.app.bsky.graph` — `GraphService`, `Follow`,
  `Block`, `ListItem`.
- `io.github.kikin81.atproto.com.atproto.repo` — `RepoService`,
  `CreateRecordRequest`, `DeleteRecordRequest`, `StrongRef`.
- `io.github.kikin81.atproto.oauth` — `AtOAuth`, `OAuthSessionStore`.
- `io.github.kikin81.atproto.runtime` — value classes (`Did`, `Handle`,
  `AtUri`, `Cid`, `Datetime`, `Nsid`, `RecordKey`, `AtIdentifier`),
  `AtField`, `XrpcClient`, `encodeRecord`.

## Common pitfalls

- **Forgetting to declare a Ktor engine.** The build succeeds but fails
  at runtime with `IllegalStateException: Failed to find HttpClientEngine`.
- **Mixing library versions.** All three ATProto artifacts must be on
  the same version. Use a version catalog to avoid drift.
- **Adding Bluesky's at-proto TypeScript repo as a submodule.** Not
  needed — this library regenerates from the upstream lexicon corpus
  on every release.
- **Editing generated sources.** Files under
  `models-<version>.jar` are the published output of the
  generator. If a generated type is wrong, open an issue upstream.

## Related skills

- `atproto-oauth` — authenticate a user and get an `XrpcClient`
- `atproto-types-reference` — cross-cutting type rules (value classes,
  `AtField`) that apply everywhere
