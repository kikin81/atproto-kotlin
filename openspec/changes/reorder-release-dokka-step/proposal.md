## Why

The release pipeline generates Dokka HTML docs **before** semantic-release
bumps `gradle.properties`, then publishes both artifacts together. The
result: every release ships docs labeled with the *previous* version.

```
release.yaml (current):
  1. checkout              gradle.properties = 4.9.0
  2. ./gradlew :dokkaGeneratePublicationHtml   ← reads 4.9.0  ❌
  3. git-auto-commit docs/api                  ← commits 4.9.0 label
  4. open-turo/actions-jvm/release             ← bumps to 5.0.0, publishes JARs
```

Maven Central correctly ships `io.github.kikin81.atproto:runtime:5.0.0`,
but the Dokka HTML at `https://kikin81.github.io/atproto-kotlin/api/`
renders a `<div class="library-version">4.9.0</div>`. This mismatch has
existed since the docs workflow landed — it was not noticed on prior
minor bumps (4.8.0 docs say 4.7.0 is less visually alarming) but became
obvious when 5.0.0 docs said 4.9.0 after the
[rename-maven-artifacts](../archive/) shipped.

Fixing this is a ~10-line change to one file.

## What Changes

- **Reorder `.github/workflows/release.yaml`**: move the four steps that
  generate + stage + commit Dokka HTML (currently steps 3–6 of the
  `release` job) to run **after** the `open-turo/actions-jvm/release`
  step. Semantic-release commits the bumped `gradle.properties` to the
  working tree before returning, so any `./gradlew` invocation
  afterwards reads the bumped version.
- **No Gradle changes**: the Dokka configuration in
  `runtime/build.gradle.kts`, `models/build.gradle.kts`,
  `oauth/build.gradle.kts`, and the root `build.gradle.kts` is
  unchanged. `Project.version` is read fresh each invocation, so the
  Gradle side already supports this.
- **No changes to non-release pushes**: pushes with only `chore:` /
  `docs:` / `test:` / `refactor:` commits continue to regenerate docs
  unconditionally — semantic-release is a no-op on those pushes, so
  `gradle.properties` is untouched and Dokka reads the current released
  version. Docs stay fresh.

## Capabilities

### New Capabilities

_(none)_

### Modified Capabilities

- `sdk-documentation`: adds a requirement that the documentation site's
  version label match the version of the Maven Central artifacts shipped
  in the same release. The existing requirement that release deploys
  docs to GitHub Pages is strengthened — "matching the released version"
  is now an enforced property, not an incidental one.

## Impact

- **Files touched**: `.github/workflows/release.yaml` only. Roughly 10
  lines moved, no new YAML structure, no new action dependencies.
- **First release affected**: the next release after this change lands.
  Existing 5.0.0 docs remain incorrect (already shipped) until either
  the next release regenerates them, or a manual `docs:` commit
  triggers the release workflow's unconditional regeneration step. The
  cleanup PR that fixed the 5.0.0 label did the latter.
- **Release duration**: unchanged. Same steps, reordered.
- **Failure-mode shift**: if Dokka generation fails, the shift means the
  Maven Central publish already succeeded — stale docs instead of a
  blocked release. See design.md for the rationale; this is the
  desirable failure mode.
- **Breaking changes**: none. Internal pipeline only, no effect on
  consumers.
