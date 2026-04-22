# Bluesky Sample (Android)

Minimal Compose app that authenticates via **AT Protocol OAuth 2.0** (PAR +
PKCE + DPoP) and renders a timeline from `app.bsky.feed.getTimeline`. Dogfoods
the code-generated AT Protocol API surface (`:runtime` +
`:models` + `:oauth`) against Bluesky's production servers.

## What it does

One-Activity Compose app with Hilt DI and two screens:

1. **Login** -- enter your Bluesky handle, tap Sign In, authenticate in a
   Chrome Custom Tab, and get redirected back to the app.
2. **Feed** -- calls `app.bsky.feed.getTimeline` with DPoP-authenticated
   XRPC requests and renders a `LazyColumn` of posts showing author handle,
   post text, `createdAt`, and image thumbnails from `ImagesView` embeds.

## Running it

### Prerequisites

- A local Android SDK with at least API 36 installed.
- A Bluesky account (any handle -- no app password needed, OAuth handles auth).
- The generator's lexicon corpus installed:

  ```bash
  cd generator && npx lex install --ci && cd -
  ```

### Build and install

```bash
./gradlew :samples:android:installDebug
```

Launch **Bluesky Sample** from your device or emulator's app drawer. On the
login screen, enter your handle (e.g. `alice.bsky.social`) and tap **Sign In**.
A Chrome Custom Tab opens Bluesky's authorization page. After you approve,
the browser redirects back and the feed loads.

Tap the log-out icon in the top-right to clear the stored session.

## OAuth flow

The sample uses `:oauth` for the full AT Protocol OAuth 2.0 flow:

1. `AtOAuth.beginLogin(handle)` -- resolves handle via DNS-over-HTTPS,
   discovers the PDS and authorization server, sends a PAR request with
   PKCE + DPoP
2. Authorization URL opens in a Chrome Custom Tab
3. User authenticates on Bluesky's server and authorizes the app
4. Redirect captured via intent filter on `io.github.kikin81` scheme
5. `AtOAuth.completeLogin(redirectUri)` -- exchanges the code for
   DPoP-bound tokens, persists the session
6. `AtOAuth.createClient()` -- returns an `XrpcClient` with `DpopAuthProvider`
   that handles nonce rotation and token refresh transparently

### Client metadata

The OAuth client metadata is hosted on GitHub Pages:
`https://kikin81.github.io/atproto-kotlin/oauth/client-metadata.json`

See [`oauth/README.md`](../../oauth/README.md) for
client metadata field requirements and hosting instructions.

## Unit tests

```bash
./gradlew :samples:android:testDebugUnitTest
```

## Architecture

```
samples/android/src/main/kotlin/io/github/kikin81/atproto/samples/bluesky/
├── SampleApp.kt               # @HiltAndroidApp Application
├── MainActivity.kt            # Single Activity, singleTask, redirect capture
├── MainViewModel.kt           # @HiltViewModel: login/logout, auth URL events
├── MainUiState.kt             # Sealed: Loading | LoggedOut | LoggedIn
├── di/
│   └── AppModule.kt           # Hilt @Module: HttpClient, AtOAuth, SessionStore
├── session/
│   └── AndroidOAuthSessionStore.kt  # EncryptedSharedPreferences-backed store
└── ui/
    ├── LoginScreen.kt         # Handle input + Sign In button
    ├── FeedScreen.kt          # LazyColumn feed + embed dispatch
    └── FeedViewModel.kt       # @HiltViewModel: timeline loading + error handling
```

Hilt provides `AtOAuth`, `HttpClient(CIO)`, and `OAuthSessionStore` as
singletons. ViewModels use `viewModelScope` for all coroutines. State flows
through `StateFlow<MainUiState>` (login state) and `StateFlow<FeedUiState>`
(feed data). Auth URL events use `SharedFlow<String>` for one-shot delivery.

## Known limitations

- **No pagination / no pull-to-refresh.** First 50 posts, once per session.
- **Read-only.** No posting, liking, following, or notifications.
- **Single-account.** One session slot; no account switching.
- **Blob rendering.** Only image thumbnails on `ImagesView` embeds are
  rendered -- record quotes, external links, video, and record-with-media
  embeds render the post text without a preview.
