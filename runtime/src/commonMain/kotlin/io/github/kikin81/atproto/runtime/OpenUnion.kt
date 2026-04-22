package io.github.kikin81.atproto.runtime

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.reflect.KClass

/**
 * AT Protocol union discrimination infrastructure.
 *
 * Lexicon `union` fields serialize with a `$type` discriminator on each member:
 *
 * ```json
 * { "$type": "app.bsky.embed.images", "images": [...] }
 * ```
 *
 * The AT Protocol spec (https://atproto.com/specs/data-model) says unions are
 * **open by default**: a client may receive a `$type` value the library doesn't
 * know about, and MUST NOT discard the data. This module provides the runtime
 * building blocks for generated sealed-interface unions to honor that contract.
 *
 * The generator emits, per union:
 *  1. A sealed interface `Foo : OpenUnionMember` with a data class per known ref.
 *  2. A `Foo.Unknown(type, raw) : Foo, UnknownOpenUnionMember` fallback member.
 *  3. A `FooUnknownSerializer : UnknownMemberSerializer<Foo.Unknown>` wiring (2).
 *  4. A `FooSerializer : OpenUnionSerializer<Foo>` that dispatches on `$type`
 *     and routes unknowns to (3).
 *
 * The runtime provides the generic base types; the generator fills in the
 * union-specific glue.
 */

/**
 * Marker interface implemented by every member of a generated sealed-interface
 * union. Gives the runtime a common upper bound to target and lets consumers
 * write helpers generic over any union-typed value.
 */
public interface OpenUnionMember

/**
 * Sub-interface implemented by the generator-emitted `Unknown` variant of each
 * sealed union. Carries the original `$type` string and the full raw JSON
 * object so that re-serialization is lossless.
 *
 * Each generated union has its OWN `Unknown` data class implementing this
 * interface (typed as a member of that union's sealed hierarchy) so that
 * type checking works end-to-end.
 */
public interface UnknownOpenUnionMember : OpenUnionMember {
    /** The `$type` value from the wire (may be a bare NSID or `nsid#name`). */
    public val type: String

    /** The full original JSON object, including the `$type` key. */
    public val raw: JsonObject
}

/**
 * Base serializer for a generator-emitted `Unknown` variant.
 *
 * A concrete subclass fills in [construct] with the data class constructor
 * reference for the owning union's Unknown member, and passes a unique serial
 * name (conventionally the Kotlin FQN of the Unknown class).
 *
 * Example generator output:
 * ```
 * object MediaUnknownSerializer : UnknownMemberSerializer<Media.Unknown>(
 *     "io.github.kikin81.atproto.app.bsky.embed.recordWithMedia.Media.Unknown"
 * ) {
 *     override fun construct(type: String, raw: JsonObject) = Media.Unknown(type, raw)
 * }
 * ```
 */
public abstract class UnknownMemberSerializer<T : UnknownOpenUnionMember>(
    serialName: String,
) : KSerializer<T> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName)

    /** Construct the owning union's specific `Unknown` data class. */
    protected abstract fun construct(type: String, raw: JsonObject): T

    override fun deserialize(decoder: Decoder): T {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException(
                "UnknownMemberSerializer is JSON-only in V1; got ${decoder::class.simpleName}",
            )
        val element = jsonDecoder.decodeJsonElement().jsonObject
        val type = element[DOLLAR_TYPE]?.jsonPrimitive?.content.orEmpty()
        return construct(type, element)
    }

    override fun serialize(encoder: Encoder, value: T) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException(
                "UnknownMemberSerializer is JSON-only in V1; got ${encoder::class.simpleName}",
            )
        jsonEncoder.encodeJsonElement(value.raw)
    }
}

/**
 * Base serializer for generator-emitted open unions.
 *
 * A generated subclass implements three methods that together cover both
 * directions of the wire mapping without needing reflection:
 *  - [selectKnownDeserializer]: given a normalized `$type` string from an
 *    incoming wire value, return the matching known member's serializer (or
 *    `null` to route to the Unknown fallback on decode).
 *  - [selectKnownSerializer]: given an in-memory value, return its serializer
 *    (or `null` to route to the Unknown fallback on encode).
 *  - [unknownSerializer]: return the owning union's [UnknownMemberSerializer].
 *
 * The base class handles `$type` extraction, nsid/nsid#main normalization,
 * and injection of the `$type` key on encode (concrete `@Serializable` data
 * classes produce objects without a discriminator, so we prepend it).
 *
 * Typical generator output:
 *
 * ```
 * object MediaSerializer : OpenUnionSerializer<Media>(Media::class) {
 *     override fun selectKnownDeserializer(type: String) = when (type) {
 *         "app.bsky.embed.images"   -> Media.Images.serializer()
 *         "app.bsky.embed.video"    -> Media.Video.serializer()
 *         "app.bsky.embed.external" -> Media.External.serializer()
 *         else -> null
 *     }
 *     override fun selectKnownSerializer(value: Media) = when (value) {
 *         is Media.Images   -> Media.Images.serializer()
 *         is Media.Video    -> Media.Video.serializer()
 *         is Media.External -> Media.External.serializer()
 *         is Media.Unknown  -> null
 *     }
 *     override fun unknownSerializer() = MediaUnknownSerializer
 * }
 * ```
 */
public abstract class OpenUnionSerializer<T : OpenUnionMember>(
    baseClass: KClass<T>,
) : KSerializer<T> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "io.github.kikin81.atproto.runtime.OpenUnion<${baseClass.simpleName ?: "?"}>",
    )

    /**
     * Return the serializer for the known union member matching [type], or
     * `null` if [type] is not a known member. [type] has already been
     * normalized via [normalizeDollarType].
     */
    protected abstract fun selectKnownDeserializer(type: String): KSerializer<out T>?

    /**
     * Return the serializer for [value] if it is a known union member, or
     * `null` if it is the Unknown fallback variant. The subclass implements
     * this via an exhaustive `when` on the sealed hierarchy — no reflection.
     */
    protected abstract fun selectKnownSerializer(value: T): KSerializer<out T>?

    /** Return the owning union's Unknown serializer for the fallback path. */
    protected abstract fun unknownSerializer(): KSerializer<out T>

    override fun deserialize(decoder: Decoder): T {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException(
                "OpenUnionSerializer is JSON-only in V1; got ${decoder::class.simpleName}",
            )
        val element = jsonDecoder.decodeJsonElement()
        val raw = element.jsonObject[DOLLAR_TYPE]?.jsonPrimitive?.content.orEmpty()
        val normalized = normalizeDollarType(raw)

        @Suppress("UNCHECKED_CAST")
        val serializer = (selectKnownDeserializer(normalized) ?: unknownSerializer()) as KSerializer<T>
        return jsonDecoder.json.decodeFromJsonElement(serializer, element)
    }

    override fun serialize(encoder: Encoder, value: T) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException(
                "OpenUnionSerializer is JSON-only in V1; got ${encoder::class.simpleName}",
            )

        val knownSerializer = selectKnownSerializer(value)
        if (knownSerializer == null) {
            // Unknown fallback path: the Unknown member already carries the
            // full raw JsonObject (including $type) — its serializer emits
            // that verbatim for lossless round-trip.
            @Suppress("UNCHECKED_CAST")
            jsonEncoder.encodeSerializableValue(
                unknownSerializer() as KSerializer<T>,
                value,
            )
            return
        }

        // Known path: encode via the concrete member serializer, then inject
        // the `$type` discriminator using the serializer's serial name. We
        // prepend `$type` so it appears first in the emitted object, matching
        // the convention used across AT Protocol payloads.
        @Suppress("UNCHECKED_CAST")
        val element = jsonEncoder.json
            .encodeToJsonElement(knownSerializer as KSerializer<T>, value)
            .jsonObject
        val typeName = knownSerializer.descriptor.serialName
        val withType = JsonObject(
            linkedMapOf<String, JsonElement>().apply {
                put(DOLLAR_TYPE, JsonPrimitive(typeName))
                putAll(element)
            },
        )
        jsonEncoder.encodeJsonElement(withType)
    }
}

/**
 * Normalize a `$type` discriminator value for equality matching.
 *
 * Per the AT Protocol data model spec, a `$type` of `com.example.foo` and
 * `com.example.foo#main` refer to the same definition — the `#main` fragment
 * is implicit and MAY be omitted. Generators always match against the bare
 * form, so incoming values with a `#main` suffix are stripped.
 *
 * Other fragments (e.g. `com.example.foo#bar`) are preserved as-is.
 */
public fun normalizeDollarType(type: String): String = if (type.endsWith("#main")) type.removeSuffix("#main") else type

/** The `$type` key used throughout AT Protocol JSON payloads. */
public const val DOLLAR_TYPE: String = "\$type"
