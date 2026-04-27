# Contributing to atproto-kotlin

Thanks for considering a contribution! This project is a code-generated AT
Protocol Kotlin Multiplatform SDK, so there are a few quirks worth knowing
about before you open a PR.

## Quick start

```bash
# 1. Clone and install the upstream lexicon corpus (pinned by CID).
git clone https://github.com/kikin81/atproto-kotlin.git
cd atproto-kotlin/generator && npx lex install --ci && cd -

# 2. Build everything: runtime, generator, models, oauth, sample.
./gradlew build
```

**Prerequisites:**

- **JDK 17** (tracked by `.java-version` / `.sdkmanrc`)
- **Node 22+** (for `npx lex install`)
- **Android SDK** (API 36+, only if you're touching `:samples:android`)

Gradle 9.3.1 ships via the wrapper — no separate install needed.

## Repo map: what you can edit

| Module | Hand-written? | Notes |
|---|---|---|
| `:runtime` | **Yes** | Value classes, `AtField`, `OpenUnionSerializer`, `XrpcClient`, `AuthProvider`. Edit freely. |
| `:generator` | **Yes** | The Lexicon parser, IR, and KotlinPoet emitters. Edit freely, but see "Regenerating models" below. |
| `:models` | **No — generated** | Do not edit files under `models/build/generated/…`. To change generated output, edit the generator. The pinned lexicon corpus is bumped automatically — see [Lexicon updates](#lexicon-updates). |
| `:oauth` | **Yes** | OAuth 2.0 + PAR + PKCE + DPoP. Edit freely. |
| `:samples:android` | **Yes** | Reference Compose consumer. Great place to verify end-to-end changes. |

### Regenerating models after generator changes

```bash
./gradlew :models:generateModels
```

If you change emitter output, update the golden fixtures:

```bash
GOLDEN_UPDATE=1 ./gradlew :generator:test --tests '*GoldenFileTest*'
```

Review the diff carefully — golden updates are a signal, not a rubber stamp.

## The contribution flow

1. **Open an issue first** for anything non-trivial. It's faster to align on
   approach before code than to rework a PR. For typo fixes or small
   documentation tweaks, just open a PR.
2. **Fork + branch** from `main`. Use a descriptive branch name
   (`feat/<thing>`, `fix/<thing>`).
3. **Write tests.** JUnit 5 via `kotlin.test` across all modules. MockEngine
   (Ktor) for HTTP-level tests. See existing tests for the pattern.
4. **Verify locally** before pushing:
   ```bash
   ./gradlew build spotlessCheck
   ```
5. **Conventional Commits** — enforced by commitlint. Use `feat:`, `fix:`,
   `chore:`, `docs:`, `test:`, `refactor:`, `ci:`. A `feat:` or `fix:` on
   `main` cuts a release, so pick the prefix deliberately.
6. **Install pre-commit hooks once** so formatting + commit-msg validation run
   locally:
   ```bash
   pre-commit install && pre-commit install --hook-type commit-msg
   ```
7. **Open a PR** against `main`. Fill out the template. CI must be green.

## Verifying your change

Depending on what you touched:

- **Runtime or models:** unit tests under the relevant module are usually
  enough. `./gradlew :runtime:jvmTest` or
  `./gradlew :models:jvmTest`.
- **Generator:** run the golden file tests and review the diff.
- **OAuth:** `./gradlew :oauth:test` covers the MockEngine
  flows. For end-to-end DPoP you'll want to run the Android sample.
- **Anything consumer-facing:** build and run the sample app —
  `./gradlew :samples:android:installDebug`. Logging in and scrolling the
  timeline exercises OAuth, XRPC, open unions, and the generated service
  surface in one go.

## Code style

Spotless + ktlint 1.4.1 are enforced. Pre-commit hooks and CI will block
unformatted code. If you didn't install the hooks, run:

```bash
./gradlew spotlessApply
```

before committing. No wildcard imports. No max line length. Compose
`@Composable` function naming is disabled (lowercase-friendly helper names
are fine).

## Public API surface

The `:runtime`, `:models`, and `:oauth` modules track every public symbol
via [kotlinx-binary-compatibility-validator](https://github.com/Kotlin/binary-compatibility-validator).
Each module owns its dump under `<module>/api/<module>.api` (for example,
`runtime/api/runtime.api`, `models/api/models.api`, `oauth/api/oauth.api`)
and these text files are under version control; CI runs `./gradlew apiCheck`
and fails on any unexpected diff.

If your change intentionally adds, removes, or modifies a public symbol,
regenerate the dumps and commit them alongside the code:

```bash
./gradlew apiDump
git add runtime/api oauth/api models/api
```

The diff in `api/*.api` becomes a clear review signal — reviewers can
see exactly what entered or left the public surface without spelunking
through the source.

`:compose` and `:compose-material3` aren't tracked yet (Android library
modules need explicit per-module wiring; tracked separately). `:generator`
and `:samples:android` are intentionally excluded — neither is consumed
externally, so their public surface is internal.

## Lexicon updates

Upstream AT Protocol lexicons are bumped automatically. A scheduled
[`lexicon-bump`](.github/workflows/lexicon-bump.yaml) workflow runs
weekly (Mondays 14:00 UTC) and opens a PR titled
`chore(lexicons): bump @atproto/lex to <v>` whenever upstream has
drifted from the pinned version. The PR includes the updated
`generator/package.json`, `generator/package-lock.json`, and
`generator/lexicons.json`, plus a CID-level summary of what changed.

To trigger an ad-hoc bump, run the `Lexicon bump` workflow manually
via the **Actions** tab on GitHub (`workflow_dispatch`).

Successive runs reuse a stable `chore/lexicon-bump` branch, so a
newer upstream release will update the existing PR rather than
opening a second one.

Merging the PR cuts a release whose semver bump depends on whether
the regenerated models added, removed, or renamed types — humans
classify by editing the PR title to `feat:` or `feat!:` before
merging if a breaking change landed.

## Other dependency updates (Renovate)

JVM, npm (excluding `@atproto/lex`), Gradle, and GitHub Actions
dependencies are bumped automatically by [Renovate](https://docs.renovatebot.com/),
configured at [`.github/renovate.json`](.github/renovate.json).
The config groups Kotlin/KSP/Compose-compiler under `kotlin-ecosystem`,
Compose UI/Foundation/Animation/Material/Runtime under `compose-ui`,
and all `io.ktor:*` artifacts under `ktor` so each release lands as
one PR instead of many. Minor and patch Gradle dependency updates
auto-merge once CI is green; major bumps wait for a human.

`@atproto/lex` is deliberately excluded from Renovate — the
[`lexicon-bump`](.github/workflows/lexicon-bump.yaml) workflow above
owns it because a Renovate-only npm bump would leave
`generator/lexicons.json` out of sync and fail `npx lex install --ci`.

The Renovate dashboard issue (auto-created by the bot) is the source
of truth for queued and ignored updates.

## Larger architectural changes (OpenSpec)

For significant structural work — new modules, generator rewrites, changes
to the public API shape — this repo uses an
[OpenSpec](https://github.com/kikin81/openspec) workflow under
[`openspec/`](openspec/). You do **not** need to author an OpenSpec proposal
for a bug fix or a minor feature.

If your idea is large enough that it might need one, open an issue first
and a maintainer will guide you through the process.

## Releases (FYI)

Every push to `main` runs semantic-release. `feat:` bumps minor, `fix:`
bumps patch, `BREAKING CHANGE:` footer bumps major, everything else is
no-release. Artifacts publish to Maven Central and GitHub Packages
automatically. You don't need to touch version numbers — CI owns them.

## License

By contributing, you agree that your contributions will be licensed under
the [MIT License](LICENSE).
