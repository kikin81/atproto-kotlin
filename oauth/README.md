# AT Protocol OAuth (`oauth`)

AT Protocol OAuth 2.0 module for public clients (mobile and desktop apps).
Implements the full Bluesky/atproto authentication flow: handle resolution,
DPoP proof-of-possession, Pushed Authorization Requests (PAR), PKCE, and
transparent token refresh with DPoP-bound rotation.

## Quick start

```kotlin
val oauth = AtOAuth(
    clientMetadataUrl = "https://your-app.example.com/oauth/client-metadata.json",
    sessionStore = mySessionStore,
    httpClient = HttpClient(CIO),
)

// 1. Start login — resolves handle, runs PAR, returns authorization URL
val authUrl = oauth.beginLogin("alice.bsky.social")

// 2. Open authUrl in a browser (Custom Tabs on Android)
// 3. User authenticates and authorizes your app
// 4. Capture the redirect URI from the browser callback

oauth.completeLogin(redirectUri)

// 5. Use the authenticated client
val client = oauth.createClient()
FeedService(client).getTimeline()
```

## What the flow does

1. **Discovery**: handle -> DID (DNS-over-HTTPS + HTTP fallback) -> PDS ->
   authorization server metadata
2. **PAR + DPoP**: Pushes an authorization request with PKCE (S256) and a
   DPoP proof signed with a fresh EC P-256 keypair. Handles the expected
   `use_dpop_nonce` retry cycle transparently.
3. **Browser authorization**: Returns the authorization URL for the consumer
   to open (Custom Tabs, ASWebAuthenticationSession, system browser).
4. **Token exchange**: Exchanges the authorization code with PKCE verifier +
   DPoP proof. Validates `state` (CSRF), `iss` (mix-up attack), and `sub`
   (account mismatch).
5. **Authenticated requests**: `createClient()` returns an `XrpcClient` with
   a `DpopAuthProvider` that attaches `Authorization: DPoP <token>` +
   `DPoP: <proof>` headers on every request. DPoP nonce rotation and token
   refresh happen transparently.

## Client metadata

AT Protocol OAuth requires a publicly hosted JSON document describing your
app. The authorization server fetches this during PAR to validate your
`client_id`, redirect URIs, and capabilities.

### Required fields

```json
{
  "client_id": "https://your-app.example.com/oauth/client-metadata.json",
  "application_type": "native",
  "client_name": "Your App Name",
  "client_uri": "https://your-app.example.com",
  "tos_uri": "https://your-app.example.com/tos",
  "policy_uri": "https://your-app.example.com/privacy",
  "dpop_bound_access_tokens": true,
  "grant_types": ["authorization_code", "refresh_token"],
  "redirect_uris": ["com.example.yourapp:/oauth-redirect"],
  "response_types": ["code"],
  "scope": "atproto transition:generic",
  "token_endpoint_auth_method": "none"
}
```

### Key rules

- **`client_id`** must be the exact HTTPS URL where the JSON is hosted.
- **`client_uri`**, **`tos_uri`**, and **`policy_uri`** must share the same
  origin as `client_id`.
- **`redirect_uris`** for native apps use a private-use URI scheme with a
  **single slash**: `com.example.yourapp:/path` (not `://`). This follows
  RFC 8252 as enforced by Bluesky's authorization server.
- **`application_type`** must be `"native"` for mobile/desktop apps.
- **`dpop_bound_access_tokens`** must be `true`.
- **`token_endpoint_auth_method`** must be `"none"` (public client).

### Hosting

Host the JSON at any HTTPS URL you control. Common options:

- **GitHub Pages**: Place in your repo's `docs/` directory and enable Pages.
  Example: `https://username.github.io/repo/oauth/client-metadata.json`
- **Static hosting**: Any CDN or static site (Cloudflare Pages, Netlify, S3).
- **Your app's domain**: `https://app.example.com/.well-known/oauth-client-metadata.json`

The file must be served with `Content-Type: application/json` and be publicly
accessible (no authentication).

## Redirect URI configuration

### Android

In your `AndroidManifest.xml`, add an intent filter on the Activity that
handles the redirect:

```xml
<activity
    android:name=".MainActivity"
    android:launchMode="singleTask">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="com.example.yourapp" />
    </intent-filter>
</activity>
```

Use `singleTask` launch mode so the redirect re-delivers to the existing
Activity instead of creating a new one.

### Capturing the redirect

```kotlin
// In your Activity
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val uri = intent.data?.toString() ?: return
    // Dispatch to your ViewModel / OAuth handler
    viewModel.completeLogin(uri)
}
```

## Session persistence

Implement `OAuthSessionStore` to persist the session across app restarts:

```kotlin
interface OAuthSessionStore {
    suspend fun load(): OAuthSession?
    suspend fun save(session: OAuthSession)
    suspend fun clear()
}
```

On Android, use `EncryptedSharedPreferences` to protect the DPoP keypair and
tokens at rest. See the sample app for a complete implementation.

## Security considerations

- **DPoP (RFC 9449)**: Every request carries a proof-of-possession JWT signed
  with the client's ephemeral EC P-256 key, binding the access token to the
  specific client instance. Stolen tokens cannot be replayed without the
  private key.
- **PKCE (RFC 7636)**: S256 challenge prevents authorization code interception.
- **Private-use URI schemes**: Custom schemes (`com.example.app:/path`) can be
  claimed by any app on the device. On Android, consider using App Links
  (verified HTTPS redirects) for stronger redirect security in production.
- **Clock calibration**: The DPoP signer calibrates its clock from the server's
  `Date` header to prevent `iat` claim rejection on devices with clock drift.
- **Nonce tracking**: PDS and authorization server nonces are tracked
  independently and rotated transparently.

## Module structure

```
oauth/src/main/kotlin/io/github/kikin81/atproto/oauth/
├── AtOAuth.kt              # Flow orchestrator: beginLogin, completeLogin, createClient
├── DiscoveryChain.kt       # handle -> DID -> PDS -> auth server resolution
├── DpopSigner.kt           # EC P-256 DPoP JWT signer (java.security, no Nimbus)
├── DpopAuthProvider.kt     # AuthProvider impl: DPoP headers + transparent refresh
├── OAuthSession.kt         # Serializable session + OAuthSessionStore interface
├── Pkce.kt                 # PKCE S256 verifier + challenge generation
└── OAuthExceptions.kt      # OAuthException, OAuthSessionExpiredException, etc.
```

## Dependencies

This module depends on:
- `:runtime` (for `AuthProvider`, `XrpcClient`)
- Ktor client (HTTP requests)
- kotlinx-serialization (JSON parsing)
- `java.security` (EC P-256 key generation and signing — no external JWT library)
