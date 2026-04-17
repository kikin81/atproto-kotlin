package io.github.kikin81.atproto.samples.bluesky.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private const val MAX_GRAPHEMES = 300

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    onBack: () -> Unit,
    onPosted: () -> Unit,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    val text by viewModel.text.collectAsState()
    val posting by viewModel.posting.collectAsState()
    val charCount = text.codePointCount(0, text.length)

    LaunchedEffect(Unit) {
        viewModel.posted.collect { success ->
            if (success) onPosted()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Post") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { viewModel.onTextChanged(it) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = { Text("What's happening?") },
                enabled = !posting,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "$charCount / $MAX_GRAPHEMES",
                style = MaterialTheme.typography.bodySmall,
                color = if (charCount > MAX_GRAPHEMES) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.createPost() },
                modifier = Modifier.fillMaxWidth(),
                enabled = text.isNotBlank() && charCount <= MAX_GRAPHEMES && !posting,
            ) {
                if (posting) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                } else {
                    Text("Post")
                }
            }
        }
    }
}
