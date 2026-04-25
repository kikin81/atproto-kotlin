# artifact-publishing Specification

## Purpose
TBD - created by archiving change rename-maven-artifacts. Update Purpose after archive.
## Requirements
### Requirement: Published artifactIds SHALL NOT duplicate tokens from the groupId

The system SHALL publish every Maven Central artifact under an
`artifactId` that contains no token already present in its `groupId`.
Specifically, because the group is `io.github.kikin81.atproto`, no
artifact SHALL use `atproto`, `at-protocol`, or any hyphenated variant
as a prefix or suffix of its artifactId.

#### Scenario: Consumer resolves the OAuth module

- **WHEN** a consumer declares `implementation("io.github.kikin81.atproto:oauth:5.0.0")` in a Gradle build
- **THEN** the artifact resolves from Maven Central and provides the OAuth classes

#### Scenario: No stuttering coordinates exist at 5.x

- **WHEN** a consumer searches Maven Central for artifacts under group `io.github.kikin81.atproto` at version `5.0.0` or later
- **THEN** the returned artifactIds are `runtime`, `models`, and `oauth` — not `at-protocol-runtime`, `at-protocol-models`, or `at-protocol-oauth`

### Requirement: Gradle module names SHALL match published artifactIds

The system SHALL name every publishable Gradle module identically to
its published `artifactId`. A module published as `oauth` SHALL be
located at Gradle path `:oauth` with directory `/oauth`. This keeps
the internal and external naming in lockstep and lets the
vanniktech maven-publish plugin derive `artifactId` from
`project.name` without overrides.

#### Scenario: Module path matches artifactId

- **WHEN** the build publishes `io.github.kikin81.atproto:oauth:<version>`
- **THEN** the corresponding Gradle project is `:oauth` and the source directory is `/oauth/`

#### Scenario: Non-publishable modules follow the same naming rule

- **WHEN** a contributor looks up the code generator module
- **THEN** it is located at `:generator` (not `:at-protocol-generator`), for consistency with the publishable modules even though it is not published to Maven Central

### Requirement: The protocol name SHALL be spelled `atproto` across all code and build surfaces

The system SHALL use the single-word spelling `atproto` in all Gradle
module names, Maven coordinates, package names, and internal
identifiers. The hyphenated form `at-protocol` SHALL NOT appear in
any of these surfaces. The canonical name "AT Protocol" (with the
space) MAY be used in human-readable prose: KDoc comments, README
text, POM descriptions, and user-facing documentation.

#### Scenario: Repo-wide grep for legacy spelling

- **WHEN** `grep -rn 'at-protocol-' --include='*.kts' --include='*.yaml' --include='*.yml' --include='*.toml' .` is run at the repo root on a post-5.0.0 working tree
- **THEN** the command returns zero matches

#### Scenario: Protocol name in prose

- **WHEN** a reader opens `README.md` or a published POM description
- **THEN** the protocol is referred to as "AT Protocol" (human-readable), while code-level identifiers use `atproto`

### Requirement: Major version bumps SHALL accompany coordinate-breaking changes

The system SHALL cut a new major version whenever a published
artifact's groupId or artifactId changes in a way that prevents
existing consumers from resolving a newer version under their pinned
coordinates. The breaking commit SHALL include a conventional-commit
`!` marker and a `BREAKING CHANGE:` footer so the release tooling
computes a major bump.

#### Scenario: Rename triggers a major release

- **WHEN** the module rename lands on `main` under a commit of the form `feat!: ... \n\nBREAKING CHANGE: ...`
- **THEN** semantic-release computes the next version as a major bump (4.x -> 5.0.0) and publishes the renamed artifacts under that version

### Requirement: Migration guidance SHALL accompany every coordinate break

The system SHALL document coordinate-breaking changes with a
consumer-facing migration note that lists the old -> new coordinate
mapping and explicitly states whether any consumer code changes are
required. The guidance SHALL live in a location reachable from the
project README.

#### Scenario: Consumer arrives from 4.x

- **WHEN** a consumer reads the README of the 5.x SDK after seeing a resolution failure on their 4.x coordinates
- **THEN** they find a migration section (or `MIGRATION.md`) containing the full old -> new coordinate table and a clear statement that no API code changes are required
