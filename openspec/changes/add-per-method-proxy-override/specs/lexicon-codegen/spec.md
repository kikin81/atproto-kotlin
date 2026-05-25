## ADDED Requirements

### Requirement: Generated service methods SHALL expose a per-call `proxy: String? = null` override

For every emitted XRPC service method (query or procedure, regardless of input shape), `ServiceGenerator` SHALL append a final parameter `proxy: String? = null` to the method signature, and SHALL forward that value to the corresponding `XrpcClient.{query, procedure}` call.

Forwarding semantics:
- When the enclosing service class has a constructor-level `proxy` property (i.e. `resolveProxyForPackage` returned a non-null DID for the package's NSIDs), the emitted forwarding expression SHALL be `proxy = proxy ?: this.proxy`. The method-level value wins when non-null; the constructor-level default is used when the method-level value is null.
- When the enclosing service class has no constructor-level `proxy` property (i.e. `resolveProxyForPackage` returned null for the package's NSIDs), the emitted forwarding expression SHALL be `proxy = proxy`. The method-level value is forwarded directly; when null, no `atproto-proxy` header is sent (today's behavior).

The constructor-level `proxy` property emission, the `ProxyMapping` table, and the `resolveProxyForPackage` function SHALL NOT change as part of this requirement.

#### Scenario: Consumer-chosen proxy override on an unproxied service

- **WHEN** a consumer calls `NotificationService(client).registerPush(request, proxy = "did:web:push.nubecita.app#bsky_notif")`
- **THEN** the emitted method body invokes `client.procedure(nsid = "app.bsky.notification.registerPush", ..., proxy = proxy)`, and the `XrpcClient` sends the `atproto-proxy: did:web:push.nubecita.app#bsky_notif` header on the resulting request.

#### Scenario: Unproxied service with no caller override is wire-identical to today

- **WHEN** a consumer calls `NotificationService(client).registerPush(request)` (no `proxy` argument)
- **THEN** the method parameter `proxy` defaults to `null`, the forwarding expression evaluates to `null`, and `XrpcClient.procedure` sends no `atproto-proxy` header — identical to the behavior emitted before this change.

#### Scenario: Proxied service with no caller override falls back to constructor default

- **WHEN** a consumer calls `ConvoService(client).listConvos(request)` (no `proxy` argument)
- **THEN** the method parameter `proxy` defaults to `null`, the forwarding expression `proxy ?: this.proxy` evaluates to the constructor-level default `"did:web:api.bsky.chat#bsky_chat"`, and `XrpcClient.query` sends `atproto-proxy: did:web:api.bsky.chat#bsky_chat` — identical to the behavior emitted before this change.

#### Scenario: Proxied service with caller override uses the override

- **WHEN** a consumer calls `ConvoService(client).listConvos(request, proxy = "did:web:custom.chat#bsky_chat")`
- **THEN** the forwarding expression `proxy ?: this.proxy` evaluates to the method-level value, and `XrpcClient.query` sends `atproto-proxy: did:web:custom.chat#bsky_chat`, ignoring the constructor-level default.

#### Scenario: Raw-bytes procedures get the same per-method override

- **WHEN** the generator emits a service method for a procedure whose `input.encoding` is non-JSON (e.g. `RepoService.uploadBlob(input: ByteArray, inputContentType: ContentType)`)
- **THEN** the emitted method signature SHALL end with `proxy: String? = null`, and the emitted body SHALL forward `proxy = proxy` (or `proxy = proxy ?: this.proxy` if the service has a constructor-level `proxy`).

#### Scenario: Constructor-level proxy property is unchanged for services with a ProxyMapping hit

- **WHEN** the generator emits a service class for a package whose NSIDs hit a `ProxyMapping` entry (e.g. `ConvoService` for `chat.bsky.convo.*`)
- **THEN** the constructor SHALL still expose `proxy: String? = "<hardcoded-did>"` with the same default value as before this change, and the private `proxy` property SHALL still be emitted on the class — only the method bodies' forwarding expressions change.
