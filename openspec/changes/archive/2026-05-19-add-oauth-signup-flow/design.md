## Context

`AtOAuth.beginLogin(handle)` runs the full discovery chain (`resolveHandle` → `resolveDid` → `resolvePds` → `resolveAuthServer` → `fetchAuthServerMetadata`) before issuing the PAR request. The chain produces an `AuthServerMetadata` value that carries the discovered `did`, `handle`, and `pdsUrl` alongside the OAuth endpoints. Downstream, `completeLogin` validates the token-response `sub` claim against that pre-resolved DID (`AtOAuth.kt:155-159`), and the session is persisted with those identity fields.

For a brand-new user, none of those fields exist yet. The auth server (`bsky.social`) advertises `prompt=create` in its `prompt_values_supported` and OIDC Prompt Create 1.0 imposes no identifier requirement; the atproto OAuth spec explicitly permits hostname-only flows with no `login_hint`. The challenge is therefore not the wire shape — it's that the SDK's internal state machine assumes identity is known up front.

Downstream consumer `kikin81/nubecita` (beads ticket `nubecita-lq9t.3.5`) is blocked on this; it expects a single suspending entry point that returns an authorization URL parallel to `beginLogin`.

## Goals / Non-Goals

**Goals:**
- Add a public entry point on `AtOAuth` that begins an OAuth flow with no handle/DID, against a known auth server (default `bsky.social`), with OIDC `prompt=create` in the PAR body.
- Reuse the existing `completeLogin(redirectUri)` redirect handler — consumers do not write new redirect plumbing.
- After the user signs up in the browser and the redirect lands, the consuming app is signed in as the new account with no manual handle re-entry.
- Keep the binary-compat blast radius confined to a single major release: refresh `.api` files, commit with a `BREAKING CHANGE:` footer, ship a `docs/breaking-changes/vN.md` entry.

**Non-Goals:**
- Handle-picker UI inside the SDK (the auth server renders that).
- Custom-PDS signup (most users target `bsky.social`; defer until a real consumer asks).
- Direct-XRPC `com.atproto.server.createAccount` flow (different shape entirely).
- Supporting `prompt=login` / `prompt=select_account` in this change (out of scope; see Open Questions for whether the API should be named to leave room for them).

## Decisions

### Decision 1: API shape — `beginSignup(authServer)` vs `beginAnonymousAuth(authServer, prompt)`

**Chosen:** `beginSignup(authServer: String = "bsky.social"): String`.

**Rationale:** The narrow, intent-revealing name matches the only consumer use case we have today (signup) and reads naturally next to `beginLogin(handle)`. Callers don't have to know the OIDC `prompt` vocabulary.

**Alternative considered:** A generic `beginAnonymousAuth(authServer: String, prompt: String): String`. Would also unlock future `prompt=login` and `prompt=select_account` flows without another binary-breaking API addition. **Rejected for v1** because: (a) it leaks an OIDC-spec parameter name into our public surface, which we've otherwise kept abstract; (b) per the repo memory, *any* parameter addition is binary-breaking even with defaults, so adding `prompt=login` later is just as expensive as adding a second narrow entry point — no future-proofing win. We will keep the option open by *not* taking the name `beginSignup` for any broader meaning later.

### Decision 2: Discovery short-circuit lives in `DiscoveryChain`

Add `DiscoveryChain.resolveKnownAuthServer(authServerUrl: String): AuthServerMetadata` that calls only `fetchAuthServerMetadata` and returns a value with `did = null`, `handle = null`, `pdsUrl = null`. The existing `resolve(handle)` path is unchanged. This keeps discovery logic in one place and avoids `AtOAuth` learning to do partial chains.

### Decision 3: Make `did`, `handle`, `pdsUrl` nullable on `AuthServerMetadata` and `OAuthSession`

These three fields become `String?` to express "not known until after token exchange." This is the breaking change. Login path populates them as before; signup path populates them from the token response.

**Alternative considered:** Introduce a parallel `PendingSignupMetadata` type with a sealed-class hierarchy over `AuthServerMetadata`. Rejected: doubles the type surface for a flag's worth of state, and `OAuthSession` would need a sealed split too. The nullable approach is uglier in one place but contains the blast radius.

### Decision 4: `PendingAuthState` gains a flow-origin flag

Add `flowOrigin: FlowOrigin` (enum: `Login`, `Signup`). `completeLogin` branches on it:
- **Login:** existing behavior — `tokenResponse.sub` MUST equal `metadata.did`, else throw `OAuthAccountMismatchException`.
- **Signup:** `metadata.did` is null; accept `tokenResponse.sub` as authoritative, populate `did` from it, and populate `handle` + `pdsUrl` by running the *remainder* of the discovery chain in reverse (DID → DID doc → PDS service endpoint → handle from `alsoKnownAs`).

This keeps the login path's strict mix-up-attack protection intact while giving the signup path a way to learn the identity it just minted.

### Decision 5: PAR injection — single line in `parParams()`

`parParams()` at `AtOAuth.kt:307-321` gains an optional `prompt: String? = null` parameter; when non-null, `append("prompt", prompt)`. `loginHint` becomes nullable (the signup path has no hint). The PAR request is otherwise unchanged — same PKCE, same DPoP nonce dance.

### Decision 6: Sample app gets a "Create account" CTA, not a separate signup screen

Add a button to the existing `LoginScreen` that calls `oauth.beginSignup()` and emits the resulting URL to the same Custom Tab flow the login button uses. The redirect handler is shared. Keeps the sample's surface minimal and demonstrates that signup is "the same flow, different entry point."

### Decision 7: Gate signup on the auth server advertising `prompt=create`

Before issuing the PAR, fetch `fetchAuthServerMetadata` and verify `prompt_values_supported` contains `"create"`. If not, throw a typed `OAuthSignupNotSupportedException`. Keeps us portable across entryways per RFC 8414 metadata discovery and protects against surprise failures on non-bsky auth servers.

## Risks / Trade-offs

- **[Binary break is unavoidable]** Nullable identity fields on `AuthServerMetadata` change the public API surface (Kotlin nullability is encoded in metadata and tracked by the binary-compat validator). → Mitigation: bundle this with any other queued breaking work; ship `docs/breaking-changes/vN.md` + `BREAKING CHANGE:` footer to ensure semantic-release cuts a major bump. Per repo memory on binary-breaking changes, refresh `:runtime:apiDump` and `:models:apiDump` and commit refreshed `*.api` files.

- **[Identity-resolution failure post-signup]** After token exchange, we still need to resolve handle + PDS from the new DID. If the freshly-minted DID isn't immediately resolvable via the PLC directory (propagation delay), session persistence fails. → Mitigation: retry resolution with bounded exponential backoff; if still failing, persist the session with `handle = null` / `pdsUrl = null` and surface a warning — the access token is still usable for `did:plc:...` calls.

- **[`completeLogin` branching complexity]** The DID-mismatch check is a security-critical invariant; making it conditional risks a regression where the login path silently degrades to "no mismatch check." → Mitigation: the flag lives on `PendingAuthState`, which is built by `beginLogin` / `beginSignup` — there's no other path to construct it. Add a test that proves the login path still rejects mismatched `sub`.

- **[Non-bsky auth servers]** We default to `bsky.social` and gate on `prompt_values_supported`. Self-hosted entryways may not advertise `"create"` even if they support it. → Mitigation: accept a `requirePromptCreateSupport: Boolean = true` parameter on `beginSignup` so advanced consumers can bypass the check; default-on keeps the safe path.

- **[Test coverage for a path we can't dry-run end-to-end]** We can't actually create accounts against bsky.social in CI. → Mitigation: rely on `MockEngine` fixtures matching real PAR + token-endpoint responses; verify the wire shape (params, headers, body) byte-for-byte; document that end-to-end verification is manual against the sample app.

## Migration Plan

1. Land the `:oauth` changes behind no flag (this is a new entry point + a binary-breaking refactor; partial rollout doesn't help).
2. Refresh `:runtime:apiDump` + relevant `*.api` files; commit them in the same PR.
3. Author `docs/breaking-changes/vN.md` modeled on `v5.md`.
4. Include `BREAKING CHANGE:` footer in the squashed merge commit so semantic-release bumps the major.
5. Update sample app in the same PR (same major) to use the new entry point — demonstrates the migration in-tree.
6. Tag downstream `kikin81/nubecita#nubecita-lq9t.3.5` once the release is on Maven Central.

**Rollback:** revert the PR. No persistent state changes, no migrations.

## Open Questions

- Should the existing `OAuthSession`'s identity fields really become nullable on disk, or should we lazy-resolve and persist only after we have the full identity? Persisting `handle = null` to `EncryptedSharedPreferences` means callers who load a session early may see a half-populated session. Lean: persist eagerly with nulls; document the half-state as expected in the seconds immediately after signup completion.
- Naming nit: `beginSignup` returns just a `String` (the auth URL) like `beginLogin` does — but should it return a richer object (e.g., a `SignupHandle` with the URL + the pending state ID) for callers that want to observe completion? Lean: keep parity with `beginLogin` for v1; revisit if a real consumer asks.
- Are there atproto-spec-level requirements we're missing for "anonymous" PAR flows (e.g., must `scope` differ from login scope)? Spec is silent; lean is to use the same scope.
