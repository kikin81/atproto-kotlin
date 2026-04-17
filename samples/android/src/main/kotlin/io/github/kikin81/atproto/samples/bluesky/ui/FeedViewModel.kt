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
import io.github.kikin81.atproto.app.bsky.feed.timelinePageFlow
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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
    private var timelineJob: Job? = null
    private var loadMoreSignal: Channel<Unit>? = null

    init {
        loadTimeline()
    }

    fun onEvent(event: FeedEvent) {
        when (event) {
            FeedEvent.LoadTimeline, FeedEvent.Retry -> loadTimeline()
            FeedEvent.LoadMore -> loadMoreSignal?.trySend(Unit)
            is FeedEvent.ToggleLike -> toggleLike(event.post)
            is FeedEvent.DeletePost -> deletePost(event.post)
        }
    }

    fun onComposeTap() {
        viewModelScope.launch { _navigateToCompose.emit(Unit) }
    }

    private fun loadTimeline() {
        timelineJob?.cancel()
        loadMoreSignal?.close()
        val signal = Channel<Unit>(Channel.CONFLATED)
        loadMoreSignal = signal

        timelineJob = viewModelScope.launch {
            _uiState.value = FeedUiState.Loading
            val c = runCatching {
                val created = oauth.createClient()
                client = created
                currentDid = sessionStore.load()?.did
                created
            }.getOrElse { t ->
                Log.e("FeedViewModel", "Failed to create client", t)
                _uiState.value = FeedUiState.Error(t.message ?: t::class.simpleName.orEmpty())
                return@launch
            }

            FeedService(c).timelinePageFlow(GetTimelineRequest(limit = 25L))
                .catch { t ->
                    Log.e("FeedViewModel", "Timeline flow failed", t)
                    _uiState.value = FeedUiState.Error(t.message ?: t::class.simpleName.orEmpty())
                }
                .collect { page ->
                    val current = (_uiState.value as? FeedUiState.Loaded)?.feed.orEmpty()
                    _uiState.value = FeedUiState.Loaded(current + page)
                    // Gate the next page fetch on a LoadMore signal — upstream
                    // Flow suspends until this collector block returns.
                    signal.receive()
                }
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
