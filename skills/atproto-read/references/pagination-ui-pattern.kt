// Canonical infinite-scroll ViewModel with *PageFlow + load-more gating.
// Adapted from samples/android FeedViewModel. Key properties:
//   - One state update per PAGE (not per item)
//   - Next page only fetched when UI signals LoadMore
//   - Cancellation stops fetching cleanly
//   - Errors surface via Flow.catch

package com.example.yourapp.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kikin81.atproto.app.bsky.feed.FeedService
import io.github.kikin81.atproto.app.bsky.feed.FeedViewPost
import io.github.kikin81.atproto.app.bsky.feed.GetTimelineRequest
import io.github.kikin81.atproto.app.bsky.feed.timelinePageFlow
import io.github.kikin81.atproto.oauth.AtOAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

sealed interface FeedUiState {
    data object Loading : FeedUiState
    data class Loaded(val feed: List<FeedViewPost>) : FeedUiState
    data class Error(val message: String) : FeedUiState
}

class FeedViewModel(private val oauth: AtOAuth) : ViewModel() {

    private val _uiState = MutableStateFlow<FeedUiState>(FeedUiState.Loading)
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    private var timelineJob: Job? = null
    private var loadMoreSignal: Channel<Unit>? = null

    init { loadTimeline() }

    fun onLoadMore() {
        loadMoreSignal?.trySend(Unit)
    }

    fun onRetry() = loadTimeline()

    private fun loadTimeline() {
        timelineJob?.cancel()
        loadMoreSignal?.close()
        val signal = Channel<Unit>(Channel.CONFLATED)
        loadMoreSignal = signal

        timelineJob = viewModelScope.launch {
            _uiState.value = FeedUiState.Loading
            val client = runCatching { oauth.createClient() }
                .getOrElse { t ->
                    _uiState.value = FeedUiState.Error(t.message.orEmpty())
                    return@launch
                }

            FeedService(client)
                .timelinePageFlow(GetTimelineRequest(limit = 25L))
                .catch { t ->
                    _uiState.value = FeedUiState.Error(t.message.orEmpty())
                }
                .collect { page ->
                    val current = (_uiState.value as? FeedUiState.Loaded)?.feed.orEmpty()
                    _uiState.value = FeedUiState.Loaded(current + page)
                    // Gate next-page fetch on a LoadMore signal from UI.
                    // Upstream *PageFlow suspends here — pages are lazy.
                    signal.receive()
                }
        }
    }
}

// In your Compose screen:
//
//   val listState = rememberLazyListState()
//   val endReached by remember {
//       derivedStateOf {
//           val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
//           val total = listState.layoutInfo.totalItemsCount
//           last >= total - 5  // trigger when 5 items from bottom
//       }
//   }
//   LaunchedEffect(endReached) {
//       if (endReached) viewModel.onLoadMore()
//   }
