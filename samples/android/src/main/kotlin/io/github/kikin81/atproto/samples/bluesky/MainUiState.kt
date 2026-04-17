package io.github.kikin81.atproto.samples.bluesky

import io.github.kikin81.atproto.app.bsky.feed.PostView
import io.github.kikin81.atproto.runtime.AtUri

/**
 * MVI state + events for the main screen.
 */
sealed interface MainUiState {
    data object Loading : MainUiState
    data class LoggedOut(
        val error: String? = null,
        val busy: Boolean = false,
    ) : MainUiState
    data class LoggedIn(val handle: String, val did: String? = null) : MainUiState
}

sealed interface MainEvent {
    data class Login(val handle: String) : MainEvent
    data class CompleteOAuthRedirect(val redirectUri: String) : MainEvent
    data object Logout : MainEvent
}

/**
 * Navigation state within the logged-in experience. Replaces the earlier
 * boolean showCompose flag so the activity can carry both a thread URI
 * and an optional reply context without pulling in nav-compose.
 */
sealed interface LoggedInScreen {
    data object Feed : LoggedInScreen
    data class Thread(val rootUri: AtUri) : LoggedInScreen
    data class Compose(val replyTo: PostView? = null) : LoggedInScreen
}
