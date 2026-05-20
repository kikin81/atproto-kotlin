## Why

`AtOAuth.beginLogin(handle)` requires a resolved handle/DID, which doesn't exist for a user who has never created an account. Consuming apps currently work around this by dumping users into `bsky.app`'s web flow, after which users must re-type their newly-created handle back in the app — a discontinuity that loses a substantial fraction of brand-new users. `bsky.social` already advertises `prompt=create` in `prompt_values_supported`, so the server side is ready; the SDK needs a matching entry point.

## What Changes

- Add a new `AtOAuth` entry point that begins an OAuth flow against a **known auth server** (default `bsky.social`) with no handle/DID required, sending OIDC `prompt=create` in the PAR body so the auth server renders its signup UI.
- Short-circuit `DiscoveryChain` so a known auth-server URL skips `resolveHandle` / `resolveDid` / `resolvePds` / `resolveAuthServer` and goes straight to `fetchAuthServerMetadata`.
- **BREAKING (binary):** `AuthServerMetadata` (and `OAuthSession`) treat `did`, `handle`, and `pdsUrl` as deferred-resolution fields — populated post-token-exchange for the signup path. Existing login path still resolves them eagerly. Public-API binary surface changes; release will need a `BREAKING CHANGE:` footer to cut a major bump and a `docs/breaking-changes/vN.md` entry.
- `completeLogin(redirectUri)` learns that the DID-mismatch check at the token-response stage is conditional on whether `pendingState` was opened with a pre-resolved DID. Signup flows accept the token-response `sub` as authoritative and resolve `handle` + `pdsUrl` from the freshly minted account.
- Sample Android app gains a "Create account" CTA that calls the new entry point, as a reference integration.
- Reuse the existing `completeLogin(redirectUri)` redirect handler — no new redirect plumbing on the consumer side.

## Capabilities

### New Capabilities

(none — this extends the existing OAuth flow capability rather than introducing a new one)

### Modified Capabilities

- `oauth-flow`: Adds a signup entry point that operates against a known auth server with no handle/DID, injects `prompt=create` into PAR, and defers identity (`did`/`handle`/`pdsUrl`) resolution until after token exchange. Modifies the `sub`-vs-resolved-DID verification requirement to make it conditional on the flow origin (login path keeps strict check; signup path treats the token response as the identity source of truth).
- `oauth-android-integration`: Sample app exposes a "Create account" CTA wired to the new entry point.

## Impact

- **`:oauth` module**: new public `AtOAuth.beginSignup(authServer: String = "bsky.social")` entry point (or — pending API-shape decision — a more general `beginAnonymousAuth(authServer, prompt)`); changes to `DiscoveryChain` to support short-circuit-by-known-auth-server; nullable `did`/`handle`/`pdsUrl` on `AuthServerMetadata` + `OAuthSession`; `PendingAuthState` gains a flow-origin flag; `completeLogin` branches DID validation on that flag.
- **Binary-compatibility validator**: `:runtime:apiDump` / `:models:apiDump` and `:oauth` public surface will need a refreshed `.api` file. Per repo memory, semantic-release won't cut a major bump from defaults alone — commit message MUST include a `BREAKING CHANGE:` footer, and `docs/breaking-changes/vN.md` must be added modeled on `v5.md`.
- **`:samples:android`**: new "Create account" CTA in `MainViewModel` and the login screen; redirect handling unchanged.
- **Tests**: new `AtOAuthTest` fixtures for the signup path (mocked PAR + token exchange against a hardcoded auth server, token response carrying a brand-new DID).
- **Docs**: README + KDoc updates noting the new entry point; downstream tracker `kikin81/nubecita#nubecita-lq9t.3.5` unblocked on release.
- **Out of scope**: handle-picker UI (auth server handles it); custom-PDS signup (defer until real demand); in-app `com.atproto.server.createAccount` direct-XRPC signup (different flow).
