package io.github.kikin81.atproto.samples.bluesky.session

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.kikin81.atproto.oauth.OAuthSession
import io.github.kikin81.atproto.oauth.OAuthSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException

class AndroidOAuthSessionStore(appContext: Context) : OAuthSessionStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "kikinlex_oauth_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun load(): OAuthSession? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching { json.decodeFromString(OAuthSession.serializer(), raw) }.getOrNull()
    }

    override suspend fun save(session: OAuthSession) {
        // commit(), not apply(): save() runs after the auth server has already
        // rotated the single-use refresh token (see OAuthSessionStore.save).
        // apply()'s deferred write can be lost to process death, stranding the
        // consumed token on disk → invalid_grant (reuse detection) on the next
        // cold start → the whole session is revoked. The synchronous disk +
        // crypto work moves off the caller's thread (suspend fns are expected
        // to be main-safe), and a false return becomes an IOException so
        // DpopAuthProvider's retry + onPersistFailure actually engage.
        withContext(Dispatchers.IO) {
            @Suppress("ApplySharedPref")
            val committed = prefs
                .edit()
                .putString(KEY, json.encodeToString(OAuthSession.serializer(), session))
                .commit()
            if (!committed) {
                throw IOException("SharedPreferences commit() failed to persist the rotated session")
            }
        }
    }

    override suspend fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "oauth_session_json"
    }
}
