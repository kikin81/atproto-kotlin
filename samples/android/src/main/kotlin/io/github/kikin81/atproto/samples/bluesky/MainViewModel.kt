package io.github.kikin81.atproto.samples.bluesky

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kikin81.atproto.oauth.AtOAuth
import io.github.kikin81.atproto.oauth.OAuthSessionStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val oauth: AtOAuth,
    private val sessionStore: OAuthSessionStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // One-shot navigation event: the authorization URL to open in Custom Tabs.
    private val _authUrl = MutableSharedFlow<String>()
    val authUrl: SharedFlow<String> = _authUrl.asSharedFlow()

    init {
        viewModelScope.launch {
            val existing = sessionStore.load()
            _uiState.value = if (existing != null) {
                MainUiState.LoggedIn(existing.handle, existing.did)
            } else {
                MainUiState.LoggedOut()
            }
        }
    }

    fun onEvent(event: MainEvent) {
        when (event) {
            is MainEvent.Login -> login(event.handle)
            is MainEvent.CompleteOAuthRedirect -> completeLogin(event.redirectUri)
            MainEvent.Logout -> logout()
        }
    }

    private fun login(handle: String) {
        viewModelScope.launch {
            _uiState.value = MainUiState.LoggedOut(busy = true)
            runCatching { oauth.beginLogin(handle) }
                .onSuccess { url ->
                    _uiState.value = MainUiState.LoggedOut(busy = false)
                    _authUrl.emit(url)
                }
                .onFailure { t ->
                    Log.e(TAG, "beginLogin failed", t)
                    _uiState.value = MainUiState.LoggedOut(
                        error = t.message ?: "Login failed",
                    )
                }
        }
    }

    private fun completeLogin(redirectUri: String) {
        val currentState = _uiState.value
        if (currentState is MainUiState.LoggedOut && currentState.busy) {
            Log.d(TAG, "completeLogin: already busy, ignoring")
            return
        }

        viewModelScope.launch {
            Log.d(TAG, "completeLogin: starting for $redirectUri")
            _uiState.value = MainUiState.LoggedOut(busy = true)
            runCatching { oauth.completeLogin(redirectUri) }
                .onSuccess {
                    Log.d(TAG, "completeLogin succeeded")
                    val session = sessionStore.load()
                    if (session != null) {
                        Log.d(TAG, "Session loaded: ${session.handle}")
                        _uiState.value = MainUiState.LoggedIn(session.handle, session.did)
                    } else {
                        Log.e(TAG, "Session not found after successful login")
                        _uiState.value = MainUiState.LoggedOut(error = "Session not found after login")
                    }
                }
                .onFailure { t ->
                    Log.e(TAG, "completeLogin failed", t)
                    _uiState.value = MainUiState.LoggedOut(
                        error = t.message ?: "Login completion failed",
                    )
                }
        }
    }

    private fun logout() {
        viewModelScope.launch {
            oauth.logout()
            _uiState.value = MainUiState.LoggedOut()
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
