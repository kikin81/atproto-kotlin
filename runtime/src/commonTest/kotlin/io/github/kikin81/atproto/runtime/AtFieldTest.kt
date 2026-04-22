package io.github.kikin81.atproto.runtime

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AtFieldTest {

    // Properly-annotated mutation input. Both annotations required:
    //   1. `= AtField.Missing` default makes absent-key → Missing work on decode.
    //   2. @EncodeDefault(NEVER) makes Missing → absent-key work on encode.
    @Serializable
    private data class Correct(
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @Serializable(with = AtFieldSerializer::class)
        val a: AtField<String> = AtField.Missing,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @Serializable(with = AtFieldSerializer::class)
        val b: AtField<String> = AtField.Missing,
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @Serializable(with = AtFieldSerializer::class)
        val c: AtField<String> = AtField.Missing,
    )

    // Deliberately missing @EncodeDefault(NEVER) to exercise the fail-loud path.
    @Serializable
    private data class MissingAnnotation(
        @Serializable(with = AtFieldSerializer::class)
        val a: AtField<String> = AtField.Missing,
    )

    private val json = Json {
        // explicitNulls = true is the DEFAULT and MUST stay that way. Setting
        // it to false would silently collapse AtField.Null into AtField.Missing
        // on the wire. We set it explicitly here as documentation.
        explicitNulls = true
    }

    @Test
    fun decode_absentKey_becomesMissing() {
        val decoded = json.decodeFromString<Correct>("""{}""")
        assertEquals(AtField.Missing, decoded.a)
        assertEquals(AtField.Missing, decoded.b)
        assertEquals(AtField.Missing, decoded.c)
    }

    @Test
    fun decode_explicitNull_becomesNull() {
        val decoded = json.decodeFromString<Correct>("""{"a":null}""")
        assertEquals(AtField.Null, decoded.a)
        assertEquals(AtField.Missing, decoded.b)
    }

    @Test
    fun decode_concreteValue_becomesDefined() {
        val decoded = json.decodeFromString<Correct>("""{"a":"hello"}""")
        assertEquals(AtField.Defined("hello"), decoded.a)
    }

    @Test
    fun encode_missing_omitsKey() {
        val encoded = json.encodeToString(Correct.serializer(), Correct())
        assertEquals("""{}""", encoded)
    }

    @Test
    fun encode_null_emitsJsonNull() {
        val encoded = json.encodeToString(
            Correct.serializer(),
            Correct(a = AtField.Null),
        )
        assertEquals("""{"a":null}""", encoded)
    }

    @Test
    fun encode_defined_emitsValue() {
        val encoded = json.encodeToString(
            Correct.serializer(),
            Correct(a = AtField.Defined("hello")),
        )
        assertEquals("""{"a":"hello"}""", encoded)
    }

    @Test
    fun encode_allThreeStates_atOnce() {
        val encoded = json.encodeToString(
            Correct.serializer(),
            Correct(
                a = AtField.Missing,
                b = AtField.Null,
                c = AtField.Defined("hello"),
            ),
        )
        assertEquals("""{"b":null,"c":"hello"}""", encoded)
    }

    @Test
    fun roundTrip_preservesAllThreeStates() {
        val original = Correct(
            a = AtField.Missing,
            b = AtField.Null,
            c = AtField.Defined("hello"),
        )
        val wire = json.encodeToString(Correct.serializer(), original)
        val decoded = json.decodeFromString<Correct>(wire)
        assertEquals(original, decoded)
    }

    // Deliberately does NOT carry `@Serializable(with = AtFieldSerializer::class)`
    // on the AtField field — reproduces the bug surfaced by the Android sample
    // where the compiler-synthesized serializer falls back to polymorphic
    // dispatch on the sealed `AtField` type and fails at runtime on `Defined<T>`
    // ("Serializer for subclass 'Defined' is not found in the polymorphic scope
    // of 'AtField'"). The generator MUST emit the per-field annotation for
    // every AtField field or the emitted code is broken.
    @Serializable
    private data class BrokenWithoutAnnotation(
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val limit: AtField<Long> = AtField.Missing,
    )

    @Test
    fun present_wrapsValueAsDefined() {
        val wrapped: AtField.Defined<String> = present("hello")
        assertEquals(AtField.Defined("hello"), wrapped)
    }

    @Test
    fun presentOrNull_mapsNonNullToDefined() {
        val wrapped: AtField<String> = presentOrNull("hello")
        assertEquals(AtField.Defined("hello"), wrapped)
    }

    @Test
    fun presentOrNull_mapsNullToExplicitClear() {
        val wrapped: AtField<String> = presentOrNull(null)
        assertEquals(AtField.Null, wrapped)
    }

    @Test
    fun encode_definedWithoutSerializableWithAnnotation_failsLoud() {
        val err = assertFailsWith<SerializationException> {
            json.encodeToString(
                BrokenWithoutAnnotation.serializer(),
                BrokenWithoutAnnotation(limit = AtField.Defined(50L)),
            )
        }
        assertTrue(
            err.message?.contains("AtField") == true,
            "expected polymorphic-scope error mentioning AtField, got: ${err.message}",
        )
    }

    @Test
    fun encode_missingWithoutEncodeDefaultAnnotation_failsLoud() {
        // Under a Json config with encodeDefaults=true AND no @EncodeDefault(NEVER)
        // on the field, kotlinx-serialization asks the field serializer to encode
        // AtField.Missing. AtFieldSerializer detects this and throws with a
        // diagnostic pointing at the missing annotation.
        val strictJson = Json {
            explicitNulls = true
            encodeDefaults = true
        }
        val err = assertFailsWith<SerializationException> {
            strictJson.encodeToString(MissingAnnotation.serializer(), MissingAnnotation())
        }
        assertTrue(
            err.message?.contains("EncodeDefault") == true,
            "diagnostic must mention @EncodeDefault, got: ${err.message}",
        )
    }
}
