## Why

`app.bsky.notification.{registerPush, unregisterPush}` and any other NSID whose `atproto-proxy` target is consumer-chosen (rather than a Bluesky-managed appview DID) cannot be called through the generated `*Service` wrappers today. `ServiceGenerator` only emits a constructor-level `proxy` parameter for service packages that hit a `ProxyMapping` entry (currently only `chat.bsky.*`), and forwards that constructor value verbatim on every call. There is no escape hatch for "this NSID needs a proxy header, but the gateway DID is the consumer's choice." Reported by `nubecita`'s `:core:push` module (PR [#301](https://github.com/kikin81/nubecita/pull/301)), which had to drop to raw `XrpcClient.procedure(...)`, duplicating the NSID string, serializer wiring, and response handling at every call site to send `atproto-proxy: did:web:push.nubecita.app#bsky_notif`. This blocks every consumer that runs its own push gateway (e.g. [DracoBlue/atproto-push-gateway](https://github.com/DracoBlue/atproto-push-gateway)) and would block any future consumer-chosen-proxy NSID.

Tracks GitHub issue [#117](https://github.com/kikin81/atproto-kotlin/issues/117).

## What Changes

- **Generator:** `ServiceGenerator` SHALL emit a final `proxy: String? = null` parameter on every generated query/procedure method, regardless of whether the service package hits a `ProxyMapping` entry. The forwarding expression to `XrpcClient.{query, procedure}` SHALL be `proxy = proxy ?: this.proxy` when the service has a constructor-level `proxy` property, and `proxy = proxy` otherwise. The method-level override wins when non-null; otherwise behavior is identical to today.
- **Generated output:** Every `*Service.<method>(...)` signature in `:models` gains a trailing `proxy: String? = null` parameter. `NotificationService.registerPush(request, proxy = "did:web:push.nubecita.app#bsky_notif")` becomes a typed, supported call. `ConvoService.<method>(request)` callers see no behavioral change (the constructor default still wins when `proxy = null`).
- **BREAKING:** Adding a parameter to public service methods is binary-breaking per the kotlinx binary-compatibility-validator, even with a default value. Cut **v9.0.0** (current is 8.1.0). Refresh `models/api/models.api` via `:models:apiDump`. Add `docs/breaking-changes/v9.md`. Include a `BREAKING CHANGE:` footer on the merge commit so semantic-release bumps major.
- **Goldens:** Regenerate generator golden fixtures and the smoke fixture via `GOLDEN_UPDATE=1`. Every emitted service in the golden corpus changes (new parameter); no Request/Response DTOs change.
- `ProxyMapping`, `ServiceGenerator.resolveProxyForPackage`, and the constructor-property emission logic are unchanged.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `lexicon-codegen`: adds a new requirement that every emitted service query/procedure method exposes a `proxy: String? = null` parameter; when non-null it overrides the service's constructor-level `proxy` default (if any) for that single call.

## Impact

- `generator/src/main/kotlin/io/github/kikin81/atproto/generator/emit/ServiceGenerator.kt` — `buildQueryMethod`, `buildProcedureMethod`, `buildCall`, `buildRawBytesCall`, `noInputCall` add the new parameter and adjust the forwarding expression. No changes to `resolveProxyForPackage` or constructor-property emission.
- `generator/src/test/kotlin/io/github/kikin81/atproto/generator/emit/ServiceGeneratorProxyEmissionTest.kt` — new test exercising the full plan→emit path against a synthetic corpus, asserting the parameter is emitted for both `chat.bsky.*` (constructor proxy present) and `app.bsky.*` (no constructor proxy) services, and that the forwarding expression collapses correctly in each case.
- `generator/src/test/resources/golden/kotlin/**` — regenerated goldens for every `*Service.kt`. No new DTOs.
- `models/build/generated/source/lexicon/commonMain/kotlin/.../{NotificationService.kt, ConvoService.kt, ...}` — all regenerated with the new method signatures.
- `models/api/models.api` — refreshed via `./gradlew :models:apiDump`. `runtime/api/runtime.api` and `oauth/api/oauth.api` are not touched.
- `docs/breaking-changes/v9.md` — new file, modeled on `docs/breaking-changes/v8.md`. Documents the new per-method parameter with before/after for the `app.bsky.notification` case (newly unblocked) and the `chat.bsky.convo` case (defaults preserve current behavior, no consumer change required).
- Out of scope (tracked separately per the GitHub issue): `NoBodyResponseSerializer` cosmetic ergonomics, and lexicon-declared proxy hints (no upstream lexicon carries routing metadata today).
- No `:runtime` change. `XrpcClient.{query, procedure}` already accept `proxy: String? = null` — only generator emission needs to plumb the override through.

## References

- Consumer PR: https://github.com/kikin81/nubecita/pull/301
- Prior chat-proxy work (resolved via `ProxyMapping`): https://github.com/kikin81/atproto-kotlin/issues/86
- Push gateway: https://github.com/DracoBlue/atproto-push-gateway (v1.2.0 deployment at https://push.nubecita.app)
