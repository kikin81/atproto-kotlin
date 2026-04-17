# consumer-skills Specification

## Purpose
TBD - created by archiving change llm-skills-structure. Update Purpose after archive.
## Requirements
### Requirement: Repository SHALL host a skills directory for LLM consumers

The repository SHALL host a top-level `skills/` directory containing
task-oriented SKILL.md files that guide LLM agents through common
consumer-side tasks (setup, OAuth, reading data, writing records,
type reference). Each skill SHALL be a self-contained directory
with at minimum a `SKILL.md` file and optionally a `references/`
subdirectory for supporting material.

The structure SHALL follow the conventions established by
[`github.com/android/skills`](https://github.com/android/skills)
so that agents familiar with that layout can consume these skills
without reinterpretation.

#### Scenario: Fresh clone exposes the skills directory

- **WHEN** a contributor clones the repository at `main`
- **THEN** `skills/` exists at the repo root
- **AND** it contains a `README.md` and at least one skill subdirectory
- **AND** each skill subdirectory contains a `SKILL.md` at its root

### Requirement: Each SKILL.md SHALL declare discovery metadata in YAML frontmatter

Every `SKILL.md` file SHALL begin with a YAML frontmatter block
containing at minimum `name`, `description`, and a `metadata` map with
`library-version` and `keywords`. The `description` SHALL be phrased
so that an agent can pattern-match user intent against it — the
convention is to begin with "Use this skill when…".

#### Scenario: Skill frontmatter is parseable and discoverable

- **GIVEN** a SKILL.md file at `skills/atproto-oauth/SKILL.md`
- **WHEN** the file is parsed as YAML frontmatter plus markdown body
- **THEN** the frontmatter contains `name: atproto-oauth`
- **AND** `description` begins with "Use this skill when"
- **AND** `metadata.library-version` is set to the current
  `gradle.properties` version
- **AND** `metadata.keywords` is a list of at least three terms

### Requirement: Initial skill set SHALL cover consumer-side task surface

The initial skills directory SHALL include at minimum the following
five skills, each scoped to a coherent task:

1. `atproto-setup` — Gradle dependencies and module overview
2. `atproto-oauth` — End-to-end OAuth flow for Android consumers
3. `atproto-read` — Query calls, cursor pagination, open-union dispatch
4. `atproto-write-records` — `createRecord` / `deleteRecord` via
   `RepoService` with `encodeRecord()`
5. `atproto-types-reference` — `AtField<T>` semantics, runtime value
   classes, cross-cutting pitfalls

#### Scenario: All five initial skills are present

- **WHEN** a user lists `skills/` at `main`
- **THEN** it contains subdirectories named `atproto-setup`,
  `atproto-oauth`, `atproto-read`, `atproto-write-records`, and
  `atproto-types-reference`
- **AND** each of those subdirectories contains a valid `SKILL.md`

### Requirement: skills/README.md SHALL provide a consumer installation snippet

The `skills/README.md` SHALL include a ready-to-paste block for the
consumer repository's `CLAUDE.md` that lists each SKILL.md's
raw GitHub URL. The snippet SHALL direct agents to fetch only the
skill that matches the current task.

#### Scenario: Consumer app pastes the snippet and fetches a skill

- **GIVEN** a downstream Android project whose `CLAUDE.md` contains
  the installation snippet from `skills/README.md`
- **WHEN** the author asks Claude to "set up OAuth login against
  Bluesky"
- **THEN** Claude identifies `atproto-oauth` as the matching skill
- **AND** fetches
  `https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/skills/atproto-oauth/SKILL.md`
  rather than loading all five skills

### Requirement: Skills SHALL link to referenced library artifacts, not restate their source

Skills SHALL reference files that live elsewhere in the repository
(e.g. the sample app's `FeedViewModel.kt`) by linking to the raw
GitHub URL or in-repo path, and SHALL NOT duplicate those file
contents inline. This keeps skills small and prevents drift when
the underlying source file changes.

#### Scenario: Skill references sample code by URL

- **GIVEN** `atproto-read/SKILL.md` discusses pagination
- **WHEN** it needs to point at a working `*PageFlow()` integration
  example
- **THEN** it links to
  `https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/samples/android/src/main/kotlin/io/github/kikin81/atproto/samples/bluesky/ui/FeedViewModel.kt`
- **AND** does not inline the full file contents
