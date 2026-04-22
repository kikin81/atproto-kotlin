# Module oauth

AT Protocol OAuth 2.0 module for public clients (Android and JVM
desktop apps). JVM-only — `java.security` is used for EC P-256 key
generation and signing so no external JWT library is pulled in.

## What's in here

- **`AtOAuth`** — flow orchestrator. `beginLogin(handle)` resolves the
  handle to DID → PDS → authorization server, runs Pushed Authorization
  Request (PAR) with PKCE + DPoP, and returns an authorization URL to
  open in a browser. `completeLogin(redirectUri)` exchanges the code
  for DPoP-bound tokens and persists the session. `createClient()`
  returns an `XrpcClient` with a `DpopAuthProvider` that handles token
  refresh and DPoP nonce rotation transparently.
- **`DpopAuthProvider`** — `AuthProvider` implementation that attaches
  `Authorization: DPoP <token>` + `DPoP: <proof>` headers on every
  request, rotates nonces on 401, and refreshes access tokens.
- **`OAuthSession` / `OAuthSessionStore`** — serializable session
  carrying the DPoP private key + tokens, plus a `SessionStore` interface
  for persistence. Consumer apps implement `SessionStore` (typically
  backed by `EncryptedSharedPreferences` on Android).
- **`DpopSigner`** — EC P-256 DPoP JWT signer built on `java.security`.
  Calibrates its clock against the server's `Date` header to prevent
  `iat` rejection on devices with clock drift.
- **Discovery** — handle → DID (DNS-over-HTTPS + HTTP fallback) → PDS →
  authorization server metadata resolution.
- **PKCE** — S256 challenge + verifier generation.

## Security properties

- **DPoP (RFC 9449)** proof-of-possession binds access tokens to a
  per-client EC P-256 keypair. Stolen tokens cannot be replayed
  without the private key.
- **PKCE S256 (RFC 7636)** prevents authorization-code interception.
- **State + `iss` + `sub` validation** on the redirect guards against
  CSRF, mix-up attacks, and account mismatches.
- **Independent nonce tracking** for PDS and authorization server
  ensures nonce rotation is handled correctly per endpoint.

## Use with Android

See the [`atproto-oauth` skill](https://github.com/kikin81/atproto-kotlin/blob/main/skills/atproto-oauth/SKILL.md)
for a complete Android walkthrough: hosting the client-metadata JSON,
configuring the AndroidManifest intent filter, implementing an
encrypted `OAuthSessionStore`, driving the flow from a ViewModel, and
capturing the Custom Tabs redirect. A working reference app lives at
[`samples/android`](https://github.com/kikin81/atproto-kotlin/tree/main/samples/android).

# Package io.github.kikin81.atproto.oauth

`AtOAuth` flow orchestrator, `DpopAuthProvider`, `OAuthSession(Store)`,
`DpopSigner`, and the exceptions thrown when a session expires or a
step in the OAuth flow fails.
