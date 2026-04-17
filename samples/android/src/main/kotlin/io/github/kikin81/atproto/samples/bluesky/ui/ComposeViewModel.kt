package io.github.kikin81.atproto.samples.bluesky.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kikin81.atproto.app.bsky.feed.Post
import io.github.kikin81.atproto.com.atproto.repo.CreateRecordRequest
import io.github.kikin81.atproto.com.atproto.repo.RepoService
import io.github.kikin81.atproto.oauth.AtOAuth
import io.github.kikin81.atproto.oauth.OAuthSessionStore
import io.github.kikin81.atproto.runtime.AtIdentifier
import io.github.kikin81.atproto.runtime.Nsid
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

@HiltViewModel
class ComposeViewModel @Inject constructor(
    private val oauth: AtOAuth,
    private val sessionStore: OAuthSessionStore,
) : ViewModel() {

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _posting = MutableStateFlow(false)
    val posting: StateFlow<Boolean> = _posting.asStateFlow()

    private val _posted = MutableSharedFlow<Boolean>()
    val posted: SharedFlow<Boolean> = _posted.asSharedFlow()

    fun onTextChanged(value: String) {
        _text.value = value
    }

    fun createPost() {
        val content = _text.value.trim()
        if (content.isEmpty()) return

        viewModelScope.launch {
            _posting.value = true
            runCatching {
                val client = oauth.createClient()
                val did = sessionStore.load()?.did ?: error("No session")
                val post = Post(
                    text = content,
                    createdAt = datetimeNow(),
                )
                RepoService(client).createRecord(
                    CreateRecordRequest(
                        repo = AtIdentifier(did),
                        collection = Nsid("app.bsky.feed.post"),
                        record = encodeRecord(Post.serializer(), post, "app.bsky.feed.post"),
                    ),
                )
            }.onSuccess {
                _posting.value = false
                _posted.emit(true)
            }.onFailure { t ->
                Log.e("ComposeViewModel", "Failed to create post", t)
                _posting.value = false
                _posted.emit(false)
            }
        }
    }
}
