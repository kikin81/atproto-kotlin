package io.github.kikin81.atproto.samples.bluesky

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import io.github.kikin81.atproto.samples.bluesky.ui.ComposeScreen
import io.github.kikin81.atproto.samples.bluesky.ui.FeedScreen
import io.github.kikin81.atproto.samples.bluesky.ui.LoginScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BlueskySampleTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        viewModel = viewModel,
                        launchCustomTab = { url ->
                            CustomTabsIntent.Builder().build()
                                .launchUrl(this@MainActivity, Uri.parse(url))
                        },
                    )
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri = intent.data ?: return
        Log.d(TAG, "handleIntent: $uri")
        if (uri.scheme == REDIRECT_SCHEME) {
            viewModel.onEvent(MainEvent.CompleteOAuthRedirect(uri.toString()))
            // Consume the intent so it's not re-processed on configuration changes
            intent.data = null
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REDIRECT_SCHEME = "io.github.kikin81"
    }
}

@Composable
private fun MainScreen(
    launchCustomTab: (String) -> Unit,
    viewModel: MainViewModel,
) {
    val state by viewModel.uiState.collectAsState()

    // Collect one-shot auth URL events → open Custom Tabs
    LaunchedEffect(Unit) {
        viewModel.authUrl.collect { url -> launchCustomTab(url) }
    }

    when (val current = state) {
        MainUiState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is MainUiState.LoggedOut -> {
            LoginScreen(
                errorMessage = current.error,
                busy = current.busy,
                onLogin = { handle -> viewModel.onEvent(MainEvent.Login(handle)) },
            )
        }
        is MainUiState.LoggedIn -> {
            var showCompose by remember { mutableStateOf(false) }
            if (showCompose) {
                ComposeScreen(
                    onBack = { showCompose = false },
                    onPosted = { showCompose = false },
                )
            } else {
                FeedScreen(
                    handle = current.handle,
                    currentDid = current.did,
                    onLogout = { viewModel.onEvent(MainEvent.Logout) },
                    onCompose = { showCompose = true },
                )
            }
        }
    }
}

@Composable
private fun BlueskySampleTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}
