package io.github.kikin81.atproto.runtime

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Simulated generator output. In the real generator this is a top-level file
// per union; here we inline two members plus an Unknown fallback that wraps
// the generic UnknownOpenUnionMember contract with FakeMedia-typed identity.

@Serializable(with = FakeMediaSerializer::class)
sealed interface FakeMedia : OpenUnionMember {

    @Serializable
    @SerialName("app.bsky.embed.images")
    data class Images(val count: Int) : FakeMedia

    @Serializable
    @SerialName("app.bsky.embed.video")
    data class Video(val duration: Int) : FakeMedia

    @Serializable(with = FakeMediaUnknownSerializer::class)
    data class Unknown(
        override val type: String,
        override val raw: JsonObject,
    ) : FakeMedia,
        UnknownOpenUnionMember
}

object FakeMediaUnknownSerializer : UnknownMemberSerializer<FakeMedia.Unknown>(
    "io.github.kikin81.atproto.runtime.FakeMedia.Unknown",
) {
    override fun construct(type: String, raw: JsonObject): FakeMedia.Unknown = FakeMedia.Unknown(type = type, raw = raw)
}

object FakeMediaSerializer : OpenUnionSerializer<FakeMedia>(FakeMedia::class) {
    override fun selectKnownDeserializer(type: String): KSerializer<out FakeMedia>? = when (type) {
        "app.bsky.embed.images" -> FakeMedia.Images.serializer()
        "app.bsky.embed.video" -> FakeMedia.Video.serializer()
        else -> null
    }

    override fun selectKnownSerializer(value: FakeMedia): KSerializer<out FakeMedia>? = when (value) {
        is FakeMedia.Images -> FakeMedia.Images.serializer()
        is FakeMedia.Video -> FakeMedia.Video.serializer()
        is FakeMedia.Unknown -> null
    }

    override fun unknownSerializer(): KSerializer<out FakeMedia> = FakeMediaUnknownSerializer
}

class OpenUnionTest {

    // Generator-emitted Json config: ignoreUnknownKeys=true is essential because
    // concrete member deserializers see the `$type` key as an unrecognized field
    // when the polymorphic serializer routes the element to them.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun decode_knownType_routesToTypedMember() {
        val wire = """{"${'$'}type":"app.bsky.embed.images","count":3}"""
        val decoded = json.decodeFromString(FakeMediaSerializer, wire)
        assertIs<FakeMedia.Images>(decoded)
        assertEquals(3, decoded.count)
    }

    @Test
    fun decode_nsidMainSuffix_normalizesToBareNsid() {
        // Server sends `$type: ...#main` form; normalizer strips #main before
        // matching so the known-type path still wins.
        val wire = """{"${'$'}type":"app.bsky.embed.video#main","duration":42}"""
        val decoded = json.decodeFromString(FakeMediaSerializer, wire)
        assertIs<FakeMedia.Video>(decoded)
        assertEquals(42, decoded.duration)
    }

    @Test
    fun decode_unknownType_routesToUnknownFallback() {
        val wire = """{"${'$'}type":"app.bsky.embed.futureThing","someField":"someValue","n":7}"""
        val decoded = json.decodeFromString(FakeMediaSerializer, wire)
        assertIs<FakeMedia.Unknown>(decoded)
        assertEquals("app.bsky.embed.futureThing", decoded.type)
        assertTrue("someField" in decoded.raw)
        assertTrue("n" in decoded.raw)
    }

    @Test
    fun unknownType_roundTripIsLossless() {
        val wire = """{"${'$'}type":"app.bsky.embed.futureThing","a":"b","c":42}"""
        val decoded = json.decodeFromString(FakeMediaSerializer, wire)
        assertIs<FakeMedia.Unknown>(decoded)
        val reencoded = json.encodeToString(FakeMediaSerializer, decoded)
        assertEquals(json.parseToJsonElement(wire), json.parseToJsonElement(reencoded))
    }

    @Test
    fun knownType_encodeFromConcreteInstance() {
        val value: FakeMedia = FakeMedia.Images(count = 5)
        val encoded = json.encodeToString(FakeMediaSerializer, value)
        // The concrete Images serializer adds the $type via @SerialName.
        val parsed = json.parseToJsonElement(encoded).jsonObject
        assertEquals("app.bsky.embed.images", parsed[DOLLAR_TYPE]!!.jsonPrimitive.content)
        assertEquals("5", parsed["count"]!!.jsonPrimitive.content)
    }

    @Test
    fun knownType_encodeUsesSerialName_notKotlinFqcn() {
        // Regression for #76: without @SerialName, kotlinx-serialization defaults
        // descriptor.serialName to the Kotlin FQCN, which makes
        // OpenUnionSerializer emit `$type: "io.github.kikin81.atproto.runtime.FakeMedia.Images"`.
        // Bluesky's appview dispatches embed renderers by lexicon NSID, so the
        // FQCN form silently drops the embed at render time. This test asserts
        // the wire output is the NSID and explicitly NOT a Kotlin FQCN-looking
        // value.
        val value: FakeMedia = FakeMedia.Video(duration = 12)
        val encoded = json.encodeToString(FakeMediaSerializer, value)
        val parsed = json.parseToJsonElement(encoded).jsonObject
        val typeOnWire = parsed[DOLLAR_TYPE]!!.jsonPrimitive.content
        assertEquals("app.bsky.embed.video", typeOnWire)
        assertTrue(
            "FakeMedia" !in typeOnWire,
            "wire \$type leaked Kotlin class identity: $typeOnWire",
        )
    }

    @Test
    fun normalizeDollarType_stripsMainSuffix() {
        assertEquals("com.example.foo", normalizeDollarType("com.example.foo#main"))
    }

    @Test
    fun normalizeDollarType_preservesNonMainFragments() {
        assertEquals("com.example.foo#bar", normalizeDollarType("com.example.foo#bar"))
    }

    @Test
    fun normalizeDollarType_preservesBareNsid() {
        assertEquals("com.example.foo", normalizeDollarType("com.example.foo"))
    }

    @Test
    fun unknownMember_directSerialization_preservesRaw() {
        val raw: JsonObject = buildJsonObject {
            put(DOLLAR_TYPE, "com.example.unknown")
            put("field", "value")
        }
        val member = FakeMedia.Unknown(type = "com.example.unknown", raw = raw)
        val encoded = json.encodeToString(FakeMediaUnknownSerializer, member)
        val decoded = json.decodeFromString(FakeMediaUnknownSerializer, encoded)
        assertEquals(member, decoded)
    }
}
