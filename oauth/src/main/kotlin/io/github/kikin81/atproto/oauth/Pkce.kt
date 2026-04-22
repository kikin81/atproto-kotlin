package io.github.kikin81.atproto.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * PKCE (Proof Key for Code Exchange) S256 implementation per RFC 7636.
 */
internal object Pkce {
    @OptIn(ExperimentalEncodingApi::class)
    fun generateVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.UrlSafe.encode(bytes).trimEnd('=')
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun computeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.UrlSafe.encode(hash).trimEnd('=')
    }
}
