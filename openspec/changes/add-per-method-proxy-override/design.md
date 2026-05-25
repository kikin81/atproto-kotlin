## Context

`ServiceGenerator` emits one Kotlin `*Service` class per emission package, grouping every `QueryDef` / `ProcedureDef` whose Request/Response classes land in that package. For service packages whose NSIDs hit a `ProxyMapping` entry (today: only `chat.bsky.*` → `did:web:api.bsky.chat#bsky_chat`), the generator emits a constructor parameter `proxy: String? = "<hardcoded-did>"` and forwards `proxy = proxy` on every call. For all other packages, no proxy is emitted and the generated body calls `client.{query, procedure}(...)` without a `proxy` argument (`XrpcClient` defaults it to `null`, so no `atproto-proxy` header is sent).

`XrpcClient.{query, procedure}` already accept `proxy: String? = null` on every overload, including the raw-bytes overload added in the [raw-bytes-procedures change](../archive/2026-05-04-raw-bytes-procedures/proposal.md). The wire-level plumbing exists end-to-end; only the generator wrapper hides it.

The blocked use case: `app.bsky.notification.{registerPush, unregisterPush}` per the wire contract requires `atproto-proxy: <gateway-did>#bsky_notif`. The gateway DID is operator-chosen — Bluesky's official client uses `did:web:api.bsky.app#bsky_notif`, self-hosted operators use whatever they've stood up (e.g. `did:web:push.nubecita.app#bsky_notif` for the `nubecita` consumer running [DracoBlue/atproto-push-gateway](https://github.com/DracoBlue/atproto-push-gateway)). `ProxyMapping` cannot legitimately hardcode a gateway choice that varies per consumer.

The current bypass: `nubecita` PR [#301](https://github.com/kikin81/nubecita/pull/301) calls `XrpcClient.procedure(...)` directly, duplicating the NSID string, both serializer references, and the response-serializer wiring. The bypass works but loses the entire reason the generated wrapper exists.

## Goals / Non-Goals

**Goals:**
- Every generated `*Service.<method>(...)` accepts an optional per-call `proxy: String? = null` override.
- For services with a constructor-level `proxy` (today: `ConvoService`), the override falls back to the constructor default when null — existing call sites compile unchanged with identical wire behavior.
- For services without a constructor-level `proxy` (everyone else), the override is the only way to send an `atproto-proxy` header. When null, the call is wire-identical to today.
- The change is mechanical and applies uniformly: no per-NSID allowlist, no opt-in.
- Cut a clean v9.0.0 with a `docs/breaking-changes/v9.md` page and a refreshed `models/api/models.api`.

**Non-Goals:**
- Adding a `NoBodyResponseSerializer` runtime marker (mentioned as "out of scope" on issue #117 — friendlier than `UnitResponseSerializer` for procedures with no `output.schema`). File separately if motivated.
- Reading proxy hints from lexicon JSON metadata (no upstream lexicon carries routing metadata today; `ProxyMapping` stays the source of truth for constructor-level defaults).
- Adding chat-gateway override support to `ConvoService` consumers. The per-method `proxy` parameter gives them this for free, but the docs and breaking-changes page don't actively promote it — `chat.bsky.*` consumers should keep using the constructor default.
- Touching `XrpcClient` or any other `:runtime` surface. The runtime already supports `proxy` per-call.
- Touching `ProxyMapping`, `resolveProxyForPackage`, or constructor-property emission. The constructor-level proxy stays exactly as today.

## Decisions

### Decision 1: Per-method parameter on every method, not just proxied services

Emit `proxy: String? = null` on **every** generated query/procedure method, regardless of whether the service has a `ProxyMapping` hit. The alternative — emit the parameter only on services without a constructor proxy, or only on the specific NSIDs that "need" it — would require either (a) a per-NSID allowlist (same fragility as `ProxyMapping`, just moved one layer down) or (b) consumers learning which services do or don't accept the override. Uniformity wins: every method, same shape, one rule to remember.

This is also a free upgrade for `chat.bsky.*` consumers who want to point at a self-hosted chat appview — not a use case today, but the API supports it without extra work.

### Decision 2: Forwarding expression `proxy ?: this.proxy` vs. explicit branches

When the service has a constructor `proxy` property, the emitted forwarding expression is:

```kotlin
proxy = proxy ?: this.proxy
```

Method parameter `proxy` shadows the class property of the same name, so `this.proxy` is the only way to reference the constructor-level value. Kotlin's Elvis operator collapses to the method override when non-null, the constructor default otherwise — exactly the semantics we want, in one line.

The alternative — emit two overloads (one without `proxy`, one with) — doubles the surface area, fights against KotlinPoet's parameter-with-default model, and gains nothing.

For services without a constructor `proxy`, the expression collapses to just `proxy = proxy`. No `this.proxy` reference; nothing to fall back to.

### Decision 3: Cut v9.0.0; no compatibility shim

Adding any parameter to a public API tracked by `kotlinx-binary-compatibility-validator` is binary-breaking even with a default value. The validator runs as `:models:apiCheck` in CI (gating every release) and refuses to pass against the previous `models/api/models.api` dump once any signature shifts. The only way to satisfy it is to refresh the dump and accept the major bump. Prior breaking releases — see `docs/breaking-changes/v5.md`, `v6.md`, `v7.md`, `v8.md` — followed the same pattern.

There is no source-level break: existing call sites like `notificationService.registerPush(request)` compile unchanged. Only Kotlin consumers who recompile against v9 see new behavior available (the parameter); Java consumers shouldn't exist (this is a KMP library, but `:models` publishes a JVM artifact too — the new parameter is still source-compatible from Java via the default-value bridge KotlinPoet emits).

Alternatives rejected:
- **Hidden internal flag** to skip parameter emission on a per-package basis: defeats the entire point of the change (uniformity).
- **New service variant** (`NotificationServiceWithProxy`): API duplication, naming bikeshed, doesn't generalize.
- **Defer the bump by emitting two separate functions** (one wrapping the other): same binary breakage in the underlying generated method, just buried.

### Decision 4: Test surface — full plan→emit, not isolated unit

The existing `ServiceGeneratorResolveProxyTest` tests the pure function `resolveProxyForPackage` in isolation — it's not appropriate for asserting emitted Kotlin syntax. The new test runs a small synthetic lexicon corpus (one `chat.bsky.*` NSID, one `app.bsky.*` NSID, both with at least one query and one procedure) through the same stages that `CodeGenerator` invokes for service emission — `LexiconParser → SymbolTable.build → RefResolver.validate → ContextTagger → EmissionPlan.build → ServiceGenerator.emitAll` — and asserts against the emitted `TypeSpec`. (`VerificationPass`, which `CodeGenerator` runs after `ServiceGenerator.emitAll` to catch naming collisions across the full corpus, is intentionally omitted: the synthetic corpus is too small to trip a collision, and the test's invariants are about per-method parameter and body shape, not naming.):

- Every `FunSpec` in the service `TypeSpec` ends with a `ParameterSpec` named `proxy` of type `String?` with default `null`.
- The method body `CodeBlock` for the proxied service contains `proxy = proxy ?: this.proxy`; the unproxied service contains `proxy = proxy`.

This is heavier than a string assertion but cheap to maintain — the synthetic lexicons live in the test resources alongside the existing generator test fixtures.

The byte-for-byte golden test (`GoldenFileTest`) covers the regression-detection side: any unintended change to emitted output trips it.

### Decision 5: Goldens regenerate uniformly; smoke fixture is the consumer-facing verification

Every `*Service.kt` in `generator/src/test/resources/golden/kotlin/...` gains the new parameter on every method. The diff is mechanical and uniform — review focus is "did anything *not* change that should have?" The smoke fixture under `generator/build/generated-smoke/...` (regenerated by the existing smoke task) is the consumer-facing verification: `NotificationService.registerPush` and `unregisterPush` MUST end up with `proxy: String? = null` parameters.

## Risks / Trade-offs

- **Risk: Goldens diff is enormous and review-fatigued.** Every emitted service changes. → **Mitigation:** the change is mechanical (one parameter added, one expression adjusted). Reviewers can spot-check 3-4 services (one unproxied, one proxied, one with the raw-bytes overload) and trust the synthetic-corpus test to catch deviations. The PR description should call this out explicitly so reviewers know what to look at.
- **Risk: A consumer recompiling against v9 hits the binary break without realizing it.** → **Mitigation:** `docs/breaking-changes/v9.md`, semantic-release major bump (so the version number itself signals it), and an explicit "no source changes required" note for the common case.
- **Risk: `this.proxy` in the generated body could shadow incorrectly if a future change renames the constructor property.** → **Mitigation:** the constructor property is also named `proxy` in the same emitter; if one is renamed, both are. The generator is the source of truth for both names — they cannot drift.
- **Risk: A future `ProxyMapping` entry whose NSIDs span multiple emission packages would still throw `VerificationFailure` in `resolveProxyForPackage` (today's behavior).** → **Not changing.** This is intentional — `resolveProxyForPackage` enforces "one proxy per service package", which is unrelated to per-method overrides.
- **Trade-off: Verbose for consumers who never override the proxy.** Every IDE autocomplete shows a `proxy` parameter that 99% of callers will ignore. Accepted — the alternative (no escape hatch) is strictly worse for the 1% of callers who need it, and Kotlin's named-argument culture makes the noise tolerable.

## Migration Plan

1. Implement the generator changes.
2. Regenerate goldens (`GOLDEN_UPDATE=1 ./gradlew :generator:test --tests '*GoldenFileTest*'`); commit the diff.
3. Regenerate the `:models` build (`./gradlew :models:assemble`); refresh `models/api/models.api` (`./gradlew :models:apiDump`); commit.
4. Write `docs/breaking-changes/v9.md` modeled on `docs/breaking-changes/v8.md`. Cover:
   - What changed (per-method `proxy` parameter on every generated service method)
   - What didn't change (source compatibility; constructor `proxy` defaults still win when method param is null)
   - The motivating use case (push registration with a self-hosted gateway), with before/after code
   - The chat-services case (no consumer change required, plus the free-upgrade ability to override per-call)
5. Open the PR. Commit message MUST include a `BREAKING CHANGE:` footer (semantic-release reads this to bump major).
6. After merge, semantic-release cuts v9.0.0 and publishes. Consumers see the new method shape in their next Gradle resolve.
7. Open follow-up bd issues for the out-of-scope items: `NoBodyResponseSerializer` ergonomics, lexicon-declared proxy hints.

**Rollback:** If a regression surfaces post-release, revert the generator PR, refresh `models/api/models.api` back to the v8 shape, and cut a v9.0.1 patch. (Re-publishing an earlier method shape under v8.x is not an option — semver forbids it.)

## Open Questions

_None._ The issue is fully specified and the brainstorming session converged on a single approach.
