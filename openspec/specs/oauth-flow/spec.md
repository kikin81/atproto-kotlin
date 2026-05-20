## ADDED Requirements

### Requirement: OAuth module SHALL implement the full AT Protocol authorization flow

The `:oauth` module SHALL implement the complete AT Protocol OAuth 2.0 authorization flow for public clients: handle → DID → PDS → authorization server discovery, Pushed Authorization Request (PAR) with PKCE S256, browser-based user authorization, authorization code → token exchange with DPoP proof, and session persistence via an `OAuthSessionStore` interface.

#### Scenario: Successful login flow against a real PDS

- **WHEN** a consumer calls `AtOAuth.beginLogin(handle = "alice.bsky.social")` with a valid client metadata URL
- **THEN** the module resolves the handle to a DID, the DID to a PDS, the PDS to an authorization server, generates a PKCE code verifier + challenge, generates or loads a DPoP EC P-256 keypair, pushes a PAR request, and returns an authorization URL suitable for opening in a browser

#### Scenario: Token exchange after browser redirect

- **WHEN** the browser redirects back to the app's registered redirect URI with an authorization code
- **AND** the consumer calls `AtOAuth.completeLogin(redirectUri)` with that URI
- **THEN** the module extracts the authorization code, exchanges it at the token endpoint with the PKCE code verifier and a DPoP proof, receives an access token + refresh token, persists them via `OAuthSessionStore`, and returns an authenticated session

#### Scenario: Discovery failure surfaces a typed error

- **WHEN** the handle cannot be resolved to a DID, or the PDS does not advertise an authorization server
- **THEN** the module throws a typed `OAuthDiscoveryException` with a diagnostic message naming the failed resolution step and the handle/DID/PDS that was being resolved

### Requirement: DpopAuthProvider SHALL attach DPoP proof on every XRPC request

The module SHALL provide a `DpopAuthProvider` implementation of the (widened) `AuthProvider` interface that attaches both an `Authorization: DPoP <access_token>` header and a `DPoP: <proof_jwt>` header on every XRPC request. The DPoP proof JWT SHALL be signed with the session's EC P-256 private key and SHALL include the HTTP method (`htm`), target URL (`htu`), access token hash (`ath`), a unique JWT ID (`jti`), issued-at (`iat`), and the server-issued nonce if one has been received.

#### Scenario: DPoP proof is attached to a query request

- **WHEN** `DpopAuthProvider` is wired into an `XrpcClient` and the consumer calls `FeedService(client).getTimeline()`
- **THEN** the outgoing HTTP request carries both `Authorization: DPoP <token>` and `DPoP: <signed-jwt>`, and the DPoP JWT's `htm` claim is `GET` and `htu` claim matches the request URL

#### Scenario: DPoP-Nonce rotation is handled transparently

- **WHEN** the server responds with HTTP 401 and a `DPoP-Nonce: <nonce>` header
- **THEN** `DpopAuthProvider` stores the nonce, rebuilds the DPoP proof JWT with the nonce included, and retries the request — the consumer never sees the 401

### Requirement: Session refresh SHALL be transparent to the consumer

When the access token expires and the server returns HTTP 401, the `DpopAuthProvider` SHALL automatically refresh the session using the DPoP-bound refresh token, persist the new token pair via `OAuthSessionStore`, and retry the original XRPC request. The consumer SHALL NOT be required to handle token expiry or refresh logic.

#### Scenario: Automatic refresh on expired access token

- **WHEN** an XRPC call returns HTTP 401 indicating the access token has expired
- **AND** the session holds a valid refresh token
- **THEN** `DpopAuthProvider` calls the token endpoint with `grant_type=refresh_token`, a fresh DPoP proof, and the stored refresh token, receives a new access/refresh pair, persists them, and retries the original request — the consumer receives the successful response without observing the 401

#### Scenario: Refresh failure clears the session

- **WHEN** the refresh token has been revoked or is otherwise invalid
- **THEN** `DpopAuthProvider` clears the session via `OAuthSessionStore.clear()` and throws an `OAuthSessionExpiredException` so the consumer knows to re-authenticate

### Requirement: OAuthSessionStore SHALL be a pluggable persistence interface

The module SHALL define an `OAuthSessionStore` interface with `load`, `save`, and `clear` methods. The interface SHALL be platform-agnostic (no Android or iOS imports). The Android sample SHALL provide an `EncryptedSharedPreferences`-backed implementation. The stored session SHALL include at minimum: access token, refresh token, DPoP private key (serialized), DPoP public key (serialized), DID, handle, PDS URL, and the authorization server's token endpoint URL. The `did`, `handle`, and `pdsUrl` fields MAY be transiently null in the brief window between a signup-flow token exchange and identity hydration; once hydration completes (or its bounded retry budget exhausts) the session SHALL be re-persisted with whatever values were resolved.

#### Scenario: Session survives app restart

- **WHEN** a user authenticates via OAuth and the app is force-stopped and relaunched
- **THEN** the module loads the persisted session from `OAuthSessionStore`, reconstructs the `DpopAuthProvider` with the stored DPoP keypair, and the consumer can make authenticated XRPC calls without re-authenticating

#### Scenario: Signup-flow session persists with null identity then hydrates

- **WHEN** a signup-flow session is persisted immediately after token exchange, before identity hydration completes
- **AND** the consumer reads `OAuthSession` from the store within that window
- **THEN** the loaded session carries non-null tokens + DPoP keys and possibly-null `did`/`handle`/`pdsUrl`. Once hydration completes the store is updated with the resolved identity fields.

### Requirement: PAR request SHALL handle DPoP nonce discovery transparently

The module SHALL send the initial PAR request with a DPoP proof header (without a nonce). When the authorization server responds with HTTP 401 and `use_dpop_nonce` error + `DPoP-Nonce` header (the expected happy-path response), the module SHALL store the nonce, reconstruct the DPoP proof with the nonce included, and retry the PAR request — all transparently to the consumer.

#### Scenario: PAR nonce discovery cycle succeeds silently

- **WHEN** `AtOAuth.beginLogin(handle)` sends the first PAR request without a nonce
- **AND** the authorization server responds with HTTP 401 + `{"error": "use_dpop_nonce"}` + `DPoP-Nonce: <nonce>` header
- **THEN** the module retries the PAR request with the nonce included in the DPoP proof JWT's `nonce` claim, and the consumer never observes the 401

### Requirement: Token response `sub` field SHALL be verified against the resolved DID

For sessions opened via the login flow (where a DID was resolved up-front), the module SHALL verify that the `sub` field in the token response matches the pre-resolved DID. A mismatch indicates the user authorized a different account than expected — the module SHALL reject the session and throw a typed `OAuthAccountMismatchException`. For sessions opened via the signup flow (where no DID exists pre-flight), this check SHALL be skipped and the token-response `sub` SHALL be accepted as authoritative; the signup-flow identity-derivation requirements (above) govern that path instead.

#### Scenario: Mismatched `sub` DID is rejected on the login flow

- **WHEN** `AtOAuth.completeLogin(redirectUri)` exchanges the authorization code for tokens against a session opened by `beginLogin(handle)`
- **AND** the token response's `sub` field does not match the DID resolved from the user's handle
- **THEN** the module throws `OAuthAccountMismatchException` and does NOT persist the session

#### Scenario: Signup flow accepts the token-response `sub`

- **WHEN** `AtOAuth.completeLogin(redirectUri)` completes a session opened by `beginSignup(authServer)`
- **THEN** the module accepts the token-response `sub` as the new account's DID without any prior-DID comparison, and proceeds to identity hydration

### Requirement: Callback `iss` field SHALL be verified against the discovered authorization server

The redirect URI from the authorization server includes an `iss` parameter. The module SHALL verify that `iss` matches the authorization server URL discovered via `/.well-known/oauth-protected-resource`. This prevents mix-up attacks where a malicious auth server redirects to a legitimate app.

#### Scenario: Mismatched `iss` is rejected

- **WHEN** the browser redirects to the app's callback URI with `iss=https://evil-server.example.com`
- **AND** the module expected `iss=https://legitimate-pds.example.com`
- **THEN** the module throws a typed security exception and does NOT proceed with token exchange

### Requirement: DPoP nonces SHALL be tracked per-server

The module SHALL maintain separate DPoP nonce values for the authorization server (used for PAR and token requests) and the resource server / PDS (used for XRPC requests). The `OAuthSession` SHALL persist both nonce values so they survive app restarts.

#### Scenario: Auth server and PDS nonces are independent

- **WHEN** the authorization server issues `DPoP-Nonce: auth-nonce-123` and the PDS issues `DPoP-Nonce: pds-nonce-456`
- **THEN** DPoP proofs sent to the token endpoint include `nonce: "auth-nonce-123"` and DPoP proofs sent to the PDS include `nonce: "pds-nonce-456"` — the two are never mixed

### Requirement: Module SHALL NOT require the consumer to handle JWTs, keys, or nonces

The entire DPoP proof construction, key management, nonce tracking, and JWT signing SHALL be internal to the module. The consumer-facing API SHALL expose only: `beginLogin(handle)`, `beginSignup(authServer)`, `completeLogin(redirectUri)`, `createClient()`, and `logout()`. No JWT types, no `ECPrivateKey`, no nonce strings SHALL appear in the public API surface.

#### Scenario: Consumer integration is fewer than 15 lines

- **WHEN** a developer integrates the OAuth module into a new Android app
- **THEN** the integration code (excluding imports and boilerplate) is fewer than 15 lines: construct `AtOAuth`, call `beginLogin` or `beginSignup`, handle redirect with `completeLogin`, call `createClient`, use the service classes

### Requirement: OAuth module SHALL expose a signup entry point against a known auth server

The `:oauth` module SHALL expose a public suspending function `AtOAuth.beginSignup(authServer: String = "bsky.social"): String` that begins an OAuth authorization flow against the named auth server **without** requiring a handle or DID, by short-circuiting the discovery chain to fetch only the auth-server metadata and injecting OIDC `prompt=create` into the PAR request body. The function SHALL return an authorization URL suitable for opening in a browser. The redirect MUST be completable via the existing `AtOAuth.completeLogin(redirectUri)` entry point — no new redirect handler is required of the consumer.

#### Scenario: Signup flow begins against bsky.social with no handle

- **WHEN** a consumer calls `AtOAuth.beginSignup()` with a valid client metadata URL
- **THEN** the module fetches `https://bsky.social/.well-known/oauth-authorization-server`, skips handle/DID/PDS resolution entirely, generates a PKCE code verifier + challenge and a DPoP EC P-256 keypair, pushes a PAR request that includes `prompt=create` and **no** `login_hint`, and returns an authorization URL for `https://bsky.social/oauth/authorize` carrying the resulting `request_uri`

#### Scenario: Signup completes via the existing redirect handler

- **WHEN** the browser redirects back to the app's registered redirect URI with an authorization code after the user finishes signup
- **AND** the consumer calls `AtOAuth.completeLogin(redirectUri)` with that URI
- **THEN** the module exchanges the code for tokens, accepts the token-response `sub` as the new account's DID, persists the session, and returns an authenticated session — the consumer never re-prompts for the user's handle

### Requirement: Signup SHALL be gated on the auth server advertising `prompt=create`

Before issuing the PAR request, the module SHALL verify that the auth server's metadata `prompt_values_supported` array contains `"create"`. If the value is absent, the module SHALL throw a typed `OAuthSignupNotSupportedException` carrying the auth server URL and the advertised `prompt_values_supported`. A `requirePromptCreateSupport: Boolean = true` parameter on `beginSignup` SHALL allow advanced consumers to bypass the check for non-conformant entryways.

#### Scenario: Auth server without `prompt=create` is rejected

- **WHEN** a consumer calls `AtOAuth.beginSignup(authServer = "example.entryway.test")`
- **AND** `example.entryway.test`'s `/.well-known/oauth-authorization-server` does not list `"create"` in `prompt_values_supported`
- **THEN** the module throws `OAuthSignupNotSupportedException` before issuing PAR

#### Scenario: Bypass flag skips the gate

- **WHEN** a consumer calls `AtOAuth.beginSignup(authServer = "example.entryway.test", requirePromptCreateSupport = false)`
- **AND** `example.entryway.test`'s metadata does not advertise `"create"`
- **THEN** the module proceeds with PAR carrying `prompt=create` regardless

### Requirement: DiscoveryChain SHALL support known-auth-server short-circuit

The `DiscoveryChain` SHALL expose a method that, given a known auth-server URL, fetches only the auth-server metadata and returns an `AuthServerMetadata` with `did = null`, `handle = null`, and `pdsUrl = null`. The existing handle-resolving chain SHALL be unchanged and SHALL continue to populate all three identity fields.

#### Scenario: Short-circuit skips identity resolution

- **WHEN** the module invokes `DiscoveryChain.resolveKnownAuthServer("https://bsky.social")`
- **THEN** the returned `AuthServerMetadata` carries valid `authorizationEndpoint`, `tokenEndpoint`, `parEndpoint`, and `issuer` values, and `did`, `handle`, and `pdsUrl` are all null

### Requirement: Signup token exchange SHALL derive identity from the token response

For sessions opened via `beginSignup`, after a successful token exchange the module SHALL treat the token-response `sub` field as the authoritative DID, resolve the DID's PDS via the existing DID-document → service-endpoint logic, derive the `handle` from the DID document's `alsoKnownAs`, and persist the completed session with all three identity fields populated.

#### Scenario: Identity is hydrated post-signup

- **WHEN** `AtOAuth.completeLogin(redirectUri)` exchanges the code for a signup-flow session
- **AND** the token response carries `sub = "did:plc:newlymintedid"`
- **THEN** the module resolves `did:plc:newlymintedid` via the PLC directory, extracts the `#atproto_pds` service endpoint as the PDS URL, extracts the handle from `alsoKnownAs[0]` (`at://<handle>` form), and persists the `OAuthSession` with `did`, `handle`, and `pdsUrl` all populated

#### Scenario: Bounded retry on transient resolution failure

- **WHEN** the freshly-minted DID is not yet resolvable via the PLC directory (HTTP 404 due to propagation delay)
- **THEN** the module retries resolution with bounded exponential backoff, and on persistent failure persists the session with `handle = null` / `pdsUrl = null` and a logged warning — the access token remains usable for DID-keyed calls
