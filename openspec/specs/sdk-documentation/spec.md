## Purpose

KDoc documentation for the published SDK artifacts. Replaces the empty
javadoc JARs Maven Central previously received with Dokka-generated
content, and aggregates per-module HTML into a single site hosted on
GitHub Pages whose `library-version` label matches the released tag.
## Requirements
### Requirement: Published artifacts SHALL include Dokka-generated javadoc JARs

The system SHALL replace the empty javadoc JARs currently published to Maven Central with Dokka-generated javadoc JARs containing real KDoc documentation. IDE consumers SHALL see KDoc descriptions on hover/autocomplete for all public classes, properties, and methods in `:runtime`, `:models`, and `:oauth`.

#### Scenario: IDE hover shows KDoc from published artifact

- **WHEN** a consumer adds `io.github.kikin81.atproto:models:<version>` to their project and hovers over `FeedService.getTimeline()` in their IDE
- **THEN** the IDE displays the KDoc description from the Lexicon source

#### Scenario: Javadoc JAR contains real documentation

- **WHEN** `./gradlew :runtime:dokkaGeneratePublicationJavadoc` runs
- **THEN** the output JAR contains HTML javadoc files for all public API elements

### Requirement: System SHALL generate browsable HTML documentation

The system SHALL generate a multi-module HTML documentation site via Dokka that covers `:runtime`, `:models`, and `:oauth`. The HTML output SHALL be suitable for hosting on GitHub Pages.

#### Scenario: Generating HTML docs

- **WHEN** `./gradlew dokkaGeneratePublicationHtml` runs at the root project level
- **THEN** a combined HTML site is produced covering all three publishable modules

### Requirement: Release workflow SHALL publish documentation to GitHub Pages

The system SHALL deploy the generated HTML documentation to GitHub Pages as part of the release workflow. The docs SHALL be available at `https://kikin81.github.io/atproto-kotlin/api/`.

#### Scenario: Release publishes updated docs

- **WHEN** a new version is released via the release workflow
- **THEN** the GitHub Pages site at `/api/` is updated with documentation matching the released version

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
