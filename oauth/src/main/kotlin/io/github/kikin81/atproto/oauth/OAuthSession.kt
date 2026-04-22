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
 */
@Serializable
data class OAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val did: String,
    val handle: String,
    val pdsUrl: String,
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

    override fun hashCode(): Int = did.hashCode() * 31 + accessToken.hashCode()
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
    suspend fun save(session: OAuthSession)
    suspend fun clear()
}
