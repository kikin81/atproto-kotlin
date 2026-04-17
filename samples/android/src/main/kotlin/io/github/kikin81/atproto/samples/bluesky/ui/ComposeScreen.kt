package io.github.kikin81.atproto.samples.bluesky.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.kikin81.atproto.app.bsky.feed.Post
import io.github.kikin81.atproto.app.bsky.feed.PostView
import io.github.kikin81.atproto.runtime.decodeRecord

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
    val replyTo by viewModel.replyTo.collectAsState()
    val charCount = text.codePointCount(0, text.length)

    LaunchedEffect(Unit) {
        viewModel.posted.collect { success ->
            if (success) onPosted()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (replyTo != null) "Reply" else "New Post") },
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
            replyTo?.let {
                ReplyBanner(
                    target = it,
                    onClear = { viewModel.setReplyTo(null) },
                )
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = text,
                onValueChange = { viewModel.onTextChanged(it) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                placeholder = {
                    Text(if (replyTo != null) "Write your reply" else "What's happening?")
                },
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
                    Text(if (replyTo != null) "Reply" else "Post")
                }
            }
        }
    }
}

@Composable
private fun ReplyBanner(
    target: PostView,
    onClear: () -> Unit,
) {
    val record = runCatching { target.record.decodeRecord<Post>() }.getOrNull()
    val preview = record?.text.orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Replying to @${target.author.handle.raw}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                Text("Cancel reply", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (preview.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}
