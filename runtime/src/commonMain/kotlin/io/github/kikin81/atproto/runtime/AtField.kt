@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.kikin81.atproto.runtime

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull

/**
 * Three-state optional field for AT Protocol mutation payloads.
 *
 * The AT Protocol wire format distinguishes three states for an optional field:
 *  - **Absent** from the JSON object entirely — caller didn't touch it
 *  - Present with value **`null`** — caller explicitly cleared it
 *  - Present with a concrete **value** — caller set it
 *
 * This distinction matters for mutations like `app.bsky.actor.putPreferences`
 * where "leave unchanged" and "clear this field" are different operations.
 * A plain `T? = null` collapses the first two states, making the distinction
 * unrepresentable — see the design doc for the full rationale.
 *
 * Usage on a generated data class:
 * ```
 * @Serializable
 * data class PutPreferencesInput(
 *     val did: Did,
 *     @EncodeDefault(EncodeDefault.Mode.NEVER)
 *     val displayName: AtField<String> = AtField.Missing,
 * )
 * ```
 *
 * The `= AtField.Missing` default is what makes the absent-key → `Missing`
 * decode path work (kotlinx-serialization only invokes the field serializer
 * when the key is present). The `@EncodeDefault(NEVER)` is what makes the
 * encode path skip the field when it equals `Missing`. Both annotations are
 * required when `encodeDefaults = true`; see [AtFieldSerializer.serialize]
 * for the fail-loud diagnostic if they're missing.
 *
 * The runtime Json configuration MUST keep `explicitNulls = true` (the default).
 * Setting it to `false` would silently collapse [Null] into [Missing] on the
 * wire and defeat the whole design.
 */
public sealed interface AtField<out T> {

    /** The field key is absent from the JSON object. */
    public data object Missing : AtField<Nothing>

    /** The field key is present with an explicit JSON `null` value. */
    public data object Null : AtField<Nothing>

    /** The field key is present with a concrete value. */
    public data class Defined<out T>(public val value: T) : AtField<T>
}

/**
 * Generic serializer for [AtField] values. The compiler plugin wires [inner]
 * automatically when the field type is resolved (e.g. `AtField<String>` picks
 * up `String.serializer()`).
 *
 * On decode: any time this serializer is invoked, the field key was present in
 * the input (otherwise kotlinx-serialization would have used the default param
 * and never called us). We inspect the element: [JsonNull] becomes [AtField.Null],
 * anything else becomes [AtField.Defined] wrapping the inner-decoded value.
 *
 * On encode: [AtField.Null] emits a JSON null token, [AtField.Defined] delegates
 * to [inner], and [AtField.Missing] is an error — it should have been filtered
 * out by `@EncodeDefault(NEVER)` on the field (or by the global `encodeDefaults
 * = false`) before reaching us.
 *
 * JSON-only: [deserialize] casts the decoder to [JsonDecoder] to inspect the
 * raw element. V1 is JSON/XRPC-only; a CBOR path can be added later without
 * breaking the public surface.
 */
/**
 * Wraps [value] as [AtField.Defined]. Reads like natural English at call sites:
 *
 * ```
 * val post = Post(
 *     text = "hello world",
 *     reply = present(replyRef),
 * )
 * ```
 *
 * Paired with [presentOrNull] for the null-coalescing path where the caller has
 * a `T?` in hand and wants "non-null → set" / "null → explicit clear" semantics.
 */
public inline fun <T : Any> present(value: T): AtField.Defined<T> = AtField.Defined(value)

/**
 * Converts a nullable value into an [AtField]: non-null → [AtField.Defined],
 * null → [AtField.Null] (an *explicit clear*, not "leave unchanged").
 *
 * If you want null → "leave unchanged" semantics, don't pass the field at all
 * and let the default `= AtField.Missing` take effect.
 */
public inline fun <T : Any> presentOrNull(value: T?): AtField<T> = if (value != null) AtField.Defined(value) else AtField.Null

public class AtFieldSerializer<T : Any>(
    private val inner: KSerializer<T>,
) : KSerializer<AtField<T>> {

    override val descriptor: SerialDescriptor = inner.descriptor.nullable

    override fun deserialize(decoder: Decoder): AtField<T> {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException(
                "AtField is JSON-only in V1; got decoder of type ${decoder::class.simpleName}",
            )
        val element = jsonDecoder.decodeJsonElement()
        return if (element is JsonNull) {
            AtField.Null
        } else {
            AtField.Defined(jsonDecoder.json.decodeFromJsonElement(inner, element))
        }
    }

    override fun serialize(encoder: Encoder, value: AtField<T>) {
        when (value) {
            AtField.Missing -> throw SerializationException(
                "AtField.Missing reached the serializer. The owning field must declare " +
                    "`= AtField.Missing` as its default AND be annotated with " +
                    "@EncodeDefault(EncodeDefault.Mode.NEVER) so kotlinx-serialization " +
                    "skips the key instead of asking us to encode an absent value.",
            )
            AtField.Null -> encoder.encodeNull()
            is AtField.Defined -> encoder.encodeSerializableValue(inner, value.value)
        }
    }
}
