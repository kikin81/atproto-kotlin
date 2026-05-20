## ADDED Requirements

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
