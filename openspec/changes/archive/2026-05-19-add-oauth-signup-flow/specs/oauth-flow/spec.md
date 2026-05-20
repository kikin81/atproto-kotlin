## ADDED Requirements

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

## MODIFIED Requirements

### Requirement: Token response `sub` field SHALL be verified against the resolved DID

For sessions opened via the login flow (where a DID was resolved up-front), the module SHALL verify that the `sub` field in the token response matches the pre-resolved DID. A mismatch indicates the user authorized a different account than expected — the module SHALL reject the session and throw a typed `OAuthAccountMismatchException`. For sessions opened via the signup flow (where no DID exists pre-flight), this check SHALL be skipped and the token-response `sub` SHALL be accepted as authoritative; the signup-flow identity-derivation requirements (above) govern that path instead.

#### Scenario: Mismatched `sub` DID is rejected on the login flow

- **WHEN** `AtOAuth.completeLogin(redirectUri)` exchanges the authorization code for tokens against a session opened by `beginLogin(handle)`
- **AND** the token response's `sub` field does not match the DID resolved from the user's handle
- **THEN** the module throws `OAuthAccountMismatchException` and does NOT persist the session

#### Scenario: Signup flow accepts the token-response `sub`

- **WHEN** `AtOAuth.completeLogin(redirectUri)` completes a session opened by `beginSignup(authServer)`
- **THEN** the module accepts the token-response `sub` as the new account's DID without any prior-DID comparison, and proceeds to identity hydration

### Requirement: OAuthSessionStore SHALL be a pluggable persistence interface

The module SHALL define an `OAuthSessionStore` interface with `load`, `save`, and `clear` methods. The interface SHALL be platform-agnostic (no Android or iOS imports). The Android sample SHALL provide an `EncryptedSharedPreferences`-backed implementation. The stored session SHALL include at minimum: access token, refresh token, DPoP private key (serialized), DPoP public key (serialized), DID, handle, PDS URL, and the authorization server's token endpoint URL. The `did`, `handle`, and `pdsUrl` fields MAY be transiently null in the brief window between a signup-flow token exchange and identity hydration; once hydration completes (or its bounded retry budget exhausts) the session SHALL be re-persisted with whatever values were resolved.

#### Scenario: Session survives app restart

- **WHEN** a user authenticates via OAuth and the app is force-stopped and relaunched
- **THEN** the module loads the persisted session from `OAuthSessionStore`, reconstructs the `DpopAuthProvider` with the stored DPoP keypair, and the consumer can make authenticated XRPC calls without re-authenticating

#### Scenario: Signup-flow session persists with null identity then hydrates

- **WHEN** a signup-flow session is persisted immediately after token exchange, before identity hydration completes
- **AND** the consumer reads `OAuthSession` from the store within that window
- **THEN** the loaded session carries non-null tokens + DPoP keys and possibly-null `did`/`handle`/`pdsUrl`. Once hydration completes the store is updated with the resolved identity fields.

### Requirement: Module SHALL NOT require the consumer to handle JWTs, keys, or nonces

The entire DPoP proof construction, key management, nonce tracking, and JWT signing SHALL be internal to the module. The consumer-facing API SHALL expose only: `beginLogin(handle)`, `beginSignup(authServer)`, `completeLogin(redirectUri)`, `createClient()`, and `logout()`. No JWT types, no `ECPrivateKey`, no nonce strings SHALL appear in the public API surface.

#### Scenario: Consumer integration is fewer than 15 lines

- **WHEN** a developer integrates the OAuth module into a new Android app
- **THEN** the integration code (excluding imports and boilerplate) is fewer than 15 lines: construct `AtOAuth`, call `beginLogin` or `beginSignup`, handle redirect with `completeLogin`, call `createClient`, use the service classes
