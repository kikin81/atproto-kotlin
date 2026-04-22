## ADDED Requirements

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
