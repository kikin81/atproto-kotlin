## ADDED Requirements

### Requirement: Toolchain SHALL source lexicons from @atproto/lex via npm

The system SHALL obtain the authoritative lexicon JSON files by installing `@atproto/lex` from npm within the `:generator` module. The tool SHALL resolve and download the full transitive lexicon tree (including `actor`, `embed`, `label`, `richtext`, and all other namespace groups) into a local `lexicons/` directory under the generator module. Lexicon version selection SHALL be tracked in `generator/package.json` so that updates are a `npm update` away and versions are reproducible via `package-lock.json`.

#### Scenario: Fresh install populates lexicons directory

- **WHEN** a clean checkout runs the generator's `npm install` step
- **THEN** `generator/lexicons/` contains the full set of `app.bsky.*`, `com.atproto.*`, `chat.bsky.*`, and `tools.ozone.*` JSON files as shipped by the installed `@atproto/lex` version

### Requirement: Generator SHALL be a JVM-only Gradle module invoked as a build task

The system SHALL package the code generator as a JVM-only Gradle module `:generator` (KotlinPoet is JVM-only by construction). The generator SHALL be invoked by a Gradle task whose inputs are the `lexicons/` directory and whose outputs are Kotlin source files written into the `:models` module's `build/generated/` source set. The task SHALL be up-to-date when the lexicon inputs have not changed.

#### Scenario: Incremental up-to-date check

- **WHEN** the generator task runs, then runs again with no lexicon changes
- **THEN** Gradle reports the task as `UP-TO-DATE` on the second run and no source files are rewritten

### Requirement: Models and runtime SHALL be published as separate Maven Central artifacts with independent versioning

The system SHALL publish `:runtime` and `:models` as two distinct Maven Central artifacts. `:runtime` SHALL be versioned using standard semantic release (e.g. `1.2.3`) independent of upstream lexicon releases. `:models` SHALL be versioned with a version string hard-mapped to the upstream `@atproto/lex` release it was generated against, so that a consumer reading a `build.gradle.kts` can identify the exact lexicon snapshot the models correspond to. `:models` SHALL declare a dependency on `:runtime`.

#### Scenario: Consumer upgrades runtime without rebuilding models

- **GIVEN** a downstream project depending on `runtime:1.2.0` and `models:0.13.0`
- **WHEN** the project upgrades to `runtime:1.2.1` (a runtime-only bug fix)
- **THEN** the project compiles and runs without changes to its `models` dependency

#### Scenario: Consumer upgrades models independently

- **GIVEN** the same project
- **WHEN** a new upstream lexicon release arrives and the project upgrades to `models:0.14.0`
- **THEN** the project compiles against the same runtime and only the generated model surface changes

### Requirement: V1 scope SHALL be JSON/XRPC only

The system SHALL support JSON serialization for all `query`, `procedure`, and `record` types in V1. The system SHALL NOT support CBOR/DAG-CBOR encoding, firehose / `subscribeRepos`, CAR file parsing, or WebSocket subscriptions in V1. The IR SHALL be designed so that a CBOR emitter can be added later without breaking changes to the runtime or models public API.

#### Scenario: V1 generator ignores subscription definitions

- **WHEN** the generator encounters a `SubscriptionDef` in a lexicon
- **THEN** it emits a non-fatal warning naming the subscription and skips code generation for it
- **AND** the rest of the generation proceeds successfully
