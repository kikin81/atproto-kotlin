package io.github.kikin81.atproto.oauth

import kotlinx.serialization.Serializable

/**
 * Persisted OAuth session state. Contains everything needed to make
 * authenticated XRPC requests and refresh the session when the access
 * token expires.
 *
 * The DPoP keypair is serialized as raw byte arrays (PKCS8 private key +
 * X509 public key) rather than as JWK JSON. The consumer's
 * [OAuthSessionStore] implementation is responsible for encrypting these
 * at rest (e.g. via EncryptedSharedPreferences on Android).
 *
 * [did], [handle], and [pdsUrl] are nullable to accommodate the signup
 * flow's brief window between token exchange and identity hydration. Once
 * hydration completes (or its bounded retry budget exhausts) the session
 * is re-persisted with whatever values resolved. The login flow always
 * populates all three eagerly.
 */
@Serializable
data class OAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val did: String?,
    val handle: String?,
    val pdsUrl: String?,
    val tokenEndpoint: String,
    val revocationEndpoint: String? = null,
    val clientId: String? = null,
    val dpopPrivateKey: ByteArray,
    val dpopPublicKey: ByteArray,
    val authServerNonce: String? = null,
    val clockOffsetSeconds: Long = 0,
    val pdsNonce: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OAuthSession) return false
        return did == other.did && accessToken == other.accessToken
    }

    override fun hashCode(): Int = (did?.hashCode() ?: 0) * 31 + accessToken.hashCode()
}

/**
 * Platform-agnostic session persistence interface. Consumers provide
 * the storage backend — the module handles serialization.
 *
 * On Android, use `EncryptedSharedPreferences` (the sample shows how).
 * On JVM desktop, use a file-backed store with appropriate permissions.
 */
interface OAuthSessionStore {
    suspend fun load(): OAuthSession?

    /**
     * Persists the session. MUST be durable before returning: [save] is
     * called AFTER the authorization server has already rotated the
     * single-use refresh token, so a write that is deferred (e.g. Android
     * `SharedPreferences.apply()`) or lost to process death leaves a
     * consumed refresh token on disk — the next cold-start refresh is then
     * rejected as token reuse (`invalid_grant`) and the whole session is
     * revoked. Prefer synchronous commits (`commit()`, DataStore
     * `updateData`) over fire-and-forget writes.
     */
    suspend fun save(session: OAuthSession)

    suspend fun clear()
}
