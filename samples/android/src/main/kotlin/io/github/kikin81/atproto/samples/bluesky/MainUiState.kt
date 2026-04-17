package io.github.kikin81.atproto.samples.bluesky

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
