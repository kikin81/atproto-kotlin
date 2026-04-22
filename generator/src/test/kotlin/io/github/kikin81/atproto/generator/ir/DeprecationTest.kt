package io.github.kikin81.atproto.generator.ir

import io.github.kikin81.atproto.generator.parser.LexiconParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeprecationTest {
    private val parser = LexiconParser()

    @Test
    fun deprecatedBooleanTrue() {
        val doc = parser.parse(
            """
            {
              "lexicon": 1,
              "id": "com.example.deprecated",
              "defs": {
                "main": {
                  "type": "query",
                  "deprecated": true
                }
              }
            }
            """,
        )
        val def = assertIs<QueryDef>(doc.defs["main"])
        assertTrue(def.deprecated)
        assertNull(def.deprecatedMessage)
    }

    @Test
    fun deprecatedWithReason() {
        val doc = parser.parse(
            """
            {
              "lexicon": 1,
              "id": "com.example.deprecated",
              "defs": {
                "main": {
                  "type": "query",
                  "deprecated": "Use com.example.newEndpoint instead"
                }
              }
            }
            """,
        )
        val def = assertIs<QueryDef>(doc.defs["main"])
        assertTrue(def.deprecated)
        assertEquals("Use com.example.newEndpoint instead", def.deprecatedMessage)
    }

    @Test
    fun notDeprecated() {
        val doc = parser.parse(
            """
            {
              "lexicon": 1,
              "id": "com.example.active",
              "defs": {
                "main": {
                  "type": "query"
                }
              }
            }
            """,
        )
        val def = assertIs<QueryDef>(doc.defs["main"])
        assertFalse(def.deprecated)
        assertNull(def.deprecatedMessage)
    }

    @Test
    fun deprecatedFieldType() {
        val doc = parser.parse(
            """
            {
              "lexicon": 1,
              "id": "com.example.obj",
              "defs": {
                "main": {
                  "type": "object",
                  "properties": {
                    "oldField": {
                      "type": "string",
                      "deprecated": "Use newField instead"
                    }
                  }
                }
              }
            }
            """,
        )
        val def = assertIs<ObjectDef>(doc.defs["main"])
        val field = assertIs<StringType>(def.properties["oldField"])
        assertTrue(field.deprecated)
        assertEquals("Use newField instead", field.deprecatedMessage)
    }
}
