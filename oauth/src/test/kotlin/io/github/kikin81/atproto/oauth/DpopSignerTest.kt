package io.github.kikin81.atproto.oauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.Signature
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class DpopSignerTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decodeJwtPart(part: String): JsonObject {
        val padded = when (part.length % 4) {
            2 -> "$part=="
            3 -> "$part="
            else -> part
        }
        val bytes = Base64.UrlSafe.decode(padded)
        return json.decodeFromString(JsonObject.serializer(), String(bytes))
    }

    private fun parseJwt(jwt: String): Triple<JsonObject, JsonObject, String> {
        val parts = jwt.split(".")
        assertEquals(3, parts.size, "JWT must have 3 parts")
        return Triple(decodeJwtPart(parts[0]), decodeJwtPart(parts[1]), parts[2])
    }

    @Test
    fun signProducesValidEs256Jwt() {
        val signer = DpopSigner.generate()
        val jwt = signer.sign(method = "POST", url = "https://pds.example.com/oauth/par")
        val (header, payload, _) = parseJwt(jwt)

        assertEquals("dpop+jwt", header["typ"]?.jsonPrimitive?.content)
        assertEquals("ES256", header["alg"]?.jsonPrimitive?.content)
        assertNotNull(header["jwk"], "header must include jwk")

        assertEquals("POST", payload["htm"]?.jsonPrimitive?.content)
        assertEquals("https://pds.example.com/oauth/par", payload["htu"]?.jsonPrimitive?.content)
        assertNotNull(payload["jti"], "payload must include jti")
        assertNotNull(payload["iat"], "payload must include iat")
    }

    @Test
    fun signatureVerifiesWithPublicKey() {
        val signer = DpopSigner.generate()
        val jwt = signer.sign(method = "GET", url = "https://pds.example.com/xrpc/app.bsky.feed.getTimeline")
        val parts = jwt.split(".")
        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8)
        val sigBytes = Base64.UrlSafe.decode(
            parts[2].let { s ->
                when (s.length % 4) {
                    2 -> "$s=="
                    3 -> "$s="
                    else -> s
                }
            },
        )

        val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
        verifier.initVerify(signer.publicKey)
        verifier.update(signingInput)
        assertTrue(verifier.verify(sigBytes), "ES256 signature must verify with the signer's public key")
    }

    @Test
    fun jwkThumbprintMatchesSigningKey() {
        val signer = DpopSigner.generate()
        val thumbprint = DpopSigner.jwkThumbprint(signer.publicKey)
        assertNotNull(thumbprint)
        assertTrue(thumbprint.length in 40..50, "SHA-256 base64url should be ~43 chars, got ${thumbprint.length}")

        val thumbprint2 = DpopSigner.jwkThumbprint(signer.publicKey)
        assertEquals(thumbprint, thumbprint2, "same key should produce same thumbprint")

        val otherSigner = DpopSigner.generate()
        val otherThumbprint = DpopSigner.jwkThumbprint(otherSigner.publicKey)
        assertNotEquals(thumbprint, otherThumbprint, "different keys should produce different thumbprints")
    }

    @Test
    fun proofWithNonceIncludesNonceClaim() {
        val signer = DpopSigner.generate()
        val jwt = signer.sign(
            method = "POST",
            url = "https://pds.example.com/oauth/par",
            nonce = "server-nonce-abc",
        )
        val (_, payload, _) = parseJwt(jwt)
        assertEquals("server-nonce-abc", payload["nonce"]?.jsonPrimitive?.content)
    }

    @Test
    fun proofWithoutNonceOmitsNonceClaim() {
        val signer = DpopSigner.generate()
        val jwt = signer.sign(method = "POST", url = "https://pds.example.com/oauth/par")
        val (_, payload, _) = parseJwt(jwt)
        assertNull(payload["nonce"], "nonce claim must be absent when no nonce is provided")
    }

    @Test
    fun pdsRequestIncludesAthClaim() {
        val signer = DpopSigner.generate()
        val ath = DpopSigner.accessTokenHash("fake-access-token-123")
        val jwt = signer.sign(
            method = "GET",
            url = "https://pds.example.com/xrpc/app.bsky.feed.getTimeline",
            accessTokenHash = ath,
        )
        val (_, payload, _) = parseJwt(jwt)
        assertEquals(ath, payload["ath"]?.jsonPrimitive?.content)
    }

    @Test
    fun tokenRequestOmitsAthClaim() {
        val signer = DpopSigner.generate()
        val jwt = signer.sign(
            method = "POST",
            url = "https://pds.example.com/oauth/token",
        )
        val (_, payload, _) = parseJwt(jwt)
        assertNull(payload["ath"], "ath must be absent for token endpoint requests")
    }

    @Test
    fun proofOmitsIssClaim() {
        val signer = DpopSigner.generate()
        val jwt = signer.sign(
            method = "POST",
            url = "https://pds.example.com/oauth/par",
            nonce = "nonce",
        )
        val (_, payload, _) = parseJwt(jwt)
        assertNull(payload["iss"], "iss claim SHOULD NOT be included per AT Protocol spec")
    }

    @Test
    fun eachSignCallProducesUniqueJti() {
        val signer = DpopSigner.generate()
        val jwt1 = signer.sign(method = "GET", url = "https://example.com")
        val jwt2 = signer.sign(method = "GET", url = "https://example.com")
        val (_, payload1, _) = parseJwt(jwt1)
        val (_, payload2, _) = parseJwt(jwt2)
        assertNotEquals(
            payload1["jti"]?.jsonPrimitive?.content,
            payload2["jti"]?.jsonPrimitive?.content,
            "each proof must have a unique jti",
        )
    }

    @Test
    fun accessTokenHashMatchesPkceS256Algorithm() {
        val token = "test-access-token"
        val hash = DpopSigner.accessTokenHash(token)
        assertNotNull(hash)
        assertTrue(hash.length in 40..50, "SHA-256 base64url should be ~43 chars")
        assertFalse(hash.contains("="), "base64url should have no padding")
        assertFalse(hash.contains("+"), "base64url should use - not +")
        assertFalse(hash.contains("/"), "base64url should use _ not /")
    }

    @Test
    fun keypairExportAndRestoreRoundTrip() {
        val original = DpopSigner.generate()
        val exported = original.exportKeyPair()
        val restored = DpopSigner.fromExported(exported)

        val jwt = restored.sign(method = "GET", url = "https://example.com")
        val parts = jwt.split(".")
        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8)
        val sigBytes = Base64.UrlSafe.decode(
            parts[2].let { s ->
                when (s.length % 4) {
                    2 -> "$s=="
                    3 -> "$s="
                    else -> s
                }
            },
        )

        val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
        verifier.initVerify(original.publicKey)
        verifier.update(signingInput)
        assertTrue(verifier.verify(sigBytes), "restored signer's proof must verify with the original public key")

        assertEquals(
            DpopSigner.jwkThumbprint(original.publicKey),
            DpopSigner.jwkThumbprint(restored.publicKey),
            "thumbprints must match after export/restore round-trip",
        )
    }
}
