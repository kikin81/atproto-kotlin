## 1. DiscoveryChain short-circuit

- [x] 1.1 Make `AuthServerMetadata.did`, `.handle`, `.pdsUrl` nullable (`String?`) in `oauth/.../DiscoveryChain.kt`
- [x] 1.2 Add `DiscoveryChain.resolveKnownAuthServer(authServerUrl: String): AuthServerMetadata` that only calls `fetchAuthServerMetadata`
- [x] 1.3 Extract the existing DID → DID-doc → PDS → handle-from-`alsoKnownAs` derivation into a reusable `hydrateIdentityFromDid(did)` helper for post-signup use
- [x] 1.4 Update `DiscoveryChainTest` with fixtures for `resolveKnownAuthServer` (asserts identity fields are null and endpoints are populated)

## 2. AtOAuth signup entry point

- [x] 2.1 Add `FlowOrigin` enum (`Login`, `Signup`) and add `flowOrigin: FlowOrigin` field to `PendingAuthState`
- [x] 2.2 Add optional `prompt: String? = null` parameter to `parParams()`; append `prompt` when non-null
- [x] 2.3 Make `loginHint` parameter on `parParams()` and `pushAuthorizationRequest()` nullable; skip `append("login_hint", ...)` when null
- [x] 2.4 Implement `AtOAuth.beginSignup(authServer: String = "bsky.social", requirePromptCreateSupport: Boolean = true): String`
- [x] 2.5 In `beginSignup`, gate on `metadata.promptValuesSupported.contains("create")` when `requirePromptCreateSupport`; throw `OAuthSignupNotSupportedException` otherwise
- [x] 2.6 Persist `promptValuesSupported: List<String>` on `AuthServerMetadata` (parsed from auth-server metadata) so the gate has data to read
- [x] 2.7 Construct `PendingAuthState` with `flowOrigin = Signup` and store on the instance

## 3. completeLogin branching

- [x] 3.1 Branch `completeLogin` on `pendingState.flowOrigin`: skip `tokenResponse.sub` vs `metadata.did` mismatch check when `Signup`
- [x] 3.2 In the signup branch, populate the in-progress metadata's `did` from `tokenResponse.sub`
- [x] 3.3 Call `DiscoveryChain.hydrateIdentityFromDid(did)` to resolve `handle` + `pdsUrl` with bounded exponential backoff (cap retries: 3 attempts, ~100ms / 400ms / 1.2s)
- [x] 3.4 Persist `OAuthSession` immediately after token exchange (with whatever identity is known), then re-persist after hydration completes
- [x] 3.5 Make `OAuthSession.did`, `.handle`, `.pdsUrl` nullable to match; ensure all serializers / deserializers tolerate null

## 4. Exceptions and public surface

- [x] 4.1 Add `OAuthSignupNotSupportedException` carrying `authServerUrl: String` and `advertisedPromptValues: List<String>`
- [x] 4.2 Ensure the only new public-API symbols are `beginSignup`, `OAuthSignupNotSupportedException`, and the now-nullable fields — keep `FlowOrigin` and `hydrateIdentityFromDid` internal

## 5. Tests

- [x] 5.1 Add `AtOAuthTest` case: `beginSignup()` short-circuits discovery (single HTTP call to `bsky.social/.well-known/oauth-authorization-server`) and posts PAR with `prompt=create` and no `login_hint`
- [x] 5.2 Add `AtOAuthTest` case: `completeLogin` on a signup-flow session accepts a token-response `sub` that has no pre-resolved DID comparison
- [x] 5.3 Add `AtOAuthTest` case: signup-flow `completeLogin` calls PLC directory for the new DID and populates `handle` + `pdsUrl` (merged into 5.2 — same fixture asserts both)
- [x] 5.4 Add `AtOAuthTest` case: signup-flow `completeLogin` survives a 404 from PLC, retries, then persists the session with null identity fields when the budget exhausts
- [x] 5.5 Add `AtOAuthTest` regression: `beginLogin` + mismatched `sub` STILL throws `OAuthAccountMismatchException` (proves the branch isn't disabling the login-path security check)
- [x] 5.6 Add `AtOAuthTest` case: `beginSignup` against an auth server without `"create"` in `prompt_values_supported` throws `OAuthSignupNotSupportedException`
- [x] 5.7 Add `AtOAuthTest` case: `beginSignup(requirePromptCreateSupport = false)` bypasses the gate

## 6. Sample Android app

- [x] 6.1 Add a "Create account" button to `LoginScreen.kt` below the existing sign-in form
- [x] 6.2 Add `MainViewModel.beginSignup()` that calls `oauth.beginSignup()`, mirrors the existing `login()` error handling, and emits the URL to the same Custom Tab flow
- [x] 6.3 Render `OAuthSignupNotSupportedException` as a user-facing error message; keep the login form enabled (handled by existing error path — `OAuthSignupNotSupportedException.message` is user-readable and surfaces via `LoggedOut(error = …)` exactly like other errors)
- [x] 6.4 Add `samples/android/README.md` snippet documenting the signup CTA

## 7. Binary-compat + release plumbing

- [x] 7.1 Run `./gradlew :runtime:apiDump :models:apiDump` and any oauth-module equivalent; commit refreshed `*.api` files
- [x] 7.2 Author `docs/breaking-changes/vN.md` modeled on `v5.md` — N=8 (next major after current v7.0.1)
- [x] 7.3 Update `oauth/README.md` (if present) and KDoc on `AtOAuth.beginSignup` with a usage example (sample README updated; KDoc added on `beginSignup`)
- [ ] 7.4 Squash-merge commit message MUST include a `BREAKING CHANGE:` footer so semantic-release cuts a major bump — deferred to the PR merge step

## 8. Wrap-up

- [x] 8.1 `./gradlew spotlessApply` and `./gradlew build` clean
- [x] 8.2 `:oauth:test` and `:samples:android:testDebugUnitTest` green (verified via full `./gradlew build`)
- [ ] 8.3 Manual end-to-end against `bsky.social` from the sample app: tap "Create account", complete signup in the browser, confirm app lands on feed screen with the new account — requires running on a real device; not runnable from CI/agent
- [ ] 8.4 Notify downstream `kikin81/nubecita#nubecita-lq9t.3.5` once the release is on Maven Central — post-release task
