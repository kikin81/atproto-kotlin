# Chat / Service-Routed Namespace Proxy Header Support

**Date:** 2026-05-13
**Status:** Implemented — see PR [#88](https://github.com/kikin81/atproto-kotlin/pull/88) (cuts v6.0.0)
**Tracks:** [GitHub issue #86](https://github.com/kikin81/atproto-kotlin/issues/86)

## Problem

Calls to `chat.bsky.convo.*` (and other namespaces hosted on dedicated AT Protocol
appviews) fail with HTTP 403 `ScopeMissingError` because the request reaches the
PDS directly instead of being routed to the chat appview at
`did:web:api.bsky.chat`. AT Protocol uses an `atproto-proxy` HTTP header to tell
the PDS which downstream service should handle the request. The current
`XrpcClient` has no mechanism to attach this header, and the generated
`ConvoService` (or future `NotificationService` for the appview-routed
notifications namespace) has no concept of service routing.

Reproducer (issue #86):

```kotlin
val client = xrpcClientProvider.authenticated()
ConvoService(client).listConvos(ListConvosRequest())
// → ScopeMissingError: Missing required scope
//   "rpc:chat.bsky.convo.listConvos?aud=did:web:api.bsky.app" (HTTP 403)
```

The `aud=did:web:api.bsky.app` confirms the request hit the PDS rather than the
chat appview.

## Goals

- `chat.bsky.convo.*` calls succeed against authenticated sessions with no extra
  effort from the consumer (`ConvoService(client).listConvos()` just works).
- Solution generalizes to other service-routed namespaces (notifications and
  any future appview).
- Sandbox / self-hosted / test deployments can override the proxy DID.
- Source-compatible for Kotlin callers (all new parameters default).
  Binary-incompatible — JVM signatures of `XrpcClient.query`/`procedure`
  and the generated `ConvoService` constructor change, so consumers must
  recompile against v6.0.0. Tradeoff documented in
  `docs/breaking-changes/v6.md`. Preserving ABI via parallel overloads
  was considered and rejected as it would double the public surface
  without behavioral benefit.

## Non-Goals

- Auto-discovering proxy DIDs from lexicon JSON. The lexicon spec does not carry
  appview routing metadata; the proxy mapping lives in the generator as a small
  hardcoded table.
- Per-call proxy override on generated services. Service-level granularity is
  sufficient for every namespace AT Protocol currently routes.
- Generic header-injection middleware on `XrpcClient`. The `proxy` parameter
  is intentionally typed and named for the AT Protocol concept it represents.

## Approaches Considered

**A. Per-call header override on `XrpcClient`** — every consumer must remember
to pass `mapOf("atproto-proxy" to "...")` on every chat call. Forgotten →
silent 403. Defeats the point of typed generated services.

**B. Service-level constructor param only (no runtime change)** — pushes the
"what is the right proxy DID" knowledge onto consumer code. Fragile and easy
to get wrong.

**C. Generator-driven, with a runtime primitive (chosen)** — runtime gains a
typed `proxy` parameter on `query`/`procedure`; the generator threads a
default proxy DID into services for known service-routed namespaces. Combines
power-user flexibility (runtime layer) with zero-friction ergonomics
(generated layer).

## Design

### Runtime layer — `XrpcClient`

Add `proxy: String? = null` to all `query` and `procedure` overloads. When
non-null, attach `atproto-proxy: <value>` to the outgoing request. Default
`null` preserves existing behavior.

```kotlin
public suspend fun <P, R> query(
    nsid: String,
    params: P,
    paramsSerializer: KSerializer<P>,
    responseSerializer: KSerializer<R>,
    errorMapper: XrpcErrorMapper = DefaultXrpcErrorMapper,
    auth: AuthProvider? = null,
    proxy: String? = null,           // <-- new
): R
```

Implementation: in the request-builder block, after `applyAuth(provider)`, add:

```kotlin
proxy?.let { header("atproto-proxy", it) }
```

The same change applies symmetrically to:

- `procedure(... input, inputSerializer, ...)` — JSON-body procedure
- `procedure(... )` — no-input procedure overload
- `procedure(... input: ByteArray, inputContentType, ...)` — raw-bytes procedure

The 401 / DPoP-nonce retry path inherits the `proxy` value (the retry uses
the same request shape).

### Generator layer — `ServiceGenerator` + new `ProxyMapping`

**New file** `generator/src/main/kotlin/io/github/kikin81/atproto/generator/emit/ProxyMapping.kt`:

```kotlin
package io.github.kikin81.atproto.generator.emit

internal object ProxyMapping {
    private val rules: List<Pair<String, String>> = listOf(
        "chat.bsky." to "did:web:api.bsky.chat#bsky_chat",
        // Future: notifications appview, video upload, etc.
    )

    fun proxyFor(nsid: String): String? =
        rules.firstOrNull { (prefix, _) -> nsid.startsWith(prefix) }?.second
}
```

**Modified** `ServiceGenerator.buildServiceClass`:

1. Compute `proxyDid = defKeys.map { ProxyMapping.proxyFor(it.nsid.raw) }.distinct()`.
2. If the set contains more than one non-null value → `VerificationFailure`
   (a single emitted package mixed proxied + non-proxied or two different
   proxies — unsupported and would indicate the rules table was added wrong).
3. If the single value is non-null:
   - Add a constructor parameter `proxy: String? = "<the DID>"` after
     `client`.
   - Store it as `private val proxy = proxy`.
   - Thread `proxy = proxy` into every `client.query(...)` /
     `client.procedure(...)` call emitted by `buildCall` and `buildRawBytesCall`.
4. If the value is null (regular non-proxied namespace), emit unchanged code.

Generated output for `ConvoService`:

```kotlin
public class ConvoService(
    client: XrpcClient,
    proxy: String? = "did:web:api.bsky.chat#bsky_chat",
) {
    private val client = client
    private val proxy = proxy

    public suspend fun listConvos(
        request: ListConvosRequest = ListConvosRequest(),
    ): ListConvosResponse = client.query(
        nsid = "chat.bsky.convo.listConvos",
        params = request,
        paramsSerializer = ListConvosRequest.serializer(),
        responseSerializer = ListConvosResponse.serializer(),
        proxy = proxy,
    )
    // ...
}
```

**Why a constructor default (not a hardcoded `val`):** sandbox, staging, or
self-hosted chat deployments can route to a different appview by passing
`ConvoService(client, proxy = "did:web:custom...")`. Setting `proxy = null`
disables the header entirely (useful for local PDS testing). 99% of consumers
get the production behavior with zero configuration.

### Pagination flow extensions

`buildFlowExtensions` calls the underlying service method (e.g.,
`listConvos(request.copy(cursor = cursor))`). Since the proxy is held on the
service instance and applied inside `listConvos`, the flow extensions inherit
the routing automatically — no changes needed.

## Testing

### `runtime/src/commonTest/.../XrpcClientTest.kt`

New MockEngine cases:

- `query` with `proxy = "did:web:api.bsky.chat#bsky_chat"` → request headers
  contain `atproto-proxy: did:web:api.bsky.chat#bsky_chat`.
- `query` with `proxy = null` → request headers do **not** contain
  `atproto-proxy`.
- Same two cases for `procedure` (JSON-body), `procedure` (no input), and
  `procedure` (raw bytes).
- `proxy` survives the 401/DPoP-nonce retry path.

### `generator/src/test/.../GoldenFileTest.kt`

Add a minimal `chat.bsky.convo.listConvos` lexicon (or a stub under a new
fictional `chat.test.demo.*` namespace mapped in `ProxyMapping`) to the
golden corpus and update the golden Kotlin to assert:

- `ConvoService` constructor includes `proxy: String? = "did:web:api.bsky.chat#bsky_chat"`.
- Each generated method passes `proxy = proxy` to `client.query/procedure`.
- A non-proxied service in the same golden run is emitted unchanged.

Run with `GOLDEN_UPDATE=1 ./gradlew :generator:test --tests '*GoldenFileTest*'`
to regenerate after the change.

### New `generator/src/test/.../ProxyMappingTest.kt`

Unit-level: prefix matching, no match, longest-prefix-wins (if two rules ever
overlap — currently not a concern).

## Migration & Compatibility

- **API impact:** additive only. Existing call sites keep compiling.
- **Generated services impact:** existing services (e.g. `FeedService`,
  `ActorService`) unchanged. Service classes for proxied namespaces gain one
  additional constructor parameter with a sensible default.
- **SemVer:** minor bump (auto-managed by semantic-release on the generated
  `:models` and `:runtime` artifacts).
- **Docs:** add a short section to the runtime KDoc on the `proxy` parameter
  and a one-paragraph note in the README on service-routed namespaces.

## Open questions for follow-up changes (out of scope here)

- Should `ProxyMapping` also cover `app.bsky.notification.*` (or whichever
  routing the notifications appview ends up using) in this same change, or
  ship that as a follow-up? Per issue #86 references the notifications gap
  as a saved precedent — recommend handling in a separate PR once the chat
  pattern is proven.
- Should there be a public `XrpcClient` extension for power users to send a
  proxy header on an arbitrary call without going through a generated
  service? Not needed for issue #86; defer until there's a real use case.
