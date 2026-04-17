## Why

Every paginated AT Protocol endpoint returns `cursor: String?` for the
next page. Today, consumers must manually loop: call the service method,
extract the cursor, set it on the next request, check for null to stop.
This is tedious, error-prone, and the same boilerplate for all 18
paginated endpoints.

Kotlin's `Flow` is the natural fit — a `Flow<T>` that lazily fetches
pages as the collector consumes items, handles end-of-feed (null cursor),
and works across JVM and iOS without platform-specific dependencies.

## What Changes

- **Runtime pagination primitive**: A generic `paginate()` function in
  `:at-protocol-runtime` that takes a suspend lambda `(cursor: String?) -> R`
  and lambdas to extract the cursor and items from `R`, returning a
  `Flow<T>` that fetches pages on demand.
- **Generated Flow extensions**: The code generator emits convenience
  extension functions on Service classes for every cursor-paginated query
  (e.g. `FeedService.timelineFlow(request)` → `Flow<FeedViewPost>`).
  These wire up the `paginate()` call with the correct cursor/items
  extraction automatically.
- **Sample app migration**: Update the Android sample to use
  `timelineFlow()` for infinite-scroll feed rendering.

## Capabilities

### New Capabilities

- `pagination`: Flow-based cursor pagination primitive and generated
  convenience extensions.

### Modified Capabilities

- `lexicon-codegen`: Generator emits `*Flow()` extension functions on
  Service classes for paginated queries.
- `android-sample`: Feed screen uses `timelineFlow()` for pagination.

## Impact

- **at-protocol-runtime**: New `Pagination.kt` file with the generic
  `paginate()` function. Adds `kotlinx-coroutines-core` dependency
  (already transitive via Ktor).
- **at-protocol-generator**: `ServiceGenerator` emits additional Flow
  extension functions for paginated queries.
- **at-protocol-models**: Generated services gain `*Flow()` methods.
- **samples/android**: FeedScreen gains infinite scroll via
  `timelineFlow()`.
- **Breaking changes**: None. The existing service methods are unchanged;
  Flow extensions are purely additive.
