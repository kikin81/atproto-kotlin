package io.github.kikin81.atproto.samples.bluesky.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kikin81.atproto.app.bsky.feed.FeedService
import io.github.kikin81.atproto.app.bsky.feed.FeedViewPost
import io.github.kikin81.atproto.app.bsky.feed.GetTimelineRequest
import io.github.kikin81.atproto.app.bsky.feed.Like
import io.github.kikin81.atproto.app.bsky.feed.PostView
import io.github.kikin81.atproto.app.bsky.feed.ViewerState
import io.github.kikin81.atproto.com.atproto.repo.CreateRecordRequest
import io.github.kikin81.atproto.com.atproto.repo.DeleteRecordRequest
import io.github.kikin81.atproto.com.atproto.repo.RepoService
import io.github.kikin81.atproto.com.atproto.repo.StrongRef
import io.github.kikin81.atproto.oauth.AtOAuth
import io.github.kikin81.atproto.oauth.OAuthSessionStore
import io.github.kikin81.atproto.runtime.AtIdentifier
import io.github.kikin81.atproto.runtime.AtUri
import io.github.kikin81.atproto.runtime.Nsid
import io.github.kikin81.atproto.runtime.RecordKey
import io.github.kikin81.atproto.runtime.XrpcClient
import io.github.kikin81.atproto.runtime.encodeRecord
import io.github.kikin81.atproto.samples.bluesky.util.datetimeNow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface FeedUiState {
    data object Loading : FeedUiState
    data class Loaded(val feed: List<FeedViewPost>) : FeedUiState
    data class Error(val message: String) : FeedUiState
}

sealed interface FeedEvent {
    data object LoadTimeline : FeedEvent
    data object Retry : FeedEvent
    data object LoadMore : FeedEvent
    data class ToggleLike(val post: PostView) : FeedEvent
    data class DeletePost(val post: PostView) : FeedEvent
}

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val oauth: AtOAuth,
    private val sessionStore: OAuthSessionStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow<FeedUiState>(FeedUiState.Loading)
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private val _navigateToCompose = MutableSharedFlow<Unit>()
    val navigateToCompose: SharedFlow<Unit> = _navigateToCompose.asSharedFlow()

    private var client: XrpcClient? = null
    private var currentDid: String? = null
    private var nextCursor: String? = null
    private var loadingMore = false
    private var endOfFeed = false

    init {
        loadTimeline()
    }

    fun onEvent(event: FeedEvent) {
        when (event) {
            FeedEvent.LoadTimeline, FeedEvent.Retry -> loadTimeline()
            FeedEvent.LoadMore -> loadMore()
            is FeedEvent.ToggleLike -> toggleLike(event.post)
            is FeedEvent.DeletePost -> deletePost(event.post)
        }
    }

    fun onComposeTap() {
        viewModelScope.launch { _navigateToCompose.emit(Unit) }
    }

    private fun loadTimeline() {
        viewModelScope.launch {
            _uiState.value = FeedUiState.Loading
            nextCursor = null
            endOfFeed = false
            runCatching {
                val c = oauth.createClient()
                client = c
                currentDid = sessionStore.load()?.did
                FeedService(c).getTimeline(GetTimelineRequest(limit = 25L))
            }.onSuccess { response ->
                nextCursor = response.cursor
                endOfFeed = response.cursor == null
                _uiState.value = FeedUiState.Loaded(response.feed)
            }.onFailure { t ->
                Log.e("FeedViewModel", "Failed to load timeline", t)
                _uiState.value = FeedUiState.Error(t.message ?: t::class.simpleName.orEmpty())
            }
        }
    }

    private fun loadMore() {
        if (loadingMore || endOfFeed) return
        val c = client ?: return
        val cursor = nextCursor ?: return

        loadingMore = true
        viewModelScope.launch {
            runCatching {
                FeedService(c).getTimeline(GetTimelineRequest(limit = 25L, cursor = cursor))
            }.onSuccess { response ->
                nextCursor = response.cursor
                endOfFeed = response.cursor == null
                val state = _uiState.value
                if (state is FeedUiState.Loaded) {
                    _uiState.value = state.copy(feed = state.feed + response.feed)
                }
            }.onFailure { t ->
                Log.e("FeedViewModel", "Failed to load more", t)
            }
            loadingMore = false
        }
    }

    private fun toggleLike(post: PostView) {
        val c = client ?: return
        val did = currentDid ?: return
        val isLiked = post.viewer?.like != null

        updatePost(post.uri) { entry ->
            val oldViewer = entry.post.viewer ?: ViewerState()
            val newCount = (entry.post.likeCount ?: 0) + if (isLiked) -1 else 1
            val newViewer = if (isLiked) oldViewer.copy(like = null) else oldViewer.copy(like = AtUri("pending"))
            entry.copy(post = entry.post.copy(viewer = newViewer, likeCount = newCount.coerceAtLeast(0)))
        }

        viewModelScope.launch {
            runCatching {
                val repo = RepoService(c)
                if (isLiked) {
                    val rkey = post.viewer?.like?.raw?.substringAfterLast('/') ?: return@launch
                    repo.deleteRecord(
                        DeleteRecordRequest(
                            repo = AtIdentifier(did),
                            collection = Nsid("app.bsky.feed.like"),
                            rkey = RecordKey(rkey),
                        ),
                    )
                } else {
                    val like = Like(
                        subject = StrongRef(uri = post.uri, cid = post.cid),
                        createdAt = datetimeNow(),
                    )
                    repo.createRecord(
                        CreateRecordRequest(
                            repo = AtIdentifier(did),
                            collection = Nsid("app.bsky.feed.like"),
                            record = encodeRecord(Like.serializer(), like, "app.bsky.feed.like"),
                        ),
                    )
                }
            }.onFailure { t ->
                Log.e("FeedViewModel", "Like/unlike failed, reverting", t)
                updatePost(post.uri) { entry ->
                    val oldViewer = entry.post.viewer ?: ViewerState()
                    val revertCount = (entry.post.likeCount ?: 0) + if (isLiked) 1 else -1
                    val revertViewer = if (isLiked) oldViewer.copy(like = post.viewer?.like) else oldViewer.copy(like = null)
                    entry.copy(post = entry.post.copy(viewer = revertViewer, likeCount = revertCount.coerceAtLeast(0)))
                }
            }
        }
    }

    private fun deletePost(post: PostView) {
        val c = client ?: return
        val did = currentDid ?: return
        val rkey = post.uri.raw.substringAfterLast('/')

        viewModelScope.launch {
            runCatching {
                RepoService(c).deleteRecord(
                    DeleteRecordRequest(
                        repo = AtIdentifier(did),
                        collection = Nsid("app.bsky.feed.post"),
                        rkey = RecordKey(rkey),
                    ),
                )
            }.onSuccess {
                val state = _uiState.value
                if (state is FeedUiState.Loaded) {
                    _uiState.value = state.copy(feed = state.feed.filter { it.post.uri != post.uri })
                }
            }.onFailure { t ->
                Log.e("FeedViewModel", "Delete failed", t)
            }
        }
    }

    private fun updatePost(uri: AtUri, transform: (FeedViewPost) -> FeedViewPost) {
        val state = _uiState.value
        if (state is FeedUiState.Loaded) {
            _uiState.value = state.copy(
                feed = state.feed.map { entry ->
                    if (entry.post.uri == uri) transform(entry) else entry
                },
            )
        }
    }
}
