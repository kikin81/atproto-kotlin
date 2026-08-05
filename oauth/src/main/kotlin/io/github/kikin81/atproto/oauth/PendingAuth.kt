package io.github.kikin81.atproto.oauth

import kotlinx.serialization.Serializable

/**
 * The in-flight half of an OAuth login, captured between `beginLogin` /
 * `beginSignup` and [AtOAuth.completeLogin].
 *
 * This exists as a persistable type because the authorization step happens
 * in a **different process** — a browser or Custom Tab. On Android the OS
 * is free to kill the app while the user is typing a password on the
 * authorization page, and it routinely does on low-memory devices. If the
 * PKCE verifier and CSRF `state` only lived in memory, that kill would
 * strand the login permanently: the callback returns to a fresh process
 * that has no idea a login was ever started, and the user sees an error no
 * amount of retrying can clear.
 *
 * The DPoP keypair is serialized as raw byte arrays (PKCS8 private key +
 * X509 public key), exactly as [OAuthSession] does. The consumer's
 * [PendingAuthStore] implementation is responsible for encrypting these at
 * rest — this record briefly holds key material and a PKCE verifier, so it
 * deserves the same protection as a session.
 *
 * The auth-server metadata is flattened rather than nested so the stored
 * shape stays a flat, forward-compatible JSON object.
 *
 * @property createdAtEpochMillis stamped at save time so [AtOAuth] can
 *   discard a pending login that is older than its TTL. Authorization codes
 *   expire server-side in minutes; a pending record that outlives that is
 *   never going to complete, and keeping key material on disk past its
 *   usefulness is needless exposure.
 */
@Serializable
data class PendingAuth(
    val state: String,
    val codeVerifier: String,
    val redirectUri: String,
    /** Serialized [AtOAuth]'s internal `FlowOrigin` — `"Login"` or `"Signup"`. */
    val flowOrigin: String,
    val authServerNonce: String?,
    val dpopPrivateKey: ByteArray,
    val dpopPublicKey: ByteArray,
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val parEndpoint: String,
    val revocationEndpoint: String? = null,
    val pdsUrl: String? = null,
    val did: String? = null,
    val handle: String? = null,
    val promptValuesSupported: List<String> = emptyList(),
    val createdAtEpochMillis: Long = 0,
) {
    // ByteArray fields make the generated equals/hashCode reference-based,
    // which silently breaks value comparison in consumer tests. `state` is a
    // 32-byte CSPRNG value generated per flow, so it alone identifies the
    // record. Same reasoning as OAuthSession's override.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PendingAuth) return false
        return state == other.state
    }

    override fun hashCode(): Int = state.hashCode()
}

/**
 * Platform-agnostic persistence for the in-flight login ([PendingAuth]).
 * Consumers provide the storage backend — the module handles serialization.
 *
 * **Android consumers should supply a durable, encrypted implementation.**
 * The default [InMemoryPendingAuthStore] cannot survive process death, which
 * is precisely the case that breaks sign-in on memory-constrained devices.
 * Reuse whatever backing store already holds the [OAuthSession] (DataStore +
 * Tink, EncryptedSharedPreferences, …) under a separate key.
 *
 * Unlike [OAuthSessionStore.save], a deferred write here is merely
 * inconvenient rather than destructive — nothing has been consumed server-side
 * at save time. It still must be durable before the browser is launched, so
 * prefer a synchronous commit anyway.
 */
interface PendingAuthStore {
    suspend fun load(): PendingAuth?

    suspend fun save(pending: PendingAuth)

    suspend fun clear()
}

/**
 * Default [PendingAuthStore]: keeps the pending login in memory only.
 *
 * Preserves the module's historical behaviour for consumers that have not
 * supplied a store. Adequate for desktop/JVM and for tests; **not** adequate
 * on Android, where a login started before a process death can never be
 * completed. See [PendingAuthStore].
 */
class InMemoryPendingAuthStore : PendingAuthStore {
    private var pending: PendingAuth? = null

    override suspend fun load(): PendingAuth? = pending

    override suspend fun save(pending: PendingAuth) {
        this.pending = pending
    }

    override suspend fun clear() {
        pending = null
    }
}
