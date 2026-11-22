# Agent Instructions & Project Guide

This repository contains **atproto-kotlin**, a code-generated Kotlin Multiplatform SDK for the AT Protocol and Bluesky.

> **Nubecita Support & Lexicon Schema Currency**
> This library directly powers the Android Bluesky client **Nubecita**.
> A primary objective of this repository is keeping the Lexicon schema up to date with the latest upstream changes in `@atproto/lex`, `bluesky-social/atproto` (`com.atproto`, `tools.ozone`) and `bluesky-social/bsky` (`app.bsky`, `chat.bsky`, `network.bsky`; moved out of `atproto` on 2026-10-15) so that Nubecita maintains 1:1 functional parity with the official Bluesky client app.

- **Repo:** https://github.com/kikin81/atproto-kotlin
- **Maven Central:** `io.github.kikin81.atproto:{runtime,models,oauth,compose,compose-material3}`
- **API Docs:** https://kikin81.github.io/atproto-kotlin/api/

---

## Modules

| Module | Type | Published | Purpose |
|--------|------|-----------|---------|
| `:runtime` | KMP (JVM + iOS) | Yes | Value classes (`AtUri`, `Did`, `Handle`), `AtField`, `OpenUnion`, `XrpcClient`, `AuthProvider` |
| `:generator` | JVM-only | No | Lexicon parser, IR, ref resolver, KotlinPoet emitters |
| `:models` | KMP (JVM + iOS) | Yes | Generated models, services, unions from Lexicon corpus |
| `:oauth` | JVM-only | Yes | AT Protocol OAuth 2.0 (PAR + PKCE + DPoP) for public clients |
| `:compose` | Android library | Yes | Bluesky facet → `AnnotatedString` helpers (Compose UI, no Material) |
| `:compose-material3` | Android library | Yes | Material3 `remember`-based wrapper for `BlueskyAnnotatedString` |
| `:samples:android` | Android | No | Reference Compose app with OAuth login + timeline feed |

---

## Build Prerequisites

- **JDK 17** (tracked by `.java-version`)
- **Node 22+** (only to refresh the Lexicon corpus with `npx lex install`; normal builds use the committed corpus)
- **Android SDK** (API 37+, needed for `:samples:android`, `:compose`, `:compose-material3`)
- **Gradle 9.3.1** (wrapper included: `./gradlew`)

---

## Key Commands

```bash
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

---

## Lexicon Schema Currency & Upstream Parity

To maintain parity for **Nubecita** with the official Bluesky client:

1. **Check latest upstream version:**
   ```bash
   npm view @atproto/lex version
   ```
2. **Update dependency pin:**
   In `generator/package.json`, bump `@atproto/lex` to the latest version.
3. **Refresh the corpus** (rewrites the committed `generator/lexicons/` and the CIDs in `lexicons.json`; commit both):
   ```bash
   cd generator && npm install @atproto/lex@latest --save-exact && npx lex install && cd ..
   ```
4. **Regenerate models:**
   ```bash
   ./gradlew :generator:generateModels --no-configuration-cache
   ```
5. **Detect uncovered NSIDs / drift** (reads both upstream repos; sources are declared in `generator/.../tools/UpstreamSources.kt`):
   ```bash
   ./gradlew :generator:detectLexiconDrift
   ```
6. **Audit vendored overlays:**
   ```bash
   ./gradlew :generator:detectStaleOverlays
   ```
7. **Re-dump the public API:**
   ```bash
   ./gradlew :models:apiDump
   ```
   Golden files only change when generator code changes (they use their own test lexicons), so a corpus refresh never needs `GOLDEN_UPDATE=1`.
8. **Automated workflows:**
   - `.github/workflows/lexicon-refresh.yaml`: Daily. Re-resolves pinned lexicons from the network, auto-adopts new stable `app.bsky.*` / `chat.bsky.*` NSIDs (rules in `autoAdoptCandidates()` in `LexiconDriftDetect.kt`; install/verify in `.github/scripts/lexicon-adopt.sh`), and opens/updates a `chore/lexicon-refresh` PR. Title is `feat(lexicons): …` when it adopted something, `chore(lexicons): …` otherwise; labeled `breaking-change` when `models.api` loses public symbols.
   - `.github/workflows/lexicon-bump.yaml`: Weekly `@atproto/lex` npm bump PR.
   - `.github/workflows/lexicon-drift-detect.yaml`: Daily drift detection against the upstream git tree (new/removed NSIDs → tracking issue).
   - `.github/workflows/overlay-staleness.yaml`: Audits vendored overlays against upstream.

---

## Architecture: Code Generation Pipeline

1. **Parse**: `LexiconParser` deserializes Lexicon JSON into IR (`Definition`, `FieldType`).
2. **Resolve**: `RefResolver` builds a `SymbolTable` mapping `DefKey -> Definition`.
3. **Context tag**: `ContextTagger` marks each def as Mutation/Read/Both.
4. **Name**: `NamingMatrix` assigns Kotlin `FqName`s per the naming rules (e.g., dropping `.defs` suffix: `app.bsky.feed.defs` -> `io.github.kikin81.atproto.app.bsky.feed`).
5. **Verify**: `VerificationPass` enforces INV-1..4 (no name collisions).
6. **Plan**: `EmissionPlan` computes union sites, membership, contextual splits.
7. **Emit**: `ModelGenerator`, `XrpcGenerator`, `UnionGenerator`, `ServiceGenerator` produce KotlinPoet `FileSpec`s with KDoc and `@Deprecated` annotations.

Generated source lands in `models/build/generated/source/lexicon/commonMain/kotlin/`. Never hand-edit files in `build/generated/`.

---

## Testing

- **JUnit 5** via `kotlin.test` across all modules
- **MockEngine** (Ktor) for HTTP-level tests in OAuth and sample modules
- **GoldenFileTest**: byte-for-byte regression test for generator output. Update with `GOLDEN_UPDATE=1`
- Golden lexicons: `generator/src/test/resources/golden/lexicons/`
- Production corpus: `generator/lexicons/` is committed; CI builds from it offline and only checks that its NSIDs match the `lexicons.json` pins
- Golden output: `generator/src/test/resources/golden/kotlin/`

---

## Publishing

- **Semantic-release** via `open-turo/actions-jvm/release` on every push to `main`
- Publishes to **Maven Central** (auto-promoted) and **GitHub Packages**
- Version tracked in `gradle.properties`, bumped automatically by CI
- GPG signing via in-memory key (CI secrets)
- Dokka-generated javadoc JARs replace empty stubs

---

## CI/CD

- `.github/workflows/ci.yaml`: lint + test + build on every push/PR
- `.github/workflows/release.yaml`: gate jobs -> semantic-release -> publish -> API docs to GitHub Pages
- `.github/workflows/lexicon-refresh.yaml`: daily PR that refreshes the committed Lexicon corpus and CIDs and adopts new stable bsky lexicons
- `.github/workflows/lexicon-drift-detect.yaml`: daily check for new/removed upstream NSIDs
- `.github/workflows/lexicon-bump.yaml`: weekly PR that bumps `@atproto/lex`
- `.github/workflows/overlay-staleness.yaml`: audits vendored overlays against upstream
- GitHub Pages serves `docs/` (includes `oauth/client-metadata.json` and `api/` Dokka docs)

---

## OpenSpec Workflow

Changes are proposed and tracked under `openspec/changes/<name>/` using the spec-driven schema: `proposal.md` -> `design.md` -> `specs/` -> `tasks.md`. Completed changes are archived to `openspec/changes/archive/` and delta specs are synced to `openspec/specs/`.

```bash
openspec list                           # see active + archived changes
openspec status --change <name>         # artifact-level progress
```

---

## Key Conventions

- `AuthProvider` interface supports both Bearer tokens and DPoP proof-of-possession.
- `JsonObject.decodeRecord<T>()` extension for typed access to `unknown` record fields.
- `AtField<T>` three-state optionality: `Missing` / `Null` / `Defined(value)` for mutation paths.
- Open unions use `interface` (not `sealed`) with `Unknown` fallback for forward compat.
- Package names drop `.defs` suffix: `app.bsky.feed.defs` -> `io.github.kikin81.atproto.app.bsky.feed`.

---

## Non-Interactive Shell Commands

**ALWAYS use non-interactive flags** with file operations to avoid hanging on confirmation prompts.

Shell commands like `cp`, `mv`, and `rm` may be aliased to include `-i` (interactive) mode on some systems, causing the agent to hang indefinitely waiting for y/n input.

**Use these forms instead:**
```bash
# Force overwrite without prompting
cp -f source dest           # NOT: cp source dest
mv -f source dest           # NOT: mv source dest
rm -f file                  # NOT: rm file

# For recursive operations
rm -rf directory            # NOT: rm -r directory
cp -rf source dest          # NOT: cp -r source dest
```

**Other commands that may prompt:**
- `scp` - use `-o BatchMode=yes` for non-interactive
- `ssh` - use `-o BatchMode=yes` to fail instead of prompting
- `apt-get` - use `-y` flag
- `brew` - use `HOMEBREW_NO_AUTO_UPDATE=1` env var

<!-- BEGIN BEADS INTEGRATION v:1 profile:minimal hash:ca08a54f -->
## Beads Issue Tracker

This project uses **bd (beads)** for issue tracking. Run `bd prime` to see full workflow context and commands.

### Quick Reference

```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

### Rules

- Use `bd` for ALL task tracking — do NOT use TodoWrite, TaskCreate, or markdown TODO lists
- Run `bd prime` for detailed command reference and session close protocol
- Use `bd remember` for persistent knowledge — do NOT use MEMORY.md files

## Session Completion

**When ending a work session**, you MUST complete ALL steps below. Work is NOT complete until `git push` succeeds.

**MANDATORY WORKFLOW:**

1. **File issues for remaining work** - Create issues for anything that needs follow-up
2. **Run quality gates** (if code changed) - Tests, linters, builds
3. **Update issue status** - Close finished work, update in-progress items
4. **PUSH TO REMOTE** - This is MANDATORY:
   ```bash
   git pull --rebase
   bd dolt push
   git push
   git status  # MUST show "up to date with origin"
   ```
5. **Clean up** - Clear stashes, prune remote branches
6. **Verify** - All changes committed AND pushed
7. **Hand off** - Provide context for next session

**CRITICAL RULES:**
- Work is NOT complete until `git push` succeeds
- NEVER stop before pushing - that leaves work stranded locally
- NEVER say "ready to push when you are" - YOU must push
- If push fails, resolve and retry until it succeeds
<!-- END BEADS INTEGRATION -->
