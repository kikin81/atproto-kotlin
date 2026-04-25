## ADDED Requirements

### Requirement: Published documentation version label SHALL match the release tag

The system SHALL ensure the Dokka-generated documentation site at
`https://kikin81.github.io/atproto-kotlin/api/` renders a
`library-version` label that matches the Git tag cut by semantic-release
in the same release workflow run. This requirement applies to every
release that bumps the version — `feat:`, `fix:`, and any commit
carrying a `BREAKING CHANGE:` footer.

To satisfy this, Dokka generation in the release workflow SHALL run
**after** semantic-release has mutated `gradle.properties` to the new
version, so that the `Project.version` Gradle reads at Dokka-generate
time is the post-bump value.

#### Scenario: Release cuts version and docs match

- **WHEN** `main` receives a push containing a `feat:` commit that
  semantic-release classifies as a minor bump from 5.0.0 to 5.1.0
- **AND** the `release.yaml` workflow runs to completion
- **THEN** the Git tag `v5.1.0` is created
- **AND** Maven Central receives `io.github.kikin81.atproto:{runtime,models,oauth}:5.1.0`
- **AND** `curl -s https://kikin81.github.io/atproto-kotlin/api/ | grep library-version`
  renders `5.1.0` — not `5.0.0` or any other prior version

#### Scenario: Non-release push regenerates docs without version drift

- **WHEN** `main` receives a push containing only `chore:` or `docs:`
  commits since the last release
- **AND** the `release.yaml` workflow runs the Dokka generation step
  (which runs on every push to `main`)
- **THEN** semantic-release is a no-op, no new tag is created
- **AND** the docs regeneration reads the existing (last-released)
  version from `gradle.properties`
- **AND** the docs site's `library-version` label continues to show
  that last-released version — not stale, not prematurely bumped

#### Scenario: Failed release leaves docs intact

- **WHEN** the `release.yaml` workflow fails during semantic-release
  (e.g., network error reaching Maven Central's staging endpoint)
- **THEN** no new Git tag is created
- **AND** `gradle.properties` is not committed with a bump
- **AND** the subsequent Dokka generation step does not run (the job
  fails)
- **AND** the docs site retains its last-good state (the version label
  for the last successful release)
