## Why

The SDK's published coordinates stutter: the group already contains
`atproto`, and every artifactId repeats it as `at-protocol-*`.

```
io.github.kikin81.atproto : at-protocol-runtime : 4.9.0
                 ^^^^^^^    ^^^^^^^^^^^^
                  redundant group + artifact stutter
```

Consumers read `io.github.kikin81.atproto:at-protocol-oauth` and see the
project name twice. The convention every mature JVM library follows —
Square's OkHttp (`com.squareup.okhttp3:okhttp`), AndroidX
(`androidx.compose.ui:ui`) — is to scope artifacts under the group and
let the artifactId name only the specific module. This change adopts
that convention.

It also fixes a latent inconsistency. The repo spells the protocol three
different ways:

| Surface                       | Spelling      |
|-------------------------------|---------------|
| Group / generated package     | `atproto`     |
| Gradle modules / artifactIds  | `at-protocol` |
| GitHub repo                   | `atproto-kotlin` |

A future contributor should not have to guess which spelling belongs
where. The rename unifies on `atproto`.

Today the primary consumer is the maintainer (Nubecita, plus the
in-repo `:samples:android`). Fixing this now is cheap. Delayed six
months — after third parties pull 4.x from Central — it becomes a
breaking migration with a long tail. Do it once, cut v5.0.0, move on.

## What Changes

- **Rename Gradle modules and directories** from `at-protocol-*` to
  plain module names. The four directories `at-protocol-runtime`,
  `at-protocol-models`, `at-protocol-oauth`, and `at-protocol-generator`
  become `runtime`, `models`, `oauth`, and `generator`.
- **Rename published artifactIds** to match. Because vanniktech's
  maven-publish plugin derives artifactId from Gradle project name by
  default, the module rename drives the publishing rename.
- **Update all internal references**: `settings.gradle.kts` includes,
  every `project(":at-protocol-*")` reference, root `build.gradle.kts`
  Dokka aggregation, CI workflow paths, pre-commit hook paths,
  openspec artifacts that cite module paths, and in-repo sample app
  dependencies.
- **Update POM metadata**: `pom.name` entries per module use the new
  artifactId; URLs/SCM blocks stay pointed at the existing repo.
- **Update all documentation**: `README.md`, `CONTRIBUTING.md`,
  `CLAUDE.md`, `docs/`, root `MODULE.md`, per-module `MODULE.md`
  files, and any gradle command examples.
- **Cut v5.0.0** via a `BREAKING CHANGE:` commit footer so
  semantic-release bumps the major. Release notes document the
  coordinate map.
- **Add a short migration note** (either `MIGRATION.md` or a section
  in `README.md`) listing the old -> new coordinate pairs for any
  consumer arriving from 4.x.

## Capabilities

### New Capabilities

- `artifact-publishing`: Coordinate conventions, artifactId rules, and
  the contract between Gradle module names and Maven Central
  artifactIds.

### Modified Capabilities

_(none — this change is entirely about publishing surface; behavior of
runtime, models, and oauth modules is unchanged)_

## Impact

- **Coordinate break**: Consumers pinned to `io.github.kikin81.atproto:at-protocol-*:4.x`
  will fail to resolve `5.0.0+` under the old artifactId. No relocation
  POMs — documented in release notes. The in-repo sample app is updated
  as part of this change; Nubecita is updated separately by the
  maintainer after release.
- **Gradle commands change**: `./gradlew :at-protocol-oauth:test`
  becomes `./gradlew :oauth:test`. Every contributor-facing doc and
  every memorized command alias is affected.
- **CI workflows**: `.github/workflows/ci.yaml` and `release.yaml`
  reference module paths; both must be updated in the same commit
  that renames the directories so CI stays green.
- **Generator output**: Generated code already uses `atproto` in
  package names — no regeneration needed. Golden files do not embed
  artifactIds, so no golden-update run is required.
- **Published artifacts at old coordinates**: Remain on Central
  (immutable). Users searching Central for the library will see both
  the old 4.x line and the new 5.x line.
- **No consumer-facing code changes**: No class renames, no package
  renames, no method signatures changed. A consumer upgrading only
  edits their `implementation(...)` strings.
