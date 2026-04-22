---
name: atproto-oauth
description: >
  Use this skill when integrating AT Protocol OAuth 2.0 login into an
  Android app consuming the kikin81/atproto-kotlin library. Covers
  hosting the client-metadata JSON, configuring the Android intent
  filter for the redirect URI, implementing an encrypted
  OAuthSessionStore, constructing the AtOAuth flow orchestrator, and
  driving beginLogin / completeLogin / createClient / logout. DPoP
  proof-of-possession and token refresh are handled transparently by
  the library. Use this skill after atproto-setup and before any
  atproto-read or atproto-write-records work.
license: MIT (see repo LICENSE)
metadata:
  author: kikin81
  library-version: "5.0.0"
  keywords:
    - AT Protocol
    - Bluesky
    - OAuth
    - DPoP
    - PKCE
    - Android
    - authentication
    - Custom Tabs
    - EncryptedSharedPreferences
---

## Objective

Authenticate a Bluesky user with OAuth 2.0 (PAR + PKCE + DPoP) and
obtain a `DpopAuthProvider`-backed `XrpcClient` that all subsequent
ATProto calls use.

## Prerequisites

- Consumer project set up per `atproto-setup` (runtime + models + oauth)
- Android host Activity you control
- A publicly hosted HTTPS URL where you can serve a JSON file

## Flow at a glance

```
User enters handle (alice.bsky.social)
        │
        ▼
  beginLogin(handle) ──► resolves handle→DID→PDS→auth-server
        │                        runs PAR with PKCE + DPoP
        ▼
  returns authorizationUrl  ──► launch in Chrome Custom Tabs
        │
        ▼
  User authenticates on Bluesky's server, authorizes app
        │
        ▼
  Browser redirects to com.example.yourapp:/oauth-redirect
        │
        ▼
  Android re-delivers to Activity.onNewIntent
        │
        ▼
  completeLogin(redirectUri) ──► exchanges code, validates state/iss/sub,
        │                       persists OAuthSession via your SessionStore
        ▼
  createClient() ──► XrpcClient with DpopAuthProvider
```

## Step 1: Host client-metadata.json

AT Protocol OAuth requires a public JSON document describing your app.
The authorization server fetches this during PAR to validate your
`client_id`.

See `references/client-metadata-template.json` for a ready-to-fill
template. Key rules:

- `client_id` **must** be the exact HTTPS URL where the JSON is hosted.
- `client_uri`, `tos_uri`, `policy_uri` must share the same origin as
  `client_id`.
- `redirect_uris` for native apps use a **single slash** after the
  scheme: `com.example.yourapp:/oauth-redirect` (not `://`). RFC 8252
  per Bluesky's authorization server.
- `application_type` must be `"native"`.
- `dpop_bound_access_tokens` must be `true`.
- `token_endpoint_auth_method` must be `"none"` (public client).

**Hosting options:**
- GitHub Pages (`docs/oauth/client-metadata.json` with Pages enabled)
- Cloudflare Pages, Netlify, S3, or any static HTTPS host
- Your app's own domain at `/.well-known/oauth-client-metadata.json`

Must be served with `Content-Type: application/json` and publicly
reachable.

## Step 2: Configure the Android redirect

### AndroidManifest.xml

```xml
<activity
    android:name=".MainActivity"
    android:launchMode="singleTask"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="com.example.yourapp" />
    </intent-filter>
    <!-- existing LAUNCHER intent-filter stays untouched -->
</activity>
```

`singleTask` is required — redirect re-delivers to the existing Activity
instance instead of spinning up a new one (which would lose your
`AtOAuth` in-memory flow state).

### Capture the redirect

See `references/android-redirect-capture.kt` for the full `onCreate` +
`onNewIntent` pattern. Minimal shape:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    val uri = intent.data ?: return
    if (uri.scheme == "com.example.yourapp") {
        viewModel.onEvent(MainEvent.CompleteOAuthRedirect(uri.toString()))
        intent.data = null  // consume so it doesn't re-fire on rotation
    }
}
```

## Step 3: Implement `OAuthSessionStore`

The session contains access + refresh tokens **and** the DPoP private
key. Use `EncryptedSharedPreferences` — anything less leaks credentials.

```kotlin
class AndroidOAuthSessionStore(context: Context) : OAuthSessionStore {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "atproto-oauth-session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): OAuthSession? =
        prefs.getString(KEY, null)?.let {
            runCatching { json.decodeFromString<OAuthSession>(it) }.getOrNull()
        }

    override suspend fun save(session: OAuthSession) {
        prefs.edit().putString(KEY, json.encodeToString(session)).apply()
    }

    override suspend fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object { const val KEY = "session" }
}
```

## Step 4: Instantiate `AtOAuth` (Hilt DI recommended)

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object OAuthModule {
    @Provides @Singleton
    fun provideHttpClient(): HttpClient = HttpClient(CIO)

    @Provides @Singleton
    fun provideSessionStore(@ApplicationContext ctx: Context): OAuthSessionStore =
        AndroidOAuthSessionStore(ctx)

    @Provides @Singleton
    fun provideAtOAuth(
        sessionStore: OAuthSessionStore,
        httpClient: HttpClient,
    ): AtOAuth = AtOAuth(
        clientMetadataUrl = "https://your-app.example.com/oauth/client-metadata.json",
        sessionStore = sessionStore,
        httpClient = httpClient,
    )
}
```

## Step 5: Drive the flow from your ViewModel

```kotlin
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val oauth: AtOAuth,
) : ViewModel() {

    private val _authUrl = MutableSharedFlow<String>()
    val authUrl: SharedFlow<String> = _authUrl.asSharedFlow()

    fun login(handle: String) {
        viewModelScope.launch {
            runCatching { oauth.beginLogin(handle) }
                .onSuccess { url -> _authUrl.emit(url) }
                .onFailure { /* surface to UI */ }
        }
    }

    fun completeLogin(redirectUri: String) {
        viewModelScope.launch {
            runCatching { oauth.completeLogin(redirectUri) }
                .onSuccess { /* navigate to logged-in state */ }
                .onFailure { /* session not created, surface error */ }
        }
    }
}
```

Activity collects `authUrl` and launches Custom Tabs:

```kotlin
LaunchedEffect(Unit) {
    viewModel.authUrl.collect { url ->
        CustomTabsIntent.Builder().build()
            .launchUrl(activity, Uri.parse(url))
    }
}
```

## Step 6: Use the authenticated client

Once `completeLogin` succeeds:

```kotlin
val client: XrpcClient = oauth.createClient()
val timeline = FeedService(client).getTimeline(GetTimelineRequest(limit = 25L))
```

`createClient()` attaches a `DpopAuthProvider` that injects
`Authorization: DPoP <token>` + `DPoP: <proof>` headers, handles DPoP
nonce rotation on 401, and refreshes tokens transparently when they
expire.

## Session restore on app launch

On startup, check for an existing session before showing login:

```kotlin
init {
    viewModelScope.launch {
        val existing = sessionStore.load()
        _uiState.value = if (existing != null) {
            MainUiState.LoggedIn(existing.handle, existing.did)
        } else {
            MainUiState.LoggedOut
        }
    }
}
```

If a session exists, `oauth.createClient()` works immediately — no
re-login needed.

## Logout

```kotlin
oauth.logout()  // clears the session via SessionStore.clear()
```

After logout, discard any cached `XrpcClient` — it's bound to the old
DPoP key. Call `createClient()` fresh after the next login.

## Common pitfalls

- **Redirect URI with `://` instead of `:/`.** `com.example.app://path`
  is rejected by Bluesky's auth server. Use `com.example.app:/path`.
- **Forgetting `singleTask`.** Without it, the redirect spawns a new
  Activity instance and your ViewModel loses its in-flight OAuth state.
- **Storing the session in plain `SharedPreferences`.** The session
  contains the DPoP private key. Always use `EncryptedSharedPreferences`.
- **Reusing an `XrpcClient` across logout/login.** The old client's
  DPoP key was cleared with the session. Call `createClient()` again
  after `completeLogin`.
- **`client_id` mismatch.** The URL in `client_id` must match exactly
  where the JSON is hosted — including `https://`, subdomain, path,
  and no trailing slash differences.
- **`HttpClient` without an engine.** Declare
  `io.ktor:ktor-client-cio` (or another engine) in your `build.gradle.kts`.
- **Not consuming `intent.data`.** If you don't null it after handling
  the redirect, configuration changes (rotation) re-fire the redirect
  capture and `completeLogin` runs twice.

## Related skills

- `atproto-setup` — Gradle prerequisites
- `atproto-read` — using the client to read feeds after login
- `atproto-write-records` — using the client to create posts, likes

## Reference: working sample

Full working Android implementation with Hilt, Compose UI, and
EncryptedSharedPreferences:

- https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/samples/android/src/main/kotlin/io/github/kikin81/atproto/samples/bluesky/MainActivity.kt
- https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/samples/android/src/main/kotlin/io/github/kikin81/atproto/samples/bluesky/MainViewModel.kt
- https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/samples/android/src/main/kotlin/io/github/kikin81/atproto/samples/bluesky/session/AndroidOAuthSessionStore.kt
- https://raw.githubusercontent.com/kikin81/atproto-kotlin/main/samples/android/src/main/kotlin/io/github/kikin81/atproto/samples/bluesky/di/AppModule.kt
