package io.github.kikin81.atproto.runtime

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class BlobTest {
    private val json = Json { encodeDefaults = false }

    @Test fun cidLinkRoundTrip() {
        val link = CidLink("bafkreigh2akiscaildc6wdwh3p7bk2elwzqitgmxjmkttf3ylr4tjlk7xy")
        val encoded = json.encodeToString(CidLink.serializer(), link)
        assertEquals(
            """{"${'$'}link":"bafkreigh2akiscaildc6wdwh3p7bk2elwzqitgmxjmkttf3ylr4tjlk7xy"}""",
            encoded,
        )
        assertEquals(link, json.decodeFromString(CidLink.serializer(), encoded))
    }

    @Test fun blobEncodesTypeDiscriminatorAndNestedLink() {
        val blob = Blob(
            ref = CidLink("bafkreiabc"),
            mimeType = "image/jpeg",
            size = 123456L,
        )
        val encoded = json.encodeToString(Blob.serializer(), blob)
        // `$type` must appear even though it's a default value — @EncodeDefault(ALWAYS)
        // forces it to be emitted.
        assertEquals(
            """{"ref":{"${'$'}link":"bafkreiabc"},"mimeType":"image/jpeg","size":123456,"${'$'}type":"blob"}""",
            encoded,
        )
    }

    @Test fun blobRoundTripFromWireJson() {
        val wire = """
            {
              "${'$'}type": "blob",
              "ref": { "${'$'}link": "bafkreiabc" },
              "mimeType": "image/png",
              "size": 42
            }
        """.trimIndent()
        val decoded = json.decodeFromString(Blob.serializer(), wire)
        assertEquals("bafkreiabc", decoded.ref.link)
        assertEquals("image/png", decoded.mimeType)
        assertEquals(42L, decoded.size)
        assertEquals("blob", decoded.type)
    }
}
