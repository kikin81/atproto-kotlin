## ADDED Requirements

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
