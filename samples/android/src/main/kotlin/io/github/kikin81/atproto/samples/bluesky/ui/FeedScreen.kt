package io.github.kikin81.atproto.samples.bluesky.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import io.github.kikin81.atproto.app.bsky.embed.ImagesView
import io.github.kikin81.atproto.app.bsky.embed.RecordView
import io.github.kikin81.atproto.app.bsky.embed.RecordViewBlocked
import io.github.kikin81.atproto.app.bsky.embed.RecordViewDetached
import io.github.kikin81.atproto.app.bsky.embed.RecordViewNotFound
import io.github.kikin81.atproto.app.bsky.embed.RecordViewRecord
import io.github.kikin81.atproto.app.bsky.embed.RecordViewRecordEmbedsUnion
import io.github.kikin81.atproto.app.bsky.embed.RecordViewRecordUnion
import io.github.kikin81.atproto.app.bsky.embed.RecordWithMediaView
import io.github.kikin81.atproto.app.bsky.feed.FeedViewPost
import io.github.kikin81.atproto.app.bsky.feed.FeedViewPostReasonUnion
import io.github.kikin81.atproto.app.bsky.feed.Post
import io.github.kikin81.atproto.app.bsky.feed.PostView
import io.github.kikin81.atproto.app.bsky.feed.ReasonRepost
import io.github.kikin81.atproto.runtime.Datetime
import io.github.kikin81.atproto.runtime.decodeRecord
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    handle: String,
    currentDid: String?,
    onLogout: () -> Unit,
    onCompose: () -> Unit,
    viewModel: FeedViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("@$handle") },
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCompose) {
                Icon(Icons.Filled.Add, contentDescription = "New post")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                FeedUiState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is FeedUiState.Error -> {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Failed to load timeline", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(s.message, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.onEvent(FeedEvent.Retry) }) { Text("Retry") }
                    }
                }
                is FeedUiState.Loaded -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(s.feed) { entry ->
                            PostRow(
                                entry = entry,
                                isOwnPost = entry.post.author.did.raw == currentDid,
                                onLikeToggle = { viewModel.onEvent(FeedEvent.ToggleLike(entry.post)) },
                                onDelete = { viewModel.onEvent(FeedEvent.DeletePost(entry.post)) },
                            )
                            HorizontalDivider()
                        }
                        item {
                            LaunchedEffect(Unit) {
                                viewModel.onEvent(FeedEvent.LoadMore)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PostRow(
    entry: FeedViewPost,
    isOwnPost: Boolean,
    onLikeToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val post = entry.post
    val record = runCatching { post.record.decodeRecord<Post>() }.getOrNull()
    val text = record?.text.orEmpty()
    val createdAt = record?.createdAt ?: post.indexedAt
    val thumbUrl = extractFirstImageThumb(post)
    val quotedRecord = extractQuotedRecord(post)
    val quotedPlaceholder = if (quotedRecord == null) extractQuotedPlaceholder(post) else null
    val isLiked = post.viewer?.like != null
    val likeCount = post.likeCount ?: 0

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        RepostHeader(entry.reason)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "@${post.author.handle.raw}",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                formatDatetime(createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (text.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
        if (quotedRecord != null) {
            QuotedRecordCard(quotedRecord)
        } else if (quotedPlaceholder != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                quotedPlaceholder,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (thumbUrl != null) {
            Spacer(Modifier.height(8.dp))
            AsyncImage(
                model = thumbUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(192.dp).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onLikeToggle, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isLiked) "Unlike" else "Like",
                    tint = if (isLiked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (likeCount > 0) {
                Text(
                    "$likeCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isOwnPost) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete post",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

internal fun extractFirstImageThumb(post: PostView): String? = when (val embed = post.embed) {
    is ImagesView -> embed.images.firstOrNull()?.thumb?.raw
    is RecordWithMediaView -> (embed.media as? ImagesView)?.images?.firstOrNull()?.thumb?.raw
    else -> null
}

internal fun extractQuotedRecord(post: PostView): RecordViewRecord? = when (val embed = post.embed) {
    is RecordView -> embed.record as? RecordViewRecord
    is RecordWithMediaView -> embed.record.record as? RecordViewRecord
    else -> null
}

internal fun extractQuotedPlaceholder(post: PostView): String? = when (val embed = post.embed) {
    is RecordView -> placeholderFor(embed.record)
    is RecordWithMediaView -> placeholderFor(embed.record.record)
    else -> null
}

private fun placeholderFor(union: RecordViewRecordUnion): String? = when (union) {
    is RecordViewNotFound -> "Quoted post not found"
    is RecordViewBlocked -> "Quoted post from a blocked account"
    is RecordViewDetached -> "Quoted post was detached by its author"
    is RecordViewRecordUnion.Unknown -> "Quoted post unavailable"
    is RecordViewRecord -> null
    else -> null
}

private fun extractFirstImageThumbFromEmbeds(embeds: List<RecordViewRecordEmbedsUnion>?): String? {
    embeds ?: return null
    for (e in embeds) {
        when (e) {
            is ImagesView -> return e.images.firstOrNull()?.thumb?.raw
            is RecordWithMediaView -> (e.media as? ImagesView)?.images?.firstOrNull()?.thumb?.raw?.let { return it }
            else -> Unit
        }
    }
    return null
}

@Composable
private fun RepostHeader(reason: FeedViewPostReasonUnion?) {
    val repost = reason as? ReasonRepost ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Repeat,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            "Reposted by @${repost.by.handle.raw}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun QuotedRecordCard(record: RecordViewRecord) {
    val quoted = runCatching { record.value.decodeRecord<Post>() }.getOrNull()
    val quotedText = quoted?.text.orEmpty()
    val quotedThumb = extractFirstImageThumbFromEmbeds(record.embeds)

    Spacer(Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            "@${record.author.handle.raw}",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall,
        )
        if (quotedText.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(quotedText, style = MaterialTheme.typography.bodyMedium)
        }
        if (quotedThumb != null) {
            Spacer(Modifier.height(8.dp))
            AsyncImage(
                model = quotedThumb,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private val datetimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, h:mm a")

internal fun formatDatetime(datetime: Datetime): String = try {
    OffsetDateTime.parse(datetime.raw).format(datetimeFormatter)
} catch (e: DateTimeParseException) {
    datetime.raw
}
