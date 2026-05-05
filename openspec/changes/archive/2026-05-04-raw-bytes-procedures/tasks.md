## 1. Runtime: shared private helper

- [x] 1.1 Extract a private `executeProcedure(...)` helper in `XrpcClient` that takes the params, response serializer, error mapper, auth override, and a `HttpRequestBuilder.() -> Unit` body-shaping lambda; move auth, 401 retry, and error-mapping logic into this helper.
- [x] 1.2 Refactor the existing JSON-body `procedure(...)` overload to delegate to the helper, passing a body lambda that calls `setBody(json.encodeToString(inputSerializer, input))` and `contentType(...)`.
- [x] 1.3 Refactor the existing no-body `procedure(...)` overload to delegate to the helper, passing an empty body lambda.
- [x] 1.4 Run `./gradlew :runtime:jvmTest` and confirm all existing tests still pass — the helper extraction must be behavior-preserving.

## 2. Runtime: raw-bytes overload

- [x] 2.1 Add the new `procedure(...)` overload taking `input: ByteArray, inputContentType: ContentType` plus the same `params`/`paramsSerializer`/`responseSerializer`/`errorMapper`/`auth` parameters as the JSON overload; delegate to the shared helper, passing a body lambda that calls `contentType(inputContentType)` and `setBody(input)`.
- [x] 2.2 Add KDoc for the new overload describing when to use it (`*/*` or non-JSON `input.encoding` lexicons), what content types are accepted, and the in-memory tradeoff for large bodies (point at potential future `ByteReadChannel` overload).
- [x] 2.3 Add a MockEngine test that posts raw bytes with `ContentType.Image.PNG` and asserts the recorded request URL, body bytes, and `Content-Type` header (covers atproto-runtime spec scenario "Posting raw bytes with a configured Content-Type").
- [x] 2.4 Add a MockEngine test that asserts an `AuthProvider`'s headers are applied to the raw-bytes request (covers "Auth is applied to raw-bytes procedure requests").
- [x] 2.5 Add a MockEngine test that returns 401 then 200 and asserts the raw-bytes overload retries exactly once with the refreshed auth and re-sends the same body and `Content-Type` (covers "401 retry path runs for raw-bytes procedure requests").
- [x] 2.6 Add a MockEngine test that returns a typed `XrpcError` body and asserts the configured `XrpcErrorMapper` runs (covers "Error mapping runs for raw-bytes procedure requests").
- [x] 2.7 Run `./gradlew :runtime:jvmTest` and confirm new tests pass alongside the existing suite.

## 3. Generator: detection rule and `InputShape`

- [x] 3.1 In `EmissionPlan` (or its closest equivalent), introduce an `InputShape` sum type with `NoInput`, `JsonInput(typeRef)`, and `RawBytesInput(encoding, defaultedContentType?)` cases.
- [x] 3.2 Implement the detection rule in `EmissionPlan`: for each `ProcedureDef`, classify as `RawBytesInput` when `input` is present, `input.schema` is absent, AND `input.encoding != "application/json"`; classify as `JsonInput` when `input.schema` is present; classify as `NoInput` otherwise.
- [x] 3.3 Implement the encoding-to-`ContentType`-default mapping for known Ktor `ContentType` constants (e.g. `image/png` → `ContentType.Image.PNG`, `image/jpeg` → `ContentType.Image.JPEG`, `video/mp4` → `ContentType.Video.MP4`, `application/octet-stream` → `ContentType.Application.OctetStream`); for unknown concrete types, emit `ContentType.parse("<encoding>")` as the default; for `*/*`, emit no default.
- [x] 3.4 Add a generator unit test verifying the detection rule and the encoding-to-default mapping for `*/*`, `image/png`, an unknown concrete type (e.g. `application/x-custom`), and the JSON case.

## 4. Generator: ServiceGenerator emission

- [x] 4.1 Update `ServiceGenerator` to consume `InputShape` and emit the appropriate parameter list — pass-through for `NoInput`/`JsonInput`, raw-bytes shape for `RawBytesInput`.
- [x] 4.2 For `RawBytesInput`, emit the method body as a delegated call to the raw-bytes `XrpcClient.procedure(...)` overload (with `input = input` and `inputContentType = inputContentType`).
- [x] 4.3 Confirm no `UploadBlobRequest` (or any other request data class) is emitted for procedures whose `InputShape` is `RawBytesInput`.

## 5. Goldens

- [x] 5.1 Grep the lexicon corpus (`generator/lexicons/**/*.json`) for `"encoding"` values that are not `"application/json"` and enumerate the expected set of affected lexicons. (Result: real corpus has only `com.atproto.repo.uploadBlob` with `*/*`. `com.atproto.sync.getRepo` uses `application/vnd.ipld.car` but on the **output** side, which is out of scope.)
- [x] 5.2 Run `GOLDEN_UPDATE=1 ./gradlew :generator:test --tests '*GoldenFileTest*'` to regenerate the golden output. (Added synthetic `example.uploadBlob` and `example.uploadAvatar` lexicons to exercise both the wildcard and pinned-encoding paths.)
- [x] 5.3 Diff the regenerated goldens and confirm the change set matches the enumerated lexicons exactly — no unexpected lexicons were affected. (Only `ExampleService.kt` changed; only `UploadBlobResponse.kt` and `UploadAvatarResponse.kt` were added; no Request classes generated for the raw-bytes procedures.)
- [x] 5.4 Run `./gradlew :generator:test` to confirm the regenerated goldens pass.

## 6. End-to-end verification

- [x] 6.1 Run `./gradlew build` — full build, all tests, spotless. (Ran `:runtime:build :generator:build :models:build :oauth:build` — all green. The full `./gradlew build` fails on a pre-existing Android sample lint issue unrelated to this change: `MainActivity must extend android.app.Activity`. Captured as a separate bd issue follow-up.)
- [x] 6.2 Inspect the regenerated `models/build/generated/source/lexicon/commonMain/kotlin/io/github/kikin81/atproto/com/atproto/repo/RepoService.kt` and confirm `uploadBlob(input: ByteArray, inputContentType: ContentType): UploadBlobResponse` is the emitted signature.
- [x] 6.3 Coverage decision: cross-module smoke test (linking `:runtime` tests against the generated `:models`) is unnecessary given (a) the byte-identical golden test for `example.uploadBlob`/`example.uploadAvatar` locks in the generator's emitted call shape and (b) `XrpcClientTest.raw_bytes_procedure_posts_with_content_type` exercises the exact runtime call path that the generated method delegates to. The combination covers what a cross-module smoke test would.
- [x] 6.4 Run `./gradlew spotlessCheck` to confirm formatting is clean.

## 7. Wrap-up

- [x] 7.1 Update bd `kikinlex-czb` with notes on what shipped (helper extraction, raw-bytes overload, detection rule, regenerated goldens).
- [x] 7.2 Open a separate bd issue tracking the proposed `:samples:android` ComposeViewModel image-attach flow follow-up — `kikinlex-e55`.
- [x] 7.3 Open a separate bd issue tracking a future `ByteReadChannel` streaming `procedure(...)` overload — `kikinlex-177`. (Bonus: also opened `kikinlex-rs0` for the pre-existing `:samples:android` MainActivity lint issue surfaced by the full build.)
