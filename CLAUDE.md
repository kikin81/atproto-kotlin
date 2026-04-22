# CLAUDE.md

## Project overview

Code-generated AT Protocol Kotlin Multiplatform SDK for Bluesky. Parses the
upstream Lexicon JSON corpus and emits idiomatic Kotlin: data classes, open
unions, typed value classes, XRPC service interfaces, and KDoc documentation.

**Repo:** https://github.com/kikin81/atproto-kotlin
**Maven Central:** `io.github.kikin81.atproto:{runtime,models,oauth}`
**API Docs:** https://kikin81.github.io/atproto-kotlin/api/

## Modules

| Module | Type | Published | Purpose |
|--------|------|-----------|---------|
| `:runtime` | KMP (JVM + iOS) | Yes | Value classes, AtField, OpenUnion, XrpcClient, AuthProvider |
| `:generator` | JVM-only | No | Lexicon parser, IR, ref resolver, KotlinPoet emitters |
| `:models` | KMP (JVM + iOS) | Yes | Generated models, services, unions from Lexicon corpus |
| `:oauth` | JVM-only | Yes | AT Protocol OAuth 2.0 (PAR + PKCE + DPoP) for public clients |
| `:samples:android` | Android | No | Reference Compose app with OAuth login + timeline feed |

## Build prerequisites

- **JDK 17** (tracked by `.java-version`)
- **Node 22+** (for `npx lex install` — installs the Lexicon corpus)
- **Android SDK** (API 36+, only needed for `:samples:android`)
- **Gradle 9.3.1** (wrapper included)

## Key commands

```bash
# Install lexicon corpus (required before first build)
cd generator && npx lex install --ci && cd -

# Full build (all modules + tests + spotless)
./gradlew build

# Module-specific tests
./gradlew :runtime:jvmTest
./gradlew :generator:test
./gradlew :oauth:test
./gradlew :samples:android:testDebugUnitTest

# Code formatting
./gradlew spotlessApply          # auto-fix
./gradlew spotlessCheck          # verify only

# Generate API docs (Dokka)
./gradlew :dokkaGeneratePublicationHtml   # combined HTML site -> build/dokka/html/

# Install sample app
./gradlew :samples:android:installDebug

# Regenerate golden files after generator changes
GOLDEN_UPDATE=1 ./gradlew :generator:test --tests '*GoldenFileTest*'
```

## Code style

- **Spotless + ktlint 1.4.1** enforced via pre-commit hooks and CI
- **pre-commit hooks**: install with `pre-commit install && pre-commit install --hook-type commit-msg`
- **Conventional Commits**: enforced by commitlint (`feat:`, `fix:`, `chore:`, etc.)
- No wildcard imports, no max line length, Compose `@Composable` function naming disabled

## Architecture: code generation pipeline

1. **Parse**: `LexiconParser` deserializes Lexicon JSON into IR (`Definition`, `FieldType`)
2. **Resolve**: `RefResolver` builds a `SymbolTable` mapping `DefKey -> Definition`
3. **Context tag**: `ContextTagger` marks each def as Mutation/Read/Both
4. **Name**: `NamingMatrix` assigns Kotlin `FqName`s per the naming rules
5. **Verify**: `VerificationPass` enforces INV-1..4 (no name collisions)
6. **Plan**: `EmissionPlan` computes union sites, membership, contextual splits
7. **Emit**: `ModelGenerator`, `XrpcGenerator`, `UnionGenerator`, `ServiceGenerator` produce KotlinPoet `FileSpec`s with KDoc and `@Deprecated` annotations

Generated source lands in `models/build/generated/source/lexicon/commonMain/`.

## Testing

- **JUnit 5** via `kotlin.test` across all modules
- **MockEngine** (Ktor) for HTTP-level tests in OAuth and sample modules
- **GoldenFileTest**: byte-for-byte regression test for generator output. Update with `GOLDEN_UPDATE=1`
- Golden lexicons: `generator/src/test/resources/golden/lexicons/`
- Golden output: `generator/src/test/resources/golden/kotlin/`

## Publishing

- **Semantic-release** via `open-turo/actions-jvm/release` on every push to `main`
- Publishes to **Maven Central** (auto-promoted) and **GitHub Packages**
- Version tracked in `gradle.properties`, bumped automatically by CI
- GPG signing via in-memory key (CI secrets)
- Dokka-generated javadoc JARs replace empty stubs

## CI/CD

- `.github/workflows/ci.yaml`: lint + test + build on every push/PR
- `.github/workflows/release.yaml`: gate jobs -> semantic-release -> publish -> API docs to GitHub Pages
- GitHub Pages serves `docs/` (includes `oauth/client-metadata.json` and `api/` Dokka docs)

## OpenSpec workflow

Changes are proposed and tracked under `openspec/changes/<name>/` using the
spec-driven schema: `proposal.md` -> `design.md` -> `specs/` -> `tasks.md`.
Completed changes are archived to `openspec/changes/archive/` and delta specs
are synced to `openspec/specs/`.

```bash
openspec list                           # see active + archived changes
openspec status --change <name>         # artifact-level progress
```

## Key conventions

- `AuthProvider` interface supports both Bearer tokens and DPoP proof-of-possession
- `JsonObject.decodeRecord<T>()` extension for typed access to `unknown` record fields
- `AtField<T>` three-state optionality: `Missing` / `Null` / `Defined(value)` for mutation paths
- Open unions use `interface` (not `sealed`) with `Unknown` fallback for forward compat
- Package names drop `.defs` suffix: `app.bsky.feed.defs` -> `io.github.kikin81.atproto.app.bsky.feed`
