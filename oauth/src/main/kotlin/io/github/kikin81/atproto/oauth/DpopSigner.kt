package io.github.kikin81.atproto.oauth

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Signs DPoP proof JWTs using EC P-256 (ES256) per RFC 9449.
 *
 * Each [DpopSigner] instance is bound to a single EC keypair for the
 * lifetime of an OAuth session. The private key signs proofs; the public
 * key is embedded in every proof's JWT header as a JWK so the server can
 * verify possession.
 *
 * AT Protocol DPoP proof JWT structure:
 *
 * **Header:**
 * ```json
 * { "typ": "dpop+jwt", "alg": "ES256", "jwk": { "kty": "EC", "crv": "P-256", "x": "...", "y": "..." } }
 * ```
 *
 * **Payload:**
 * ```json
 * { "jti": "<unique>", "htm": "POST", "htu": "https://pds/oauth/par", "iat": 1234567890, "nonce": "..." }
 * ```
 *
 * For PDS resource requests (not PAR/token), the payload also includes:
 * ```json
 * { "ath": "<base64url(sha256(access_token))>" }
 * ```
 *
 * Per the AT Protocol spec, the `iss` claim is deliberately **omitted**
 * from all DPoP proofs.
 */
class DpopSigner private constructor(
    private val keyPair: KeyPair,
) {
    val publicKey: ECPublicKey get() = keyPair.public as ECPublicKey
    private val privateKey: ECPrivateKey get() = keyPair.private as ECPrivateKey

    /**
     * Clock offset in seconds between the device and the server. If the
     * server's `Date` header says 12:00:05 and the device says 12:00:00,
     * offset is +5. Applied to `iat` claims so DPoP proofs are accepted
     * even when the device clock is off.
     */
    var clockOffsetSeconds: Long = 0

    /**
     * Calibrates [clockOffsetSeconds] from a server response's `Date` header.
     * Call this after the first response from any AT Protocol server.
     */
    fun calibrateClockFromHeader(dateHeader: String?) {
        if (dateHeader == null) return
        try {
            val serverTime = java.time.ZonedDateTime.parse(
                dateHeader,
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
            )
            val serverEpoch = serverTime.toEpochSecond()
            val deviceEpoch = System.currentTimeMillis() / 1000
            clockOffsetSeconds = serverEpoch - deviceEpoch
        } catch (_: Exception) {
            // Couldn't parse — leave offset at 0
        }
    }

    /**
     * Signs a DPoP proof JWT for the given HTTP method + URL.
     *
     * @param method HTTP method (GET, POST, etc.)
     * @param url Full target URL (scheme + host + path, no query string)
     * @param accessTokenHash Base64url(SHA-256(access_token)) for PDS
     *   resource requests. `null` for PAR and token endpoint requests
     *   (where no access token exists yet).
     * @param nonce Server-issued DPoP nonce. `null` on the first request
     *   to a server (before the nonce-discovery cycle).
     * @return The signed JWT string (header.payload.signature).
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun sign(
        method: String,
        url: String,
        accessTokenHash: String? = null,
        nonce: String? = null,
    ): String {
        val header = buildJwtHeader()
        val payload = buildJwtPayload(method, url, accessTokenHash, nonce)
        val signingInput = "${base64UrlEncode(header)}.${base64UrlEncode(payload)}"
        val signature = signEs256(signingInput.toByteArray(Charsets.UTF_8))
        return "$signingInput.${base64UrlEncodeBytes(signature)}"
    }

    /**
     * Exports the keypair for session persistence. The caller is
     * responsible for storing these securely (e.g. EncryptedSharedPreferences).
     */
    fun exportKeyPair(): ExportedKeyPair = ExportedKeyPair(
        privateKeyEncoded = keyPair.private.encoded,
        publicKeyEncoded = keyPair.public.encoded,
    )

    @OptIn(ExperimentalEncodingApi::class)
    private fun buildJwtHeader(): String {
        val pub = publicKey
        val xBytes = pub.w.affineX.toByteArray().padOrTrimTo32()
        val yBytes = pub.w.affineY.toByteArray().padOrTrimTo32()
        val x = base64UrlEncodeBytes(xBytes)
        val y = base64UrlEncodeBytes(yBytes)
        return """{"typ":"dpop+jwt","alg":"ES256","jwk":{"kty":"EC","crv":"P-256","x":"$x","y":"$y"}}"""
    }

    private fun buildJwtPayload(
        method: String,
        url: String,
        accessTokenHash: String?,
        nonce: String?,
    ): String {
        val jti = UUID.randomUUID().toString()
        val iat = (System.currentTimeMillis() / 1000) + clockOffsetSeconds
        val sb = StringBuilder()
        sb.append("""{"jti":"$jti","htm":"$method","htu":"$url","iat":$iat""")
        if (nonce != null) sb.append(""","nonce":"$nonce"""")
        if (accessTokenHash != null) sb.append(""","ath":"$accessTokenHash"""")
        sb.append("}")
        return sb.toString()
    }

    private fun signEs256(data: ByteArray): ByteArray {
        // Android doesn't have SHA256withECDSAinP1363Format — use the
        // standard DER-encoded signature and convert to the fixed-length
        // IEEE P1363 format (64 bytes: 32-byte r || 32-byte s) that
        // JWS/JWT ES256 requires.
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(data)
        val derSig = sig.sign()
        return derToP1363(derSig)
    }

    /**
     * Converts a DER-encoded ECDSA signature to the fixed-length IEEE
     * P1363 format used by JWS. DER structure:
     * ```
     * SEQUENCE { INTEGER r, INTEGER s }
     * ```
     * P1363 output: exactly 64 bytes (32-byte r || 32-byte s), each
     * zero-padded or trimmed from BigInteger's two's-complement encoding.
     */
    private fun derToP1363(der: ByteArray): ByteArray {
        // Parse DER: 0x30 <len> 0x02 <rLen> <r...> 0x02 <sLen> <s...>
        var offset = 0
        check(der[offset++].toInt() == 0x30) { "Expected SEQUENCE tag" }
        // skip sequence length (may be 1 or 2 bytes)
        if (der[offset].toInt() and 0x80 != 0) offset += (der[offset].toInt() and 0x7F) + 1 else offset++

        check(der[offset++].toInt() == 0x02) { "Expected INTEGER tag for r" }
        val rLen = der[offset++].toInt() and 0xFF
        val rBytes = der.copyOfRange(offset, offset + rLen)
        offset += rLen

        check(der[offset++].toInt() == 0x02) { "Expected INTEGER tag for s" }
        val sLen = der[offset++].toInt() and 0xFF
        val sBytes = der.copyOfRange(offset, offset + sLen)

        val result = ByteArray(64)
        rBytes.padOrTrimTo32().copyInto(result, 0)
        sBytes.padOrTrimTo32().copyInto(result, 32)
        return result
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64UrlEncode(s: String): String = Base64.UrlSafe.encode(s.toByteArray(Charsets.UTF_8)).trimEnd('=')

    @OptIn(ExperimentalEncodingApi::class)
    private fun base64UrlEncodeBytes(b: ByteArray): String = Base64.UrlSafe.encode(b).trimEnd('=')

    /**
     * Trims or zero-pads a BigInteger byte array to exactly 32 bytes.
     * BigInteger.toByteArray() can return 33 bytes (leading sign byte)
     * or fewer than 32 bytes for small values.
     */
    private fun ByteArray.padOrTrimTo32(): ByteArray = when {
        size == 32 -> this
        size > 32 -> copyOfRange(size - 32, size)
        else -> ByteArray(32 - size) + this
    }

    data class ExportedKeyPair(
        val privateKeyEncoded: ByteArray,
        val publicKeyEncoded: ByteArray,
    )

    companion object {
        /**
         * Generates a fresh EC P-256 keypair for a new OAuth session.
         * Call this once per login; persist the keypair via [exportKeyPair].
         */
        fun generate(): DpopSigner {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            return DpopSigner(kpg.generateKeyPair())
        }

        /**
         * Restores a [DpopSigner] from a previously exported keypair.
         */
        fun fromExported(exported: ExportedKeyPair): DpopSigner {
            val kf = java.security.KeyFactory.getInstance("EC")
            val privateKey = kf.generatePrivate(
                java.security.spec.PKCS8EncodedKeySpec(exported.privateKeyEncoded),
            )
            val publicKey = kf.generatePublic(
                java.security.spec.X509EncodedKeySpec(exported.publicKeyEncoded),
            )
            return DpopSigner(KeyPair(publicKey, privateKey))
        }

        /**
         * Computes the `ath` (access token hash) claim value:
         * base64url(SHA-256(access_token)). Same algorithm as PKCE S256.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun accessTokenHash(accessToken: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(accessToken.toByteArray(Charsets.US_ASCII))
            return Base64.UrlSafe.encode(hash).trimEnd('=')
        }

        /**
         * Computes a JWK Thumbprint (RFC 7638) for the signer's public key.
         * Used by the authorization server to bind the DPoP key to the
         * access token. The thumbprint is SHA-256 of the canonical JWK
         * representation with only the required members, sorted lexically.
         */
        @OptIn(ExperimentalEncodingApi::class)
        fun jwkThumbprint(publicKey: ECPublicKey): String {
            val xBytes = publicKey.w.affineX.toByteArray().let { ba ->
                when {
                    ba.size == 32 -> ba
                    ba.size > 32 -> ba.copyOfRange(ba.size - 32, ba.size)
                    else -> ByteArray(32 - ba.size) + ba
                }
            }
            val yBytes = publicKey.w.affineY.toByteArray().let { ba ->
                when {
                    ba.size == 32 -> ba
                    ba.size > 32 -> ba.copyOfRange(ba.size - 32, ba.size)
                    else -> ByteArray(32 - ba.size) + ba
                }
            }
            val x = Base64.UrlSafe.encode(xBytes).trimEnd('=')
            val y = Base64.UrlSafe.encode(yBytes).trimEnd('=')
            // RFC 7638: members sorted lexicographically
            val canonical = """{"crv":"P-256","kty":"EC","x":"$x","y":"$y"}"""
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(canonical.toByteArray(Charsets.UTF_8))
            return Base64.UrlSafe.encode(hash).trimEnd('=')
        }
    }
}
