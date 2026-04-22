package io.github.kikin81.atproto.runtime

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class RecordDecoderTest {

    @Serializable
    data class FakeLike(
        val createdAt: String,
        val subject: String,
    )

    @Serializable
    data class FakePost(
        val text: String,
        val createdAt: String,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @Serializable(with = AtFieldSerializer::class)
        val reply: AtField<String> = AtField.Missing,
    )

    @Test
    fun encodeRecordIncludesTypeAndFields() {
        val like = FakeLike(createdAt = "2026-01-01T00:00:00Z", subject = "at://did:plc:x/app.bsky.feed.post/abc")
        val json = encodeRecord(FakeLike.serializer(), like, "app.bsky.feed.like")
        assertEquals("app.bsky.feed.like", json["\$type"].toString().trim('"'))
        assertEquals("2026-01-01T00:00:00Z", json["createdAt"].toString().trim('"'))
        assertEquals("at://did:plc:x/app.bsky.feed.post/abc", json["subject"].toString().trim('"'))
    }

    @Test
    fun encodeRecordOmitsMissingAtFields() {
        val post = FakePost(text = "hello", createdAt = "2026-01-01T00:00:00Z")
        val json = encodeRecord(FakePost.serializer(), post, "app.bsky.feed.post")
        assertTrue(json.containsKey("text"))
        assertTrue(json.containsKey("createdAt"))
        assertTrue(json.containsKey("\$type"))
        assertFalse(json.containsKey("reply"))
    }
}
