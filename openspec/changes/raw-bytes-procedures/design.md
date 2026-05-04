## Context

`XrpcClient` exposes two `procedure(...)` overloads today: one that JSON-encodes a typed input via a `KSerializer<I>` and one that posts no body. Both run through the same private path: append params to the query string, `applyAuth(provider)`, attempt the request, on 401 invoke `provider.onUnauthorized(...)` and retry once, then map errors via `XrpcErrorMapper`. The body shape is the only thing that varies — and today the only shape on offer is "JSON-encoded `I`" or "nothing."

The lexicon corpus contains procedures whose `input.encoding` is something other than `application/json`. The most prominent is `com.atproto.repo.uploadBlob` with `input.encoding: "*/*"`, but the same shape recurs for video upload endpoints and any future ozone-style HTML/binary procedures. The lexicon's signal for "this is raw bytes, not JSON" is: `input` is present, but `input.schema` is absent and `input.encoding != "application/json"`.

The generator's current `XrpcGenerator`/`ServiceGenerator` path only knows two emission modes — input-with-schema (emit `input: SomeRequest`) and no-input (emit zero parameters). When `input` lacks a `schema`, it falls into the no-input branch and silently drops the encoding metadata. Result: `RepoService.uploadBlob()` is emitted with no parameters and a no-body call to `client.procedure(...)`, which is unusable.

Stakeholders: SDK consumers building any kind of media upload (`nubecita` is the immediate one); the generator pipeline (`EmissionPlan`, `ServiceGenerator`); the `:runtime` public API surface; the `GoldenFileTest` regression harness.

## Goals / Non-Goals

**Goals:**
- Make `com.atproto.repo.uploadBlob` callable through the SDK end-to-end, with full auth/DPoP/retry/error-mapping coverage.
- Generalize: any lexicon procedure with non-JSON `input.encoding` becomes callable by the same mechanism.
- Preserve every cross-cutting invariant the existing JSON path provides — bytes-flavor procedures must not be a second-class citizen on auth, DPoP, 401 retry, or error mapping.
- Keep the change additive — no breaking changes to existing JSON-body or no-body call sites.

**Non-Goals:**
- Streaming uploads via `ByteReadChannel`. We will design the new overload so that a streaming variant can be added later without breaking callers, but the streaming overload itself is out of scope.
- Multipart bodies. No AT Protocol lexicon currently uses multipart; not designing for it.
- A typed `Blob` builder for `app.bsky.embed.images`/`app.bsky.embed.video`. That belongs to consumer code or a higher-level helper, not `XrpcClient`.
- Sample app extension (image attach flow in `:samples:android`). Worthwhile follow-up but separable; tracked as a non-blocking note on the bd issue.

## Decisions

### Decision 1: `ByteArray` body, not `ByteReadChannel`, for the first overload

**Choice:** Add one new overload that takes `input: ByteArray` and `inputContentType: ContentType`. Defer the `ByteReadChannel` streaming variant.

**Why:** `ByteArray` covers the immediate use case (image and avatar uploads, which are typically a few hundred KB to a few MB and comfortably fit in memory) and matches what `nubecita` consumers will already have after `contentResolver.openInputStream(uri)?.readBytes()`. A streaming variant is genuinely useful for video, but Ktor's `setBody(ByteReadChannel)` requires a content length that we don't always have at the point of call, and the API design for "optional content length, fallback to chunked" deserves its own thought. Shipping the `ByteArray` overload now unblocks the immediate use case; a future `ByteReadChannel` overload is purely additive.

**Alternatives considered:**
- *Single `Any` overload*: would route `ByteArray` and `ByteReadChannel` through the same entry point. Rejected — type-erased dispatch is fragile and the call site loses `inputContentLength` ergonomics.
- *Generic `OutgoingContent` parameter*: lets callers pass any Ktor content type. Rejected for the public API — too low-level for the typical SDK consumer; we can always add it later as an escape hatch.

### Decision 2: Extract a private `executeProcedure(...)` helper to host auth/retry/error logic

**Choice:** Refactor the existing JSON and no-body overloads to call a private suspend function that takes a Ktor `HttpRequestBuilder`-shaping lambda for the body. Both existing overloads pass a lambda that does `setBody(json.encodeToString(...))` or nothing; the new raw-bytes overload passes a lambda that calls `contentType(...)` and `setBody(byteArray)`.

**Why:** Keeping auth/DPoP/401-retry/error-mapping in one private path is the only way to guarantee the new overload exercises the same invariants as the old ones. Three near-duplicate copies of the retry loop is a regression waiting to happen.

**Alternatives considered:**
- *Duplicate the retry/error logic in the new overload*: rejected — divergence risk is high and the existing test suite already covers the JSON path of the helper.
- *Public `procedureRaw(...)` taking `OutgoingContent`*: rejected for the same reason as Decision 1's alternative — too low-level for the default SDK surface.

### Decision 3: Generator emits a default `inputContentType` only when `encoding` pins a single type

**Choice:**
- `input.encoding: "*/*"` ⇒ emit `inputContentType: ContentType` with no default; caller must supply.
- `input.encoding: "image/png"` (or any single concrete type) ⇒ emit `inputContentType: ContentType = ContentType.Image.PNG` (or the matching Ktor constant).

**Why:** `*/*` literally means "the lexicon disclaims any opinion about the byte content" — the caller is the only party that knows what they're sending, so forcing them to supply the type matches the lexicon's intent. For pinned types, defaulting reduces caller friction without losing correctness. The default is a normal Kotlin default parameter, so callers can still override.

**Mapping rule:** lookup the lexicon's encoding string against Ktor's `ContentType` constants. If we recognize a constant (e.g. `image/png` ⇒ `ContentType.Image.PNG`), emit it as the default. If we don't (e.g. some custom MIME type), emit `ContentType.parse("<encoding>")` as the default. Worst case we fall back to no default — log a generator warning and require the caller.

**Alternatives considered:**
- *Always require `inputContentType`*: rejected — annoying for the pinned-type case where the lexicon already tells us the answer.
- *Always default, even for `*/*`*: rejected — there's no sensible default for `*/*`. Picking `ContentType.Application.OctetStream` would silently lie to the server about JPEG image uploads.

### Decision 4: Detection rule lives in `EmissionPlan`, not in `ServiceGenerator`

**Choice:** Compute a per-procedure `InputShape` (one of `NoInput`, `JsonInput(<typeRef>)`, `RawBytesInput(<encoding>, <defaultedContentType?>)`) during `EmissionPlan` and let `ServiceGenerator` consume that. The detection logic — `input present AND input.schema absent AND input.encoding != "application/json"` ⇒ `RawBytesInput` — runs once in `EmissionPlan`.

**Why:** Keeps `ServiceGenerator` purely emission-focused and keeps the policy decision testable in isolation. Aligns with the existing pattern of computing membership/contextual splits up front in `EmissionPlan`.

## Risks / Trade-offs

- **[Risk] Refactoring the existing JSON overload to share a helper could regress the JSON path.** → Mitigation: keep the existing JSON-overload tests untouched as the regression net; the helper extraction is mechanical and the tests will catch any silent behavior change.

- **[Risk] The detection rule could match more lexicons than expected, causing unrelated golden files to shift.** → Mitigation: before running `GOLDEN_UPDATE=1`, grep the lexicon corpus for `"encoding"` values that aren't `application/json` to enumerate the expected set. Verify the regenerated golden delta matches that set exactly.

- **[Risk] A future `ByteReadChannel` overload might want a different parameter ordering than the `ByteArray` one we ship now.** → Mitigation: design the `ByteArray` signature as a strict subset of the eventual streaming signature (no `inputContentLength` parameter — that's the streaming-only addition). Adding the streaming overload later is purely additive.

- **[Risk] Auth providers that mutate request headers based on body content (some DPoP variants compute payload hashes) could behave differently for raw bytes vs JSON.** → Mitigation: the shared helper applies auth before the body lambda runs, so the auth provider sees the same `HttpRequestBuilder` shape it does for JSON requests. No DPoP variant we currently support hashes the body. If one is added later, the body would already be set on the builder before auth applies, same as today.

- **[Trade-off] No streaming = full file in memory.** Acceptable for typical Bluesky use cases (images, profile media). Documented in KDoc as a known limitation for very large uploads, with the streaming overload listed as future work.

## Migration Plan

Additive change, no migration needed. Existing call sites (JSON-body or no-body) compile unchanged; their generated signatures and runtime call paths do not change. Consumers of `RepoService.uploadBlob()` who relied on the broken zero-arg signature were not actually working — there is no production usage to migrate.

If a downstream consumer was working around this gap by reaching below the SDK to raw Ktor (as `nubecita` does today), they can now switch to `client.procedure(input = bytes, inputContentType = ContentType.Image.JPEG, ...)`. We should call this out in the next release notes.

## Open Questions

- **Should the `inputContentType` parameter be named `contentType` instead?** "inputContentType" mirrors the lexicon's `input.encoding` framing and avoids collision with Ktor's own `contentType` extension function. Leaning toward `inputContentType` for clarity but open to `contentType` if the verbose name reads as awkward in real call sites.
- **Should we surface a generator warning when a lexicon declares an encoding that doesn't map to a known `ContentType` constant?** Logging is cheap and would catch typos in upstream lexicons. Plan to add a one-line `println` in the generator's verbose mode but not promote it to an INV-level invariant.
