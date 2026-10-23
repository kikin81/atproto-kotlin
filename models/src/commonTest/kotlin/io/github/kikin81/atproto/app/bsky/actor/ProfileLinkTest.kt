package io.github.kikin81.atproto.app.bsky.actor

import io.github.kikin81.atproto.runtime.AtField
import io.github.kikin81.atproto.runtime.Datetime
import io.github.kikin81.atproto.runtime.Uri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Covers `app.bsky.actor.link`, the record behind profile links, and the
 * `links` strongRef array on `app.bsky.actor.profile` that orders them.
 */
class ProfileLinkTest {
    // Mirror the generator-emitted Json config (see runtime OpenUnionTest).
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun link_decodesRequiredFieldsOnly() {
        val wire = """{"url":"https://example.com","createdAt":"2026-10-22T12:00:00.000Z"}"""
        val link = json.decodeFromString<Link>(wire)

        assertEquals("https://example.com", link.url.raw)
        assertEquals(AtField.Missing, link.title)
        assertEquals(AtField.Missing, link.icon)
        assertEquals(AtField.Missing, link.labels)
    }

    @Test
    fun link_decodesTitleAndIconBlob() {
        val wire = """
            {
              "${'$'}type": "app.bsky.actor.link",
              "url": "https://ko-fi.com/example",
              "title": "Support me",
              "icon": {
                "${'$'}type": "blob",
                "ref": {"${'$'}link": "bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku"},
                "mimeType": "image/png",
                "size": 4096
              },
              "createdAt": "2026-10-22T12:00:00.000Z"
            }
        """.trimIndent()
        val link = json.decodeFromString<Link>(wire)

        assertEquals(AtField.Defined("Support me"), link.title)
        val icon = assertIs<AtField.Defined<*>>(link.icon).value
        assertIs<io.github.kikin81.atproto.runtime.Blob>(icon)
        assertEquals("image/png", icon.mimeType)
    }

    @Test
    fun link_omitsMissingOptionalFieldsOnEncode() {
        val link = Link(
            createdAt = Datetime("2026-10-22T12:00:00.000Z"),
            url = Uri("https://example.com"),
        )
        val encoded = json.parseToJsonElement(json.encodeToString(link)).jsonObject

        assertFalse("title" in encoded)
        assertFalse("icon" in encoded)
        assertFalse("labels" in encoded)
        assertEquals("https://example.com", encoded["url"].toString().trim('"'))
    }

    @Test
    fun profile_decodesLinksAsOrderedStrongRefs() {
        val a = "at://did:plc:abc/app.bsky.actor.link/3kaaaaaaaaaaa"
        val b = "at://did:plc:abc/app.bsky.actor.link/3kbbbbbbbbbbb"
        val cid = "bafyreib57i4ahnr7e4uzoewdetkepfsbiqnw4la73hk5hgjp4zdbalfko4"
        val wire = """
            {"links":[{"uri":"$a","cid":"$cid"},{"uri":"$b","cid":"$cid"}]}
        """.trimIndent()
        val profile = json.decodeFromString<Profile>(wire)

        val refs = assertIs<AtField.Defined<List<io.github.kikin81.atproto.com.atproto.repo.StrongRef>>>(profile.links).value
        assertEquals(listOf(a, b), refs.map { it.uri.raw })
    }
}
