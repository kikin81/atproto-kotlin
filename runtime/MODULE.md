# Module runtime

Hand-written base module for the AT Protocol Kotlin SDK. Kotlin
Multiplatform (JVM + iOS) library that every generated model and
service depends on.

## What's in here

- **Typed value classes** for every AT Protocol string format: `Did`,
  `Handle`, `AtIdentifier`, `AtUri`, `Cid`, `Datetime`, `Nsid`,
  `RecordKey`, `Language`. Use these instead of raw strings — they're
  zero-overhead and prevent mixing.
- **`AtField<T>`** — three-state optionality (`Missing` / `Null` /
  `Defined`) for mutation payloads where "absent" and "null" are
  distinct wire states (e.g. `putPreferences`).
- **`OpenUnion` infrastructure** — base types and serializers for
  sealed-interface unions with a `$type` discriminator and an
  `Unknown` fallback for forward compatibility.
- **`XrpcClient`** — Ktor-backed client wrapping a caller-supplied
  `HttpClient`. Exposes `query()` for GETs, `procedure()` for POSTs.
  Generated `*Service` classes wrap this; prefer those for lexicon
  endpoints.
- **`AuthProvider`** — pluggable auth interface. `NoAuth` for
  unauthenticated calls; bearer-token and DPoP implementations live
  in consumer modules (e.g. `oauth`).
- **`paginate()` / `paginatePages()`** — generic Flow builders for
  cursor-paginated endpoints. Generated `*Flow()` / `*PageFlow()`
  extensions on service classes call these under the hood.
- **Record encode/decode helpers** — `encodeRecord()` injects `$type`
  for mutation payloads; `decodeRecord<T>()` extracts typed records
  from `JsonObject` values on the wire.

## Typical consumers

- **models** — declares `api(project(":runtime"))`
  so every downstream project transitively picks up the runtime types.
- **oauth** — builds on `AuthProvider` and `XrpcClient`.
- **Application code** — usually never touches `XrpcClient` directly;
  constructs `<Namespace>Service(client)` from `models`.

# Package io.github.kikin81.atproto.runtime

Core runtime primitives: value classes, `AtField`, open-union base types,
`XrpcClient`, auth provider interface, pagination Flow builders, and
record encode/decode helpers. Everything here is hand-written — this
is the stable, non-generated surface the SDK builds on.
