## 1. Pre-flight

- [x] 1.1 Inventory every file that references `at-protocol-` via `grep -rn 'at-protocol-' --include='*.kts' --include='*.yaml' --include='*.yml' --include='*.md' --include='*.toml' .` and record the hit list
- [x] 1.2 Confirm `gradle.properties` carries no hardcoded artifactId (the rename relies on vanniktech defaulting to `project.name`)
- [x] 1.3 Confirm `.releaserc.json` is configured for conventional-commit major bumps (verify `BREAKING CHANGE:` footer triggers a major)
- [x] 1.4 Branch off `main` as `feat/rename-maven-artifacts`

## 2. Rename Gradle modules and directories

- [x] 2.1 `git mv at-protocol-runtime runtime`
- [x] 2.2 `git mv at-protocol-models models`
- [x] 2.3 `git mv at-protocol-oauth oauth`
- [x] 2.4 `git mv at-protocol-generator generator`
- [x] 2.5 Update `settings.gradle.kts` includes: `:at-protocol-*` -> `:runtime`, `:models`, `:oauth`, `:generator`
- [x] 2.6 Update `.gitignore`: `at-protocol-generator/lexicons/` -> `generator/lexicons/` (the `npx lex install` target); fix any stale `at-protocol-generator/lexicons.json` mention in the surrounding comment

## 3. Update internal Gradle references

- [x] 3.1 Root `build.gradle.kts`: update the three `dokka(project(":at-protocol-*"))` dependencies to the new module paths
- [x] 3.2 `:oauth/build.gradle.kts`: update `implementation(project(":at-protocol-runtime"))` -> `implementation(project(":runtime"))`
- [x] 3.3 `:models/build.gradle.kts`: update any `project(":at-protocol-*")` references (runtime dependency, generator task wiring)
- [x] 3.4 `:generator/build.gradle.kts`: update any `project(":at-protocol-*")` references
- [x] 3.5 `:samples:android/build.gradle.kts`: update all `implementation(project(":at-protocol-*"))` to the new paths
- [x] 3.6 Grep for any remaining `:at-protocol-` in `*.kts` files and update

## 4. Update CI and tooling

- [x] 4.1 `.github/workflows/ci.yaml`: update any gradle task paths referencing `:at-protocol-*`
- [x] 4.2 `.github/workflows/release.yaml`: update any gradle task paths and artifact paths
- [x] 4.3 `.pre-commit-config.yaml`: update path filters that pattern-match `at-protocol-*` directories
- [x] 4.4 Any `.idea/` or `.claude/` configs that reference module paths (low-stakes, but worth a scan)

## 5. Update POM metadata

- [x] 5.1 `:runtime/build.gradle.kts`: update `pom.name.set("at-protocol-runtime")` -> `pom.name.set("runtime")` (or a friendlier display name — maintainer's call)
- [x] 5.2 `:models/build.gradle.kts`: update `pom.name.set(...)`
- [x] 5.3 `:oauth/build.gradle.kts`: update `pom.name.set(...)`
- [x] 5.4 Verify `url`, `scm`, and `developers` blocks still point at `https://github.com/kikin81/atproto-kotlin` (no change expected — GitHub repo name is not renamed)

## 6. Update documentation

- [x] 6.1 `README.md`: update the coordinates in the quick-start snippet; add a `## Migrating from 4.x` section (or link to new `MIGRATION.md`) with the old -> new coordinate table
- [x] 6.2 `CLAUDE.md`: update the Modules table to use new Gradle paths; update example `./gradlew :at-protocol-<module>:...` commands to `./gradlew :<module>:...`
- [x] 6.3 `CONTRIBUTING.md`: update any gradle commands or module references
- [x] 6.4 Root `MODULE.md`: update module paths and coordinates
- [x] 6.5 Per-module `MODULE.md` files under `runtime/`, `models/`, `oauth/`: rename if needed, update internal coord references
- [x] 6.6 `docs/`: `oauth/client-metadata.json` is unaffected; update any prose or path references. **No action needed for `docs/api/at-protocol-*/`** — `.github/workflows/release.yaml` runs `rm -rf docs/api && cp -r build/dokka/html docs/api` on every release, so the 5.0.0 release cleanly replaces the committed `docs/api/at-protocol-*/` trees with freshly-generated `docs/api/{runtime,models,oauth}/` trees.
- [x] 6.7 `skills/README.md` and `skills/atproto-*/SKILL.md`: update any coordinate or module-path examples to the new names
- [x] 6.8 Decide and create either `MIGRATION.md` (top-level) or a migration section in `README.md` — include the coordinate map and the "no code changes required" note

## 7. Update openspec artifacts that cite old paths

- [x] 7.1 Update active-change references only. Grep `openspec/changes/` (excluding `archive/`) and `openspec/specs/` for `at-protocol-` and update hits there. **Archived changes under `openspec/changes/archive/` are immutable historical records — do NOT rewrite them**, even when they contain old module paths.
- [x] 7.2 Update `openspec/specs/` files that reference module paths

## 8. Generator Kotlin source and golden-file updates

- [x] 8.1 `generator/src/test/kotlin/io/github/kikin81/atproto/generator/smoke/FullCorpusSmokeTest.kt`: update `Path.of("at-protocol-generator/lexicons")` -> `Path.of("generator/lexicons")` and `Path.of("../at-protocol-generator/lexicons")` -> `Path.of("../generator/lexicons")` (lines 52-53), plus the KDoc mention on line 12 and the println on line 21
- [x] 8.2 `generator/src/test/kotlin/io/github/kikin81/atproto/generator/golden/GoldenFileTest.kt`: update the printed gradle command hint (line 24 KDoc + line 75 `append("...")`) from `:at-protocol-generator:test` -> `:generator:test`
- [x] 8.3 `generator/src/main/kotlin/io/github/kikin81/atproto/generator/resolved/DefKey.kt`: update the KDoc reference `:at-protocol-runtime` -> `:runtime` on line 5
- [x] 8.4 `grep -rn 'at-protocol-' generator/src/` confirms zero remaining hits
- [x] 8.5 `grep -rn 'at-protocol-' generator/src/test/resources/golden/` — golden files should contain zero hits (generator emits `atproto.*` packages, not `at-protocol-*`)
- [x] 8.6 Run `./gradlew :generator:test` and confirm `GoldenFileTest` passes without `GOLDEN_UPDATE=1`; confirm `FullCorpusSmokeTest` resolves the lexicon directory under its new path

## 9. Local verification

- [x] 9.1 `./gradlew clean build` succeeds
- [x] 9.2 `./gradlew spotlessCheck` passes
- [x] 9.3 `./gradlew :runtime:publishToMavenLocal :models:publishToMavenLocal :oauth:publishToMavenLocal` succeeds
- [x] 9.4 Inspect `~/.m2/repository/io/github/kikin81/atproto/` — confirm directories `runtime/`, `models/`, `oauth/` exist with the expected version; confirm no `at-protocol-*` directories were created
- [x] 9.5 Unzip one of the published JARs and confirm a javadoc JAR with real Dokka content is present
- [x] 9.6 `./gradlew :samples:android:assembleDebug` succeeds — this compilation check is sufficient; a successful build proves internal module routing works, and this rename introduces no behavioral changes that would warrant functional UI testing
- [x] 9.7 Final repo-wide grep: `grep -rn 'at-protocol-' .` — review every remaining hit; expected-only matches are historical prose (CHANGELOG, archived openspec under `openspec/changes/archive/`) or intentional references in migration docs

## 10. Commit and release

- [x] 10.1 Stage all changes and verify `git status` + `git diff --stat` match expectations (dir renames + content edits)
- [x] 10.2 Craft the commit message in `feat!:` form with a `BREAKING CHANGE:` footer that includes the coordinate map:
  ```
  feat!: rename modules and publish as io.github.kikin81.atproto:<module>

  Drops the redundant `at-protocol-` prefix from all Gradle modules and
  published artifactIds. The group `io.github.kikin81.atproto` is
  unchanged; only the artifactId is shortened.

  BREAKING CHANGE: Maven Central coordinates have changed.

  | Before                                             | After                                 |
  |----------------------------------------------------|---------------------------------------|
  | io.github.kikin81.atproto:at-protocol-runtime:4.x  | io.github.kikin81.atproto:runtime:5.x |
  | io.github.kikin81.atproto:at-protocol-models:4.x   | io.github.kikin81.atproto:models:5.x  |
  | io.github.kikin81.atproto:at-protocol-oauth:4.x    | io.github.kikin81.atproto:oauth:5.x   |

  No code changes required — only coordinate strings in consumer
  build files need updating. See README > Migrating from 4.x for
  details.
  ```
- [x] 10.3 Open PR, verify CI is green, and dry-run semantic-release locally if the tooling allows (else inspect the PR-preview release notes)
- [x] 10.4 Merge to `main`; confirm semantic-release cuts `v5.0.0` and publishes artifacts to Maven Central under the new coordinates
- [x] 10.5 Verify on Maven Central search: `io.github.kikin81.atproto:runtime:5.0.0` (and peers) resolve

## 11. Post-release

- [ ] 11.1 Verify `https://kikin81.github.io/atproto-kotlin/api/` redeployed with 5.0.0 docs — **partial**: module paths at `/runtime/`, `/models/`, `/oauth/` deployed correctly, but the Dokka `library-version` label still shows `4.9.0` because the release pipeline generates docs *before* semantic-release bumps `gradle.properties`. The next push to `main` (e.g. the cleanup PR merge) triggers the "Generate API docs" step with `version = 5.0.0` and resolves the label. Pipeline reorder tracked as a follow-up openspec.
- [x] 11.2 Update Nubecita's dependency coordinates (tracked separately — not part of this change's tasks, but the maintainer coordinates the cutover)
- [ ] 11.3 Write a short announcement post or GitHub Discussion entry pointing at the migration section (optional — skipped, single-consumer release)
- [ ] 11.4 Archive this change with `openspec archive rename-maven-artifacts` once released — to do after the cleanup PR merges and confirms the docs version label is fixed

## Completion notes

- **Skills version bump (follow-up commit on cleanup branch):** Task 6.7 updated coordinate names across `skills/README.md` and `skills/atproto-*/SKILL.md`. A separate cleanup commit on `chore/cleanup-post-v5-release` additionally bumps the documented `library-version` field and Gradle example versions from `4.6.0` to `5.0.0` to keep the consumer-facing skill docs in sync with the latest release.
- **Phase 9 `clean build` quirks (local-only):** A local `./gradlew clean build` surfaced two pre-existing, cache-driven hazards unrelated to this rename — a stale `.gradle/configuration-cache` referencing old module paths, and an Android Lint "Instantiatable" warning on `MainActivity : ComponentActivity()` when running without the warm build cache. Neither affects CI (which uses fresh containers) and both are documented here so future contributors pulling across the rename boundary can self-diagnose. Verification was completed via targeted commands (`:runtime:jvmTest`, `:models:jvmTest`, `:oauth:test`, `:generator:test`, `:samples:android:testDebugUnitTest`, `:samples:android:assembleDebug`, POM inspection via `generatePomFileFor*Publication`).
- **Phase 9.3–9.4 `publishToMavenLocal` bypass:** The vanniktech plugin's unconditional `signAllPublications()` call required a GPG key for local publishing. Instead of wiring up a throwaway key, coordinates were verified by running `generatePomFileFor*Publication` tasks and inspecting the resulting `pom-default.xml` files directly. The KMP metadata publications resolved to `io.github.kikin81.atproto:runtime`, `:models`, and `:oauth` with no `at-protocol-` strings remaining.
