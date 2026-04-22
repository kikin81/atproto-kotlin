## Overview

Rename every publishable Gradle module (and the non-publishable
`:at-protocol-generator`) to drop the redundant `at-protocol-` prefix,
unify the protocol spelling on `atproto` across the repo, and cut
v5.0.0 as the first release under the new coordinates.

This is a pure publishing-surface change. Runtime behavior, public
API, generated code layout, and on-the-wire XRPC semantics are
unaffected.

## Coordinate Map

### Before (4.x)

| Group                              | ArtifactId              | Gradle path               |
|------------------------------------|-------------------------|---------------------------|
| `io.github.kikin81.atproto`        | `at-protocol-runtime`   | `:at-protocol-runtime`    |
| `io.github.kikin81.atproto`        | `at-protocol-models`    | `:at-protocol-models`     |
| `io.github.kikin81.atproto`        | `at-protocol-oauth`     | `:at-protocol-oauth`      |
| _(unpublished)_                    | —                       | `:at-protocol-generator`  |

### After (5.0.0+)

| Group                              | ArtifactId  | Gradle path  |
|------------------------------------|-------------|--------------|
| `io.github.kikin81.atproto`        | `runtime`   | `:runtime`   |
| `io.github.kikin81.atproto`        | `models`    | `:models`    |
| `io.github.kikin81.atproto`        | `oauth`     | `:oauth`     |
| _(unpublished)_                    | —           | `:generator` |

The group stays identical. The sample module `:samples:android` also
stays unchanged in path — the rename applies only to top-level modules
that carry the `at-protocol-` prefix.

## Why Pattern A ("project as group")

Two industry conventions for structuring multi-module publications:

```
Pattern A — project-as-group (chosen)
  Group:    io.github.kikin81.atproto
  Artifact: oauth
  Dep:      implementation("io.github.kikin81.atproto:oauth:5.0.0")

Pattern B — org-as-group
  Group:    io.github.kikin81
  Artifact: atproto-oauth
  Dep:      implementation("io.github.kikin81:atproto-oauth:5.0.0")
```

**Pattern A wins** for this repo because:

1. It matches the rest of the Kotlin/Android ecosystem the SDK lives
   next to: OkHttp, Retrofit, AndroidX, Compose. Consumer Gradle files
   stay short and visually aligned with those dependencies.
2. The group already includes `atproto`. Changing the group to drop it
   would require abandoning the `io.github.kikin81.atproto` namespace
   that's baked into every generated package. Pattern A keeps that
   namespace intact.
3. Future sibling libraries under the same `atproto` umbrella (e.g., a
   Jetstream client, a PLC-directory client) slot naturally alongside
   as `io.github.kikin81.atproto:jetstream`, `...:plc-directory`, etc.

## Why A.2 (full rename) over A.1 (artifact-only override)

Two ways to change the published artifactId:

- **A.1**: Keep Gradle modules as `:at-protocol-*`, override the
  published artifactId via `mavenPublishing { coordinates(group, "oauth", version) }`.
- **A.2**: Rename the Gradle modules (and directories) to match the
  published artifactId. No coordinate override needed — vanniktech
  defaults to `project.name`.

A.1 is cheaper in lines-changed but preserves a mismatch between the
internal name (`:at-protocol-oauth`) and the external name (`oauth`).
Every `./gradlew` command, every contributor doc, every CI file
continues to carry the legacy spelling. Future contributors will ask
why.

A.2 breaks everything internal once, cleanly, in the same commit that
breaks the external coordinates. After v5.0.0 there is exactly one
spelling: `oauth`, `runtime`, `models`, `generator`.

The internal churn is find/replace-shaped:

```
rename dir      at-protocol-runtime  ->  runtime
rename dir      at-protocol-models   ->  models
rename dir      at-protocol-oauth    ->  oauth
rename dir      at-protocol-generator -> generator

find/replace    :at-protocol-runtime  ->  :runtime       (Gradle paths)
find/replace    :at-protocol-models   ->  :models
find/replace    :at-protocol-oauth    ->  :oauth
find/replace    :at-protocol-generator -> :generator
```

Every Gradle path reference lives in `.kts` files, CI YAML, pre-commit
config, and docs. A scripted `grep -l ':at-protocol-' | xargs sed ...`
catches them in a single pass.

## Why No Relocation POMs

Vanniktech's maven-publish plugin doesn't ship relocation support.
Wiring it up means:

- Creating stub publication units at the old coordinates
- Hand-writing `<distributionManagement><relocation>` XML into each
  stub POM
- Gating the stubs behind a one-shot release (so we don't re-publish
  relocation stubs forever)

For the current consumer set (one person, one external app), the
payoff is near zero. The migration guide is a one-line `find/replace`
in the consumer's `build.gradle.kts`. If the library picks up
external traction later, relocation POMs can be revisited as a
separate spec — but they'd be published as an add-on to the 4.x line,
not as a prerequisite for the 5.0.0 cut.

Release notes and a README migration section carry the communication
burden instead.

## Why Single Change

The rename touches coordinates, Gradle paths, CI, docs, and the sample
app. Splitting "rename artifacts" from "rename Gradle modules" creates
an intermediate state where the Gradle path is still `:at-protocol-oauth`
but the artifactId is `oauth` — confusing to contributors and offering
no value to consumers. The work lands atomically or not at all.

Tasks.md phases the work internally (pre-flight -> dir rename ->
publishing/POM -> docs -> release -> post-release) but all phases land
in a single PR, under a single `feat!:` commit so semantic-release
cuts v5.0.0.

## Spelling Unification Rules

After this change, the repo follows one rule:

- Use `atproto` (one word) everywhere the protocol is named in code,
  package names, gradle coordinates, group IDs, and module names.
- The only exception is literal strings carrying the official protocol
  name "AT Protocol" (with the space) — used in KDoc, README prose,
  POM descriptions, and user-facing documentation.

The GitHub repo (`kikin81/atproto-kotlin`) is already compliant and
stays as-is.

## Version and Release Strategy

### Semantic-release drives the bump

`.releaserc.json` is configured for conventional-commits-based
versioning. The rename lands under a single commit of the form:

```
feat!: rename modules and publish as io.github.kikin81.atproto:<module>

BREAKING CHANGE: Maven Central coordinates have changed. The
at-protocol-* artifactIds are no longer published. See MIGRATION
table below for the old->new mapping.
```

The `!` after `feat` and the `BREAKING CHANGE:` footer both trigger a
major bump. Semantic-release cuts v5.0.0 on merge to `main`.

### Release notes template

semantic-release auto-generates release notes from commit messages.
The body of the breaking commit appears in the notes. Structure it so
the rendered notes include:

1. A one-line summary of the rename.
2. The coordinate map table (identical to the one in this design).
3. A link to the migration guide (README section or `MIGRATION.md`).

### Migration guide

Add either:

- A `## Migrating from 4.x to 5.0.0` section in `README.md`, OR
- A top-level `MIGRATION.md` with the same content.

Either is acceptable. The content is:

```kotlin
// before (4.x)
implementation("io.github.kikin81.atproto:at-protocol-runtime:4.9.0")
implementation("io.github.kikin81.atproto:at-protocol-models:4.9.0")
implementation("io.github.kikin81.atproto:at-protocol-oauth:4.9.0")

// after (5.0.0+)
implementation("io.github.kikin81.atproto:runtime:5.0.0")
implementation("io.github.kikin81.atproto:models:5.0.0")
implementation("io.github.kikin81.atproto:oauth:5.0.0")
```

Plus a one-line "no code changes required — only coordinate strings
change" reassurance.

## Risk and Verification

### What could break

- **Dangling `:at-protocol-*` references**: A single missed reference
  in CI YAML or a pre-commit hook silently breaks the pipeline on
  first push. Mitigation: a repo-wide `grep -rn 'at-protocol-'` at
  the end of the rename phase, checked against an allowlist of
  expected matches (prose in CHANGELOG, release notes history,
  archived openspec proposals that reference the old names as
  historical artifacts).
- **Dokka aggregation**: Root `build.gradle.kts` depends on each
  module via `dokka(project(":at-protocol-*"))`. If not updated in
  lockstep, `./gradlew :dokkaGeneratePublicationHtml` fails.
- **Android sample not rebuilt**: `:samples:android` depends on
  `project(":at-protocol-runtime")` and peers. Must be updated in
  the same commit or the sample build breaks.
- **Semantic-release misfire**: If the commit message omits the
  breaking-change marker, the release cuts as a minor (4.10.0)
  under the new coordinates, and consumers installing `4.10.x` get
  a coordinate 404. Mitigation: verify commit message before merge;
  dry-run semantic-release locally if possible.

### Verification gates before merge

1. `./gradlew build` succeeds.
2. `./gradlew spotlessCheck` passes.
3. `./gradlew :runtime:publishToMavenLocal :models:publishToMavenLocal :oauth:publishToMavenLocal`
   produces artifacts under the expected new coordinates in
   `~/.m2/repository/io/github/kikin81/atproto/{runtime,models,oauth}/`.
4. `grep -rn 'at-protocol-' --include='*.kts' --include='*.yaml' --include='*.yml' --include='*.md' .`
   returns only expected historical matches.
5. `:samples:android:installDebug` builds and runs.
6. CI green on the rename PR before merging to `main`.

### Generated code / golden files

The generator emits into `io.github.kikin81.atproto.*` packages —
those use `atproto` already. Golden files under
`at-protocol-generator/src/test/resources/golden/` do not embed
artifactIds; they're pure Kotlin source fixtures. No regeneration or
`GOLDEN_UPDATE=1` pass is required. _(The directory itself moves from
`at-protocol-generator/` to `generator/` as part of the rename, but
that's a directory move, not a content change.)_
