## Overview

Reorder the `release` job in `.github/workflows/release.yaml` so Dokka
runs after semantic-release has bumped `gradle.properties`. The result:
the published docs always carry the same version label as the JARs
shipped in the same release.

## Current state

```
release:
  needs: [lint, test, build]
  steps:
    - Checkout                                      gradle.properties = OLD
    - Set up Node.js
    - Install lexicon corpus
    - Set up JDK
    - Setup Android SDK
    - Setup Gradle

    - Generate API docs                             ← reads OLD version  ❌
      run: ./gradlew :generator:generateModels :dokkaGeneratePublicationHtml

    - Stage API docs for GitHub Pages
      run: rm -rf docs/api && cp -r build/dokka/html docs/api

    - uses: stefanzweifel/git-auto-commit-action@v5
      with:
        commit_message: "docs: update API documentation [skip ci]"
        file_pattern: "docs/api/**"

    - id: release                                   ← bumps gradle.properties,
      uses: open-turo/actions-jvm/release@v2         creates tag, runs publish
```

## Target state

```
release:
  needs: [lint, test, build]
  steps:
    - Checkout
    - Set up Node.js
    - Install lexicon corpus
    - Set up JDK
    - Setup Android SDK
    - Setup Gradle

    - id: release                                   ← bumps gradle.properties
      uses: open-turo/actions-jvm/release@v2          on release-worthy commits

    - Generate API docs                             ← reads NEW version  ✅
      run: ./gradlew :generator:generateModels :dokkaGeneratePublicationHtml

    - Stage API docs for GitHub Pages
      run: rm -rf docs/api && cp -r build/dokka/html docs/api

    - uses: stefanzweifel/git-auto-commit-action@v5
      with:
        commit_message: "docs: update API documentation [skip ci]"
        file_pattern: "docs/api/**"
```

Four steps moved as a block. No other changes.

## Why we're confident: evidence from the v5.0.0 release

A legitimate concern when proposing this reorder: **does semantic-release
actually mutate `gradle.properties` on the runner's filesystem during the
release step, or does it only compute the next version in memory and
pass it to gradle as a flag?** If the latter, the reorder wouldn't help
— Dokka would still read the pre-bump version.

Evidence from run #24793397775 (the feat! v5.0.0 release) shows the
former. Key timestamps extracted from the `Release` job logs:

```
17:49:03  semantic-release "prepare" phase starts
17:49:03  [@semantic-release/git] "Found 1 file(s) to commit"
17:49:04  [@semantic-release/git] commits + pushes to origin
17:49:04  [@semantic-release/git] "Prepared Git release: v5.0.0"
17:49:06  Created tag v5.0.0
17:49:07  Published GitHub release
17:49:07  [gradle-semantic-release-plugin] "publish" phase starts
          → ./gradlew publish  (reads gradle.properties from disk)
```

Inspecting the resulting commit confirms the on-disk mutation:

```
$ git show e81bfdd6 --stat
 gradle.properties | 2 +-
 1 file changed, 1 insertion(+), 1 deletion(-)

$ git show e81bfdd6 -- gradle.properties
-version = 4.9.0
+version = 5.0.0
```

The semantic-release plugin chain is:

```
@semantic-release/github
gradle-semantic-release-plugin
@semantic-release/git
@semantic-release/exec
```

`gradle-semantic-release-plugin` writes the new version into
`gradle.properties`. `@semantic-release/git` then stages, commits, and
pushes that change — all during the `prepare` hook, before the `publish`
hook runs `./gradlew publish`. The commit becomes part of the working
tree on the runner, so **any step after the release step reads the
bumped version**.

An independent corroboration: when the `chore/cleanup-post-v5-release`
PR (#18) merged, `release.yaml` ran its unconditional "Generate API
docs" step against a `gradle.properties` that already carried
`version = 5.0.0` (from the prior release's commit on main). Dokka
generated docs with the correct label and the auto-commit pushed them.
The live site flipped from `4.9.0` to `5.0.0` without any further
intervention. That confirms the Gradle-side mechanics work; the reorder
generalizes this to the release push itself.

## Why the Gradle side already supports this

Each `./gradlew` invocation re-reads `gradle.properties` on configuration.
`Project.version` is not cached across invocations. Semantic-release
commits the bumped `gradle.properties` to the working tree (via its
`@semantic-release/exec` / `gradle-semantic-release-plugin` step) before
returning. So any subsequent Gradle command sees the new version.

No Dokka plugin configuration changes are needed. `runtime/build.gradle.kts`,
`models/build.gradle.kts`, `oauth/build.gradle.kts`, and the root
`build.gradle.kts` stay untouched.

## Why simple reorder over parameter-passing

Two viable approaches:

### A — Simple reorder (chosen)

Move Dokka after semantic-release. Dokka reads the bumped
`gradle.properties`.

### B — Dry-run + `-Pversion=<next>`

Run semantic-release in dry-run mode first to compute the next version,
pass it explicitly to Dokka via `-Pversion=<next>`, then run the real
release:

```
steps:
  - Dry-run semantic-release, capture next-version output
  - ./gradlew :dokkaGeneratePublicationHtml -Pversion=<next>
  - Stage + commit docs
  - Real semantic-release
```

This decouples Dokka from `gradle.properties` state entirely.

**Why A wins**:

- **Simplicity**: one block of YAML moved. B adds a new "dry-run"
  invocation of semantic-release plus output parsing, which roughly
  doubles the step count.
- **Matches what the build already does**: Gradle already reads
  `Project.version` from `gradle.properties`. B introduces a second
  source of truth (the CLI flag) that conflicts with the first.
- **B's theoretical advantage doesn't matter here**: B lets you
  regenerate docs for a failed release. But if the release itself fails,
  regenerating docs with the "would-have-been" version is misleading —
  consumers would see docs for a version that doesn't exist on Central.
  A's behavior (fail docs together with the release, publish both on
  success) is the desired coupling.

If we ever need to publish docs for dev snapshots or pre-release tags
without a real Central release, B becomes more interesting. Not
applicable today.

## Failure modes

### Docs generation fails after a successful release

Under the current ordering, a Dokka failure blocks semantic-release and
the JARs never ship. After the reorder, the JARs ship first and Dokka
failure leaves the docs site at its previous state.

**Why this trade is the right direction**:

- The artifacts on Maven Central are the product of record. A release
  that successfully publishes JARs should not be blocked by a docs
  failure — consumers care about the JARs first, docs second.
- A docs failure is highly visible (CI goes red, docs site stays stale)
  and recoverable: push a trivial `docs:` commit (or re-run the
  workflow), and the unconditional regeneration step runs again.
- The current ordering has a worse failure mode that has already bitten:
  a successful docs step ships docs labeled with the wrong version.
  Visible only on careful inspection, not flagged by CI.

### Semantic-release step fails

No change. Release job fails, docs not regenerated, JARs not published.
Same as today.

### Non-release push (only `chore:` / `docs:` / `test:` / `refactor:`
commits since last tag)

No change. `open-turo/actions-jvm/release` is a no-op:
`gradle.properties` is untouched. Dokka still runs in the reordered
step, reads the current released version, and the auto-commit
regenerates `docs/api/` to reflect whatever content changes warranted
the push. Docs stay in sync.

### Concurrent pushes to `main`

No change. GitHub Actions queues workflow runs per-ref for
`git-auto-commit-action` invocations to avoid `git push` race
conditions. If we ever need stronger guarantees, a `concurrency: main`
block on the job is the standard fix.

## Idempotency and re-runs

The reordered pipeline is safe to re-run:

- Re-running after a successful end-to-end run: semantic-release sees
  the just-created tag, computes no new release, exits cleanly. Dokka
  regenerates from current `gradle.properties` (still the just-bumped
  version). Auto-commit either finds no diff (no commit created) or
  commits trivial drift.
- Re-running after a partial failure (release succeeded, Dokka failed):
  same as the non-release path above. `gradle.properties` is already at
  the new version. Dokka regenerates. Docs catch up.

## Test plan

### Cannot be fully tested without a real release

The correct behavior only manifests on a push that bumps a version.
`feat:` / `fix:` / `BREAKING CHANGE:` commits trigger it;
`chore:` / `docs:` / etc. don't. A local test of the YAML change
cannot replicate `open-turo/actions-jvm/release`'s gradle.properties
mutation because that action is GHA-only.

### Pre-merge verification

- [ ] Run `actionlint` locally on the edited `release.yaml` (or rely on
  the workflow's own Lint GitHub Actions workflow files hook).
- [ ] Visually confirm the four Dokka-related steps follow (not
  precede) the `id: release` step.
- [ ] Confirm no other step reads `build/dokka/html` or `docs/api/`
  between the Dokka generation and the auto-commit.

### Post-merge verification

- [ ] Next release-triggering push to `main`: inspect the workflow run
  and confirm `open-turo/actions-jvm/release` runs before the
  "Generate API docs" step.
- [ ] After that release: `curl -s https://kikin81.github.io/atproto-kotlin/api/ | grep library-version`
  shows the same version as `git describe --tags` on `main`.
- [ ] After one subsequent non-release push (e.g., a `chore:` or
  `docs:` PR merge): site still shows the last released version,
  not a stale one.
