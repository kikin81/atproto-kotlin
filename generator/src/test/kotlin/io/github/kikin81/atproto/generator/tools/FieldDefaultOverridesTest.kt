package io.github.kikin81.atproto.generator.tools

import io.github.kikin81.atproto.generator.emit.CodeGenerator
import io.github.kikin81.atproto.generator.ir.LexiconDocument
import io.github.kikin81.atproto.generator.ir.ObjectDef
import io.github.kikin81.atproto.generator.ir.StringType
import io.github.kikin81.atproto.generator.parser.LexiconParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldDefaultOverridesTest {
    private val parser = LexiconParser()

    /** A minimal chat.bsky.actor.defs with profileViewBasic requiring did + handle. */
    private val profileDocs =
        listOf(
            parser.parse(
                """
                {
                  "lexicon": 1,
                  "id": "chat.bsky.actor.defs",
                  "defs": {
                    "profileViewBasic": {
                      "type": "object",
                      "required": ["did", "handle"],
                      "properties": {
                        "did": { "type": "string", "format": "did" },
                        "handle": { "type": "string", "format": "handle" },
                        "displayName": { "type": "string" }
                      }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

    private fun objectDef(
        docs: List<LexiconDocument>,
        nsid: String,
        def: String,
    ): ObjectDef = docs.single { it.id == nsid }.defs.getValue(def) as ObjectDef

    @Test
    fun `parse reads def, defaults, and reason`() {
        val manifest =
            parseFieldDefaultOverrides(
                """
                {
                  "version": 1,
                  "overrides": [
                    { "def": "chat.bsky.actor.defs#profileViewBasic", "defaults": { "handle": "handle.invalid" }, "reason": "server omits it" }
                  ]
                }
                """.trimIndent(),
            )
        assertEquals(1, manifest.overrides.size)
        assertEquals("chat.bsky.actor.defs#profileViewBasic", manifest.overrides[0].def)
        assertEquals(mapOf("handle" to "handle.invalid"), manifest.overrides[0].defaults)
        assertEquals("server omits it", manifest.overrides[0].reason)
    }

    @Test
    fun `apply sets injectedDefault and keeps the field required and present`() {
        val manifest =
            FieldDefaultOverrideManifest(
                overrides = listOf(FieldDefaultOverride("chat.bsky.actor.defs#profileViewBasic", mapOf("handle" to "handle.invalid"))),
            )

        val result = applyFieldDefaultOverrides(profileDocs, manifest)

        val def = objectDef(result, "chat.bsky.actor.defs", "profileViewBasic")
        // required is untouched (the field stays non-null; the default makes it optional to kotlinx).
        assertEquals(listOf("did", "handle"), def.required)
        assertEquals("handle.invalid", (def.properties.getValue("handle") as StringType).injectedDefault)
    }

    @Test
    fun `the generator emits the field non-null defaulted to the injected value`() {
        val overridden =
            applyFieldDefaultOverrides(
                profileDocs,
                FieldDefaultOverrideManifest(
                    overrides = listOf(FieldDefaultOverride("chat.bsky.actor.defs#profileViewBasic", mapOf("handle" to "handle.invalid"))),
                ),
            )

        val source = CodeGenerator().generate(overridden).single { it.name == "ProfileViewBasic" }.toString()

        // Non-null, defaulted to the value-class-wrapped sentinel — not nullable.
        assertTrue("""public val handle: Handle = Handle("handle.invalid")""" in source, source)
        assertFalse("handle: Handle?" in source, "handle must not be nullable")
    }

    @Test
    fun `apply with no overrides returns the docs unchanged`() {
        val result = applyFieldDefaultOverrides(profileDocs, FieldDefaultOverrideManifest())
        assertEquals(null, (objectDef(result, "chat.bsky.actor.defs", "profileViewBasic").properties.getValue("handle") as StringType).injectedDefault)
    }

    @Test
    fun `apply fails fast on an unknown lexicon`() {
        val manifest =
            FieldDefaultOverrideManifest(overrides = listOf(FieldDefaultOverride("does.not.exist#x", mapOf("y" to "z"))))
        assertFailsWith<IllegalArgumentException> { applyFieldDefaultOverrides(profileDocs, manifest) }
    }

    @Test
    fun `apply fails fast on an unknown def`() {
        val manifest =
            FieldDefaultOverrideManifest(
                overrides = listOf(FieldDefaultOverride("chat.bsky.actor.defs#nope", mapOf("handle" to "x"))),
            )
        assertFailsWith<IllegalArgumentException> { applyFieldDefaultOverrides(profileDocs, manifest) }
    }

    @Test
    fun `apply fails fast on a property that is not a string`() {
        // 'did' is a string-format field, so use a non-string by adding an object property.
        val docs =
            listOf(
                parser.parse(
                    """
                    {
                      "lexicon": 1,
                      "id": "x.y.z",
                      "defs": {
                        "main": {
                          "type": "object",
                          "required": ["count"],
                          "properties": { "count": { "type": "integer" } }
                        }
                      }
                    }
                    """.trimIndent(),
                ),
            )
        val manifest = FieldDefaultOverrideManifest(overrides = listOf(FieldDefaultOverride("x.y.z#main", mapOf("count" to "0"))))
        assertFailsWith<IllegalArgumentException> { applyFieldDefaultOverrides(docs, manifest) }
    }

    @Test
    fun `apply fails fast on an unknown property`() {
        val manifest =
            FieldDefaultOverrideManifest(
                overrides = listOf(FieldDefaultOverride("chat.bsky.actor.defs#profileViewBasic", mapOf("nonexistent" to "x"))),
            )
        assertFailsWith<IllegalArgumentException> { applyFieldDefaultOverrides(profileDocs, manifest) }
    }

    @Test
    fun `apply fails fast on a malformed id without a def fragment`() {
        val manifest =
            FieldDefaultOverrideManifest(overrides = listOf(FieldDefaultOverride("chat.bsky.actor.defs", mapOf("handle" to "x"))))
        assertFailsWith<IllegalArgumentException> { applyFieldDefaultOverrides(profileDocs, manifest) }
    }
}
