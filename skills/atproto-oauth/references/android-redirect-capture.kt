// Minimal MainActivity showing OAuth redirect capture.
// Key points:
//   1. launchMode="singleTask" in AndroidManifest.xml
//   2. Both onCreate(intent) and onNewIntent(intent) route to handleIntent
//   3. Null out intent.data after handling so rotation doesn't re-fire

package com.example.yourapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.LaunchedEffect
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppUi(
                viewModel = viewModel,
                launchCustomTab = { url ->
                    CustomTabsIntent.Builder().build()
                        .launchUrl(this@MainActivity, Uri.parse(url))
                },
            )
        }
        handleIntent(intent)  // cold-start redirect (rare, but possible)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme == REDIRECT_SCHEME) {
            viewModel.onEvent(MainEvent.CompleteOAuthRedirect(uri.toString()))
            // Consume so configuration changes (rotation, dark-mode) don't
            // re-deliver the redirect and run completeLogin twice.
            intent.data = null
        }
    }

    companion object {
        private const val REDIRECT_SCHEME = "com.example.yourapp"
    }
}
