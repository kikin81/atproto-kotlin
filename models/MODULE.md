# Module models

**Code-generated** AT Protocol models and service bindings. Kotlin
Multiplatform (JVM + iOS). This is what downstream consumers depend on
to call Bluesky and atproto endpoints.

## What's in here

Everything under this module is emitted by `:generator`
at build time from the upstream `@atproto/lex` lexicon corpus. You
won't find a `src/commonMain/` — the sources live under
`build/generated/source/lexicon/` and flow through to the published jar.

Per lexicon NSID, the generator emits:

- **Record types** — data classes like `Post`, `Like`, `Follow`,
  `Profile`, `Generator` for every `record` lexicon definition.
- **Request / response pairs** — `GetTimelineRequest` + `GetTimelineResponse`,
  etc., for every `query` and `procedure`.
- **`<Namespace>Service`** — classes like `FeedService`, `ActorService`,
  `GraphService`, `RepoService` that wrap an `XrpcClient` and expose
  every NSID as a `suspend fun`.
- **Open unions** — sealed interfaces like `PostViewEmbed`, `Reason`,
  `RecordViewRecordUnion` with a data-class arm per known `$type` plus
  an `Unknown` fallback for unknown variants.
- **`*Flow()` / `*PageFlow()` extensions** — cursor pagination
  convenience functions on service classes, auto-generated for every
  paginated query.
- **Typed string formats** — the generator uses the value classes from
  `runtime` (`Did`, `Handle`, `AtUri`, …) for fields
  declared with those formats in the lexicon.

## How regeneration works

The `:generator:generateModels` Gradle task regenerates
this module's sources from `generator/lexicons/`. It runs
automatically before every `compileKotlin*` task on `:models`
and is Gradle-incremental — idle when lexicons haven't changed.

## Do not edit generated sources

Files under `models/build/generated/` and the published
`models-<version>.jar` are the output of KotlinPoet
emitters in the generator. Incorrect output is a generator bug —
open an issue at
[kikin81/atproto-kotlin](https://github.com/kikin81/atproto-kotlin/issues).

## Versioning

Bumped automatically on every `feat:` or `fix:` commit to `main` via
semantic-release. New `@atproto/lex` releases land via an
auto-generated PR (see
[`lexicon-drift-automation`](https://github.com/kikin81/atproto-kotlin/blob/main/openspec/specs/lexicon-toolchain/spec.md))
and cut a release when merged.
