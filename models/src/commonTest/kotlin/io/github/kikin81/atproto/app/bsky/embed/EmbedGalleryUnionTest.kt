package io.github.kikin81.atproto.app.bsky.embed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Proves the gallery overlay wired `app.bsky.embed.gallery` into the
 * recordWithMedia media unions as a *typed* member (not the Unknown fallback).
 */
class EmbedGalleryUnionTest {
    // Mirror the generator-emitted Json config (see runtime OpenUnionTest).
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun authoringUnion_decodesGalleryAsTypedMember() {
        val wire = """{"${'$'}type":"app.bsky.embed.gallery","items":[]}"""
        val decoded = json.decodeFromString<RecordWithMediaMediaUnion>(wire)
        assertIs<Gallery>(decoded)
    }

    @Test
    fun viewUnion_decodesGalleryViewAsTypedMember() {
        val wire = """{"${'$'}type":"app.bsky.embed.gallery#view","items":[]}"""
        val decoded = json.decodeFromString<RecordWithMediaViewMediaUnion>(wire)
        assertIs<GalleryView>(decoded)
    }

    @Test
    fun authoringUnion_encodesGalleryWithNsidDollarType() {
        val value: RecordWithMediaMediaUnion = Gallery(items = emptyList())
        val encoded = json.encodeToString(value)
        val type = json.parseToJsonElement(encoded).jsonObject["${'$'}type"]!!.jsonPrimitive.content
        assertEquals("app.bsky.embed.gallery", type)
    }
}
