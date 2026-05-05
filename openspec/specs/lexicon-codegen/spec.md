## ADDED Requirements

### Requirement: Codegen SHALL emit @Serializable data classes for every object and record definition

The system SHALL emit a Kotlin `@Serializable` data class for every `ObjectDef` and `RecordDef` reachable in the resolved IR. Required fields SHALL be emitted as non-nullable constructor parameters without defaults. The emitted class SHALL live in a Kotlin package derived from the NSID domain segments, with the `.defs` suffix dropped when present (so `app.bsky.feed.defs` and `app.bsky.feed.post` both emit into the same Kotlin package).

#### Scenario: Emitting a record with all required fields

- **WHEN** the generator processes `com.atproto.repo.strongRef` (required: `uri`, `cid`)
- **THEN** it emits `data class StrongRef(val uri: AtUri, val cid: Cid)` in package `io.github.kikin81.atproto.com.atproto.repo`
- **AND** the class is annotated `@Serializable`

### Requirement: Codegen SHALL apply context-sensitive optionality

The system SHALL emit optional fields as `AtField<T> = AtField.Missing` with `@EncodeDefault(EncodeDefault.Mode.NEVER)` when the owning object is reached from a mutation context (`RecordDef.record`, `ProcedureDef.input`, `QueryDef.parameters`, `SubscriptionDef.parameters`, or any object transitively reachable from one of those). The system SHALL emit optional fields as plain `T? = null` when the owning object is reached only from a read context (`QueryDef.output`, `ProcedureDef.output`, or `#view`-style objects). Context SHALL propagate through ref traversal.

#### Scenario: Optional field in a mutation input

- **WHEN** an `ObjectDef` tagged mutation-reachable contains property `displayName` of type `StringType` not listed in `required`
- **THEN** the generator emits `@EncodeDefault(EncodeDefault.Mode.NEVER) val displayName: AtField<String> = AtField.Missing`

#### Scenario: Optional field in a read output

- **WHEN** an `ObjectDef` tagged read-only contains property `description` of type `StringType` not listed in `required`
- **THEN** the generator emits `val description: String? = null`

### Requirement: Shared objects SHALL split only when the split produces a materially different class

The system SHALL emit a single Kotlin class for any object reached from both mutation and read contexts when `properties.keys == required` (no optional fields). The system SHALL emit two distinct Kotlin classes (the read version unsuffixed, the mutation version with `Input` suffix) only when the object has at least one optional field and is reached from both contexts.

#### Scenario: Shared object with no optional fields emits once

- **WHEN** `com.atproto.repo.strongRef` is reached from both `app.bsky.feed.like` (mutation) and `app.bsky.feed.defs#postView` (read)
- **AND** `strongRef` has no optional fields
- **THEN** the generator emits exactly one `StrongRef` class, used from both sites

#### Scenario: Shared object with optional fields emits twice

- **WHEN** a hypothetical shared object has at least one optional field and is reached from both mutation and read contexts
- **THEN** the generator emits a `Foo` class (using `T?`) for read consumers and a `FooInput` class (using `AtField<T>`) for mutation consumers

### Requirement: Unions SHALL emit as sealed interfaces with Unknown fallback and $type polymorphic serializer

The system SHALL emit every `UnionType` as a sealed interface whose members are the resolved ref targets. Every emitted union SHALL include a generated `Unknown(type: String, raw: JsonObject)` variant that preserves the full original JSON object verbatim for lossless round-trip. The system SHALL emit a `JsonContentPolymorphicSerializer` subclass that dispatches on the `$type` discriminator. The discriminator matching SHALL normalize `nsid` and `nsid#main` as equivalent.

#### Scenario: Serializing an unknown variant round-trips the raw JSON

- **WHEN** a union field receives a JSON object whose `$type` is not in the known refs list (e.g. `{"$type":"app.bsky.embed.futureThing","field":"value"}`)
- **THEN** the generated serializer deserializes it to `Unknown("app.bsky.embed.futureThing", JsonObject(...))`
- **AND** re-serializing that value produces an object with the same `$type` and the same fields as the input

#### Scenario: Discriminator normalizes nsid and nsid#main

- **WHEN** a union arm references `"com.example.foo"` and the wire value has `"$type": "com.example.foo#main"`
- **THEN** the polymorphic serializer routes to the same generated variant as if `"$type": "com.example.foo"` had been received

### Requirement: Codegen SHALL apply the naming matrix

The system SHALL derive Kotlin class names from `DefKey` according to a fixed matrix: queries/procedures at `defs.main` emit `<NsidTerminal>Request` and `<NsidTerminal>Response` classes; records at `defs.main` emit `<NsidTerminal>` as PascalCase; secondary definitions in a `.defs` file emit their fragment name in PascalCase; secondary definitions inside a primary def file emit `<PrimaryName><FragmentName>` (flat, no nesting). All emissions SHALL be top-level classes in their Kotlin package; nested class emission is NOT permitted.

#### Scenario: Secondary definition inside a record file

- **WHEN** the generator processes `app.bsky.feed.post#replyRef` (a non-main definition in the `post.json` record file)
- **THEN** it emits a top-level `PostReplyRef` class in package `io.github.kikin81.atproto.app.bsky.feed`
- **AND** it does NOT emit it as a nested class inside `Post`

#### Scenario: Secondary definition inside a .defs file

- **WHEN** the generator processes `app.bsky.actor.defs#profileView`
- **THEN** it emits a top-level `ProfileView` class in package `io.github.kikin81.atproto.app.bsky.actor` (the `.defs` suffix is dropped from the package)

### Requirement: Verification pass SHALL halt codegen on invariant violation

The system SHALL run a verification pass between IR resolution and KotlinPoet emission that enforces four invariants and halts with a diagnostic on any violation:

- **INV-1**: No `DefKey` maps to more than one target `FqName`.
- **INV-2**: No `FqName` is claimed by more than one `DefKey`.
- **INV-3**: No two union arms within the same owning union have the same Kotlin member name.
- **INV-4**: No two union arms within the same owning union have the same `$type` discriminator string.

Manual disambiguation overrides SHALL be keyed on the unordered pair of colliding `DefKey`s (not on the resulting `FqName`), so that a second collision landing on the same name triggers a fresh failure rather than being silently absorbed. The verification pass SHALL run twice — once before applying overrides and once after — so that an override-induced collision is itself caught.

#### Scenario: FqName collision halts the build

- **WHEN** two `DefKey`s resolve to the same Kotlin `FqName` and no override covers the pair
- **THEN** the verification pass throws a diagnostic naming both `DefKey`s and the contested `FqName`
- **AND** no Kotlin source files are written

#### Scenario: Override-induced collision is caught on second pass

- **WHEN** an override renames `DefKey A` to a Kotlin name that already belongs to `DefKey B`
- **THEN** the second verification pass detects the collision and halts with a diagnostic

### Requirement: Codegen SHALL emit KDoc from Lexicon descriptions

The system SHALL emit a KDoc comment on every generated class, constructor property, and service method whose corresponding Lexicon node carries a non-null `description` string. The KDoc SHALL reproduce the Lexicon description verbatim (after sanitization). Elements without a `description` SHALL be emitted with no KDoc.

#### Scenario: Class with a Lexicon description

- **WHEN** the generator processes a `RecordDef` whose `description` is `"A declaration of a like."`
- **THEN** the emitted data class has a KDoc block: `/** A declaration of a like. */`

#### Scenario: Property with a Lexicon description

- **WHEN** the generator processes an `ObjectDef` property `subject` whose `FieldType.description` is `"The subject of the like."`
- **THEN** the emitted constructor parameter has a KDoc: `/** The subject of the like. */`

#### Scenario: Service method with a Lexicon description

- **WHEN** the generator processes a `QueryDef` at `app.bsky.feed.getTimeline` whose `description` is `"Get a view of the requesting account's home timeline."`
- **THEN** the emitted `getTimeline` method has a KDoc reproducing that description

#### Scenario: Element without a description

- **WHEN** the generator processes a definition whose `description` is null
- **THEN** the emitted element has no KDoc block

### Requirement: Codegen SHALL sanitize descriptions for KotlinPoet

The system SHALL sanitize Lexicon description strings before passing them to KotlinPoet's `.addKdoc()` by escaping `%` as `%%` (KotlinPoet format specifier) and `$` as `${'$'}` (Kotlin string interpolation). The sanitizer SHALL be a reusable extension function that all emitters share.

#### Scenario: Description containing a percent sign

- **WHEN** a Lexicon description is `"Rate limited to 100% of quota"`
- **THEN** the string passed to `.addKdoc()` is `"Rate limited to 100%% of quota"`
- **AND** the compiled Kotlin KDoc reads `Rate limited to 100% of quota`

#### Scenario: Description containing a dollar sign

- **WHEN** a Lexicon description is `"Uses $type for dispatch"`
- **THEN** the string passed to `.addKdoc()` is `"Uses ${'$'}type for dispatch"`
- **AND** the compiled Kotlin KDoc reads `Uses $type for dispatch`

### Requirement: Codegen SHALL emit @Deprecated for deprecated Lexicon nodes

The system SHALL emit a Kotlin `@Deprecated` annotation on every generated class, property, or service method whose corresponding Lexicon node is marked deprecated. When the Lexicon provides a deprecation reason string, it SHALL be used as the annotation's `message` parameter. When no reason is provided, the annotation SHALL use a default message of `"Deprecated in the AT Protocol Lexicon"`.

#### Scenario: Deprecated definition with a reason

- **WHEN** the generator processes a `QueryDef` with `deprecated = true` and `deprecatedMessage = "Use resolveHandle instead"`
- **THEN** the emitted service method carries `@Deprecated("Use resolveHandle instead")`
- **AND** the emitted Request/Response classes carry the same annotation

#### Scenario: Deprecated definition without a reason

- **WHEN** the generator processes a definition with `deprecated = true` and `deprecatedMessage = null`
- **THEN** the emitted element carries `@Deprecated("Deprecated in the AT Protocol Lexicon")`

#### Scenario: Non-deprecated definition

- **WHEN** the generator processes a definition with `deprecated = false`
- **THEN** the emitted element has no `@Deprecated` annotation

### Requirement: Codegen SHALL emit a raw-bytes parameter list for procedures whose input is non-JSON

The system SHALL detect non-JSON procedure inputs using the rule: `ProcedureDef.input` is present, `ProcedureDef.input.schema` is absent, AND `ProcedureDef.input.encoding` is not `application/json`. When this rule matches, the generator SHALL emit a service method that takes `(input: ByteArray, inputContentType: io.ktor.http.ContentType)` and routes through the raw-bytes `XrpcClient.procedure(...)` overload, instead of emitting a no-arg method or a JSON-serialized request type. The detection SHALL run as part of `EmissionPlan` so that `ServiceGenerator` consumes a precomputed `InputShape` (one of `NoInput`, `JsonInput`, `RawBytesInput`).

#### Scenario: uploadBlob emits a raw-bytes signature

- **WHEN** the generator processes `com.atproto.repo.uploadBlob` (which has `input.encoding: "*/*"` and no `input.schema`)
- **THEN** the emitted `RepoService.uploadBlob(...)` declaration is:

  ```
  public suspend fun uploadBlob(
      input: ByteArray,
      inputContentType: ContentType,
  ): UploadBlobResponse
  ```

- **AND** the method body delegates to the raw-bytes `client.procedure(...)` overload with `input = input` and `inputContentType = inputContentType`
- **AND** no `UploadBlobRequest` data class is emitted for `com.atproto.repo.uploadBlob`

#### Scenario: A pinned encoding emits a default Content-Type

- **GIVEN** a procedure lexicon whose `input.encoding` is the single concrete value `image/png` and whose `input.schema` is absent
- **WHEN** the generator processes that procedure
- **THEN** the emitted method declares `inputContentType: ContentType = ContentType.Image.PNG` so that callers may omit the parameter
- **AND** callers SHALL still be able to override the default by passing an explicit `inputContentType`

#### Scenario: `*/*` encoding emits no default

- **WHEN** the generator processes a procedure whose `input.encoding` is `*/*` and whose `input.schema` is absent
- **THEN** the emitted method declares `inputContentType: ContentType` with no default value
- **AND** callers SHALL be required to supply the parameter explicitly

#### Scenario: JSON-input procedures are unaffected

- **WHEN** the generator processes a procedure whose `input.schema` is present (e.g. `com.atproto.server.createSession`)
- **THEN** the emission shape is unchanged — a typed request data class is generated and the service method signature is the same as today

#### Scenario: No-input procedures are unaffected

- **WHEN** the generator processes a procedure with no `input` at all
- **THEN** the emission shape is unchanged — a no-arg service method is generated as today

### Requirement: Codegen SHALL halt when a procedure combines raw-bytes input with URL parameters

The system SHALL NOT emit a service method for any procedure whose `ProcedureDef.input` declares a non-JSON `encoding` (i.e. classifies as raw bytes per the previous requirement) AND whose `ProcedureDef.parameters` is non-null. Instead, the generator SHALL halt codegen by raising a `VerificationFailure` whose message names the offending lexicon ID and instructs the user to file an upstream issue with the lexicon attached. This invariant exists to prevent a silent param-drop bug analogous to the missing-input bug this change fixes — designing an API surface for "raw bytes plus URL params" without a real lexicon to design against would necessarily be guesswork. The classification is implemented as a dedicated `ProcedureInputShape.UnsupportedRawBytesWithParams(encoding, lexiconId)` variant so the codegen's exhaustive `when` is forced to handle the case explicitly.

#### Scenario: Raw-bytes plus params lexicon halts codegen

- **GIVEN** a synthetic `ProcedureDef` whose `input.encoding` is `*/*`, whose `input.schema` is absent, and whose `parameters` is non-null
- **WHEN** `EmissionPlan.classifyProcedureInput` is invoked with the lexicon ID `com.example.uploadWithParams`
- **THEN** the result is a `ProcedureInputShape.UnsupportedRawBytesWithParams` carrying `encoding = "*/*"` and `lexiconId = "com.example.uploadWithParams"`
- **AND** the subsequent `ServiceGenerator` pass throws `VerificationFailure` whose message contains the lexicon ID

#### Scenario: No corpus lexicon currently triggers this

- **WHEN** the full installed AT Protocol lexicon corpus (under `generator/lexicons/`) is generated end-to-end
- **THEN** no `VerificationFailure` is raised — confirming the invariant guards a hypothetical case at the time this requirement is introduced, not an actual one
