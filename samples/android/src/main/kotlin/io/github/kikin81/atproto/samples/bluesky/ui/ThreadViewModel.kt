package io.github.kikin81.atproto.samples.bluesky.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kikin81.atproto.app.bsky.feed.BlockedPost
import io.github.kikin81.atproto.app.bsky.feed.FeedService
import io.github.kikin81.atproto.app.bsky.feed.GetPostThreadRequest
import io.github.kikin81.atproto.app.bsky.feed.GetPostThreadResponseThreadUnion
import io.github.kikin81.atproto.app.bsky.feed.NotFoundPost
import io.github.kikin81.atproto.app.bsky.feed.PostView
import io.github.kikin81.atproto.app.bsky.feed.ThreadViewPost
import io.github.kikin81.atproto.app.bsky.feed.ThreadViewPostParentUnion
import io.github.kikin81.atproto.app.bsky.feed.ThreadViewPostRepliesUnion
import io.github.kikin81.atproto.oauth.AtOAuth
import io.github.kikin81.atproto.runtime.AtUri
import io.github.kikin81.atproto.runtime.XrpcClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Rows rendered in the reply list. Happy arms become [Reply] with a full [PostView];
 * non-happy arms become [Placeholder] carrying the pre-localized message.
 */
sealed interface ThreadReplyItem {
    data class Reply(val post: PostView) : ThreadReplyItem
    data class Placeholder(val message: String) : ThreadReplyItem
}

sealed interface ThreadUiState {
    data object Loading : ThreadUiState
    data class Loaded(
        val ancestors: List<PostView>,
        val focused: PostView,
        val replies: List<ThreadReplyItem>,
        val ancestorsTruncated: Boolean,
    ) : ThreadUiState
    data class Unavailable(val message: String) : ThreadUiState
    data class Error(val message: String) : ThreadUiState
}

sealed interface ThreadEvent {
    data object Retry : ThreadEvent
}

@HiltViewModel
class ThreadViewModel @Inject constructor(
    private val oauth: AtOAuth,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ThreadUiState>(ThreadUiState.Loading)
    val uiState: StateFlow<ThreadUiState> = _uiState.asStateFlow()

    private var client: XrpcClient? = null
    private var currentUri: AtUri? = null

    fun loadThread(uri: AtUri) {
        currentUri = uri
        viewModelScope.launch {
            _uiState.value = ThreadUiState.Loading
            val c = runCatching {
                val created = oauth.createClient()
                client = created
                created
            }.getOrElse { t ->
                Log.e(TAG, "Failed to create client", t)
                _uiState.value = ThreadUiState.Error(t.message ?: t::class.simpleName.orEmpty())
                return@launch
            }

            val response = runCatching {
                FeedService(c).getPostThread(GetPostThreadRequest(uri = uri, depth = 6, parentHeight = 4))
            }.getOrElse { t ->
                Log.e(TAG, "getPostThread failed", t)
                _uiState.value = ThreadUiState.Error(t.message ?: t::class.simpleName.orEmpty())
                return@launch
            }

            _uiState.value = foldThread(response.thread)
        }
    }

    fun onEvent(event: ThreadEvent) {
        when (event) {
            ThreadEvent.Retry -> currentUri?.let { loadThread(it) }
        }
    }

    private fun foldThread(root: GetPostThreadResponseThreadUnion): ThreadUiState = when (root) {
        is ThreadViewPost -> {
            val (ancestors, truncated) = collectAncestors(root.parent)
            val replies = root.replies.orEmpty().map(::foldReply)
            ThreadUiState.Loaded(
                ancestors = ancestors,
                focused = root.post,
                replies = replies,
                ancestorsTruncated = truncated,
            )
        }
        is NotFoundPost -> ThreadUiState.Unavailable(MESSAGE_NOT_FOUND)
        is BlockedPost -> ThreadUiState.Unavailable(MESSAGE_BLOCKED)
        is GetPostThreadResponseThreadUnion.Unknown -> ThreadUiState.Unavailable(MESSAGE_UNAVAILABLE)
        else -> ThreadUiState.Unavailable(MESSAGE_UNAVAILABLE)
    }

    /**
     * Walks up [ThreadViewPost.parent] building a root-first list of ancestor posts.
     * Stops at the first non-happy arm; the boolean indicates whether the walk was
     * truncated (caller renders a "Context unavailable" marker).
     */
    private fun collectAncestors(start: ThreadViewPostParentUnion?): Pair<List<PostView>, Boolean> {
        if (start == null) return emptyList<PostView>() to false
        val acc = ArrayDeque<PostView>()
        var current: ThreadViewPostParentUnion? = start
        var truncated = false
        while (current != null) {
            when (current) {
                is ThreadViewPost -> {
                    acc.addFirst(current.post)
                    current = current.parent
                }
                is NotFoundPost, is BlockedPost, is ThreadViewPostParentUnion.Unknown -> {
                    truncated = true
                    current = null
                }
                else -> {
                    truncated = true
                    current = null
                }
            }
        }
        return acc.toList() to truncated
    }

    private fun foldReply(reply: ThreadViewPostRepliesUnion): ThreadReplyItem = when (reply) {
        is ThreadViewPost -> ThreadReplyItem.Reply(reply.post)
        is NotFoundPost -> ThreadReplyItem.Placeholder(MESSAGE_NOT_FOUND)
        is BlockedPost -> ThreadReplyItem.Placeholder(MESSAGE_BLOCKED)
        is ThreadViewPostRepliesUnion.Unknown -> ThreadReplyItem.Placeholder(MESSAGE_UNAVAILABLE)
        else -> ThreadReplyItem.Placeholder(MESSAGE_UNAVAILABLE)
    }

    companion object {
        const val MESSAGE_NOT_FOUND = "This post was deleted."
        const val MESSAGE_BLOCKED = "This post is unavailable."
        const val MESSAGE_UNAVAILABLE = "Thread unavailable."
        private const val TAG = "ThreadViewModel"
    }
}
