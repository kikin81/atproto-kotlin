## ADDED Requirements

### Requirement: Android sample SHALL authenticate via OAuth Custom Tabs

The `:samples:android` module SHALL replace the `createSession` app-password flow with the AT Protocol OAuth flow using Android Custom Tabs (`androidx.browser:browser`) for the browser-based authorization step. The sample SHALL register a redirect URI via an intent filter on a dedicated Activity (or the existing `MainActivity` with a deep-link intent filter). The sample's login screen SHALL no longer display the "NOT FOR PRODUCTION" banner.

#### Scenario: User logs in via Custom Tabs

- **WHEN** the user enters their handle on the login screen and taps "Sign in"
- **THEN** the sample opens a Custom Tab to the authorization server's `/authorize` endpoint (constructed by `AtOAuth.beginLogin`), the user authenticates in the browser, and on approval the browser redirects back to the app's registered redirect URI

#### Scenario: Redirect is captured and login completes

- **WHEN** the Custom Tab redirects to the app's registered scheme (e.g. `atproto-kotlin-sample://oauth-redirect?code=...&state=...`)
- **THEN** the redirect Activity (or intent filter on `MainActivity`) captures the URI, passes it to `AtOAuth.completeLogin(redirectUri)`, and the app transitions to the feed screen with an authenticated session

#### Scenario: Stopgap banner is removed

- **WHEN** a developer opens the sample's `LoginScreen.kt` after this change
- **THEN** the "NOT FOR PRODUCTION" `StopgapBanner` composable and all references to app-password deprecation are absent from the source

### Requirement: Android session store SHALL persist DPoP keypair securely

The sample's `SessionStore` (or a new `OAuthSessionStore` implementation) SHALL persist the DPoP EC P-256 keypair alongside the access/refresh tokens in `EncryptedSharedPreferences`. Losing the DPoP private key invalidates all tokens bound to it, so the key MUST survive app restarts, process death, and configuration changes.

#### Scenario: DPoP key survives app restart

- **WHEN** the user authenticates and the DPoP keypair is generated
- **AND** the app is force-stopped and relaunched
- **THEN** the persisted DPoP keypair is loaded from `EncryptedSharedPreferences` and the `DpopAuthProvider` signs fresh DPoP proofs with the original key — XRPC calls succeed without re-authentication

#### Scenario: Logout clears both tokens and the DPoP keypair

- **WHEN** the user taps "Sign out"
- **THEN** the session store removes the access token, refresh token, AND the DPoP keypair from encrypted storage. A subsequent app restart shows the login screen.

### Requirement: Client metadata JSON SHALL be documented and hosted for the sample

The sample's README SHALL document the required `client-metadata.json` shape (fields: `client_id`, `client_name`, `client_uri`, `redirect_uris`, `grant_types`, `response_types`, `scope`, `dpop_bound_access_tokens`, `token_endpoint_auth_method`). The sample SHALL reference a hosted `client-metadata.json` at a public HTTPS URL. The README SHALL explain how a new consumer creates and hosts their own.

#### Scenario: Sample's client metadata is publicly accessible

- **WHEN** a developer reads the sample's `AtOAuth` constructor call in `MainActivity.kt`
- **THEN** the `clientMetadataUrl` argument points to a live HTTPS URL that returns a valid JSON document with all required OAuth client metadata fields

### Requirement: Android sample SHALL expose a "Create account" CTA driven by `beginSignup`

The `:samples:android` login screen SHALL render a secondary "Create account" call-to-action below the existing handle-and-sign-in form. Tapping the CTA SHALL call `AtOAuth.beginSignup()` (with the default `bsky.social` auth server), emit the returned authorization URL to the same Custom Tab pipeline the login flow uses, and on successful redirect SHALL land the user on the feed screen as the newly-created account without prompting for a handle.

#### Scenario: User taps "Create account" and lands signed in

- **WHEN** the user opens the login screen and taps "Create account"
- **THEN** the sample calls `oauth.beginSignup()`, opens a Custom Tab to the authorization server's `/authorize` endpoint, the user completes signup in the browser, the redirect is captured by the existing redirect intent filter, `AtOAuth.completeLogin(redirectUri)` is invoked, and the app transitions to the feed screen with a session for the newly-created DID

#### Scenario: CTA reuses the existing redirect handler

- **WHEN** a developer reads the sample's `MainActivity.kt` and `MainViewModel.kt`
- **THEN** the redirect intent filter and the `completeLogin` invocation are shared between the login and signup paths — there is no second redirect handler

### Requirement: Sample SHALL surface a typed error if signup is unsupported by the auth server

If `beginSignup` throws `OAuthSignupNotSupportedException` (because the configured auth server does not advertise `prompt=create` in its `prompt_values_supported`), the sample's login screen SHALL render a user-facing error explaining that signup is not available on the configured server, and SHALL leave the login form enabled.

#### Scenario: Unsupported-signup error is shown without breaking login

- **WHEN** the sample is configured against an auth server that does not advertise `prompt=create`
- **AND** the user taps "Create account"
- **THEN** the sample displays a "Signup not available on this server" message, and the user can still type a handle and tap "Sign in" successfully
