## Why

`com.atproto.repo.uploadBlob` and any other lexicon procedure whose `input.encoding` is not `application/json` (e.g. `*/*`, `image/jpeg`, `video/mp4`) cannot be called through the SDK today. The generator drops non-JSON inputs entirely — `RepoService.uploadBlob()` is emitted with no parameters — and `XrpcClient.procedure(...)` exposes only a JSON-body overload and a no-body overload. No path through the SDK sends raw bytes with a configurable `Content-Type` while preserving auth, DPoP, 401 retry, and error mapping. Downstream consumers (reported by `nubecita`, an Android Bluesky client building a post composer with image attachments) must drop to raw Ktor and reimplement every cross-cutting concern, which is heavyweight and fragile. This blocks every image, video, avatar, and banner upload flow.

Tracks bd `kikinlex-czb` / GitHub issue [#73](https://github.com/kikin81/atproto-kotlin/issues/73).

## What Changes

- **Runtime:** Add a third `XrpcClient.procedure(...)` overload that accepts `input: ByteArray` plus `inputContentType: ContentType` and posts the bytes verbatim with the supplied content type. It SHALL exercise the same `applyAuth(provider)`, DPoP header refresh, 401 `onUnauthorized` retry, and `XrpcErrorMapper` path as the JSON overload — only the body shape differs.
- **Generator:** Add a detection rule for non-JSON procedure inputs. When a `ProcedureDef.input` is present, has no `schema`, and declares an `encoding` other than `application/json`, the generator SHALL emit a service method that takes `(input: ByteArray, inputContentType: ContentType)` and routes to the new runtime overload. When `encoding` pins a single content type (e.g. `image/png`), the generator SHALL emit a default value for `inputContentType`. When `encoding` is `*/*`, the parameter SHALL have no default — the caller must specify.
- **Generated output:** `RepoService.uploadBlob(input: ByteArray, inputContentType: ContentType): UploadBlobResponse` becomes the regenerated signature; the same rule will pick up any future lexicons with non-JSON inputs.
- **Goldens:** Update the golden file fixture for `uploadBlob` (and any other non-JSON-input lexicon currently in the corpus) via `GOLDEN_UPDATE=1`.

No breaking changes — the runtime overload is additive (new function), the generator change only affects procedures that today produce a no-arg method that cannot work anyway.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `atproto-runtime`: adds a new requirement for the `XrpcClient` to support raw-bytes procedure bodies with a caller-supplied `Content-Type`, preserving the existing auth/DPoP/retry/error-mapping invariants.
- `lexicon-codegen`: adds a new requirement for service-method emission when a procedure's `input.encoding` is not `application/json`.

## Impact

- `runtime/src/commonMain/kotlin/io/github/kikin81/atproto/runtime/XrpcClient.kt` — new overload (and an internal helper shared with the JSON overload to keep auth/retry/error logic in one place).
- `runtime/src/commonTest/...` — MockEngine tests proving the new overload sends raw bytes with the correct `Content-Type`, applies auth, and exercises the 401-retry + error-mapping path.
- Generator emitters (`XrpcGenerator` / `ServiceGenerator`) and `EmissionPlan` — detection rule for `input.encoding != "application/json"` and the corresponding KotlinPoet emission shape.
- `generator/src/test/resources/golden/kotlin/...uploadBlob...` — updated golden output (regenerate via `GOLDEN_UPDATE=1 ./gradlew :generator:test --tests '*GoldenFileTest*'`).
- `models/build/generated/source/lexicon/commonMain/kotlin/io/github/kikin81/atproto/com/atproto/repo/RepoService.kt` — regenerated `uploadBlob(...)` signature.
- No public API removals; no deprecations; no changes to existing JSON-body or no-body procedure call sites.
- Out of scope (tracked separately): extending `:samples:android` ComposeViewModel with an image-attachment flow exercising `uploadBlob` end-to-end. Worthwhile follow-up but kept out of this change to keep the surface focused.
