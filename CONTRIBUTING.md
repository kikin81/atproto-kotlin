# Contributing to atproto-kotlin

Thanks for considering a contribution! This project is a code-generated AT
Protocol Kotlin Multiplatform SDK, so there are a few quirks worth knowing
about before you open a PR.

## Quick start

```bash
# 1. Clone and install the upstream lexicon corpus (pinned by CID).
git clone https://github.com/kikin81/atproto-kotlin.git
cd atproto-kotlin/at-protocol-generator && npx lex install --ci && cd -

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
| `:at-protocol-runtime` | **Yes** | Value classes, `AtField`, `OpenUnionSerializer`, `XrpcClient`, `AuthProvider`. Edit freely. |
| `:at-protocol-generator` | **Yes** | The Lexicon parser, IR, and KotlinPoet emitters. Edit freely, but see "Regenerating models" below. |
| `:at-protocol-models` | **No — generated** | Do not edit files under `at-protocol-models/build/generated/…`. To change generated output, edit the generator. |
| `:at-protocol-oauth` | **Yes** | OAuth 2.0 + PAR + PKCE + DPoP. Edit freely. |
| `:samples:android` | **Yes** | Reference Compose consumer. Great place to verify end-to-end changes. |

### Regenerating models after generator changes

```bash
./gradlew :at-protocol-models:generateModels
```

If you change emitter output, update the golden fixtures:

```bash
GOLDEN_UPDATE=1 ./gradlew :at-protocol-generator:test --tests '*GoldenFileTest*'
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
  enough. `./gradlew :at-protocol-runtime:jvmTest` or
  `./gradlew :at-protocol-models:jvmTest`.
- **Generator:** run the golden file tests and review the diff.
- **OAuth:** `./gradlew :at-protocol-oauth:test` covers the MockEngine
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
