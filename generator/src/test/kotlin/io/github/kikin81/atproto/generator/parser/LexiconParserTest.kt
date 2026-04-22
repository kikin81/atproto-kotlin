package io.github.kikin81.atproto.generator.parser

import io.github.kikin81.atproto.generator.ir.ArrayType
import io.github.kikin81.atproto.generator.ir.BlobType
import io.github.kikin81.atproto.generator.ir.BooleanType
import io.github.kikin81.atproto.generator.ir.BytesType
import io.github.kikin81.atproto.generator.ir.CidLinkType
import io.github.kikin81.atproto.generator.ir.IntegerType
import io.github.kikin81.atproto.generator.ir.NullType
import io.github.kikin81.atproto.generator.ir.ObjectDef
import io.github.kikin81.atproto.generator.ir.ObjectType
import io.github.kikin81.atproto.generator.ir.ProcedureDef
import io.github.kikin81.atproto.generator.ir.QueryDef
import io.github.kikin81.atproto.generator.ir.RecordDef
import io.github.kikin81.atproto.generator.ir.RefType
import io.github.kikin81.atproto.generator.ir.StringType
import io.github.kikin81.atproto.generator.ir.SubscriptionDef
import io.github.kikin81.atproto.generator.ir.TokenDef
import io.github.kikin81.atproto.generator.ir.UnionType
import io.github.kikin81.atproto.generator.ir.UnknownType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LexiconParserTest {
    private val parser = LexiconParser()

    @Test fun parsesRecordLikeFeedLike() {
        val json = """
            {
              "lexicon": 1,
              "id": "app.bsky.feed.like",
              "defs": {
                "main": {
                  "type": "record",
                  "key": "tid",
                  "record": {
                    "type": "object",
                    "required": ["subject", "createdAt"],
                    "properties": {
                      "subject": { "type": "ref", "ref": "com.atproto.repo.strongRef" },
                      "createdAt": { "type": "string", "format": "datetime" }
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val doc = parser.parse(json)
        assertEquals("app.bsky.feed.like", doc.id)
        val main = assertIs<RecordDef>(doc.defs["main"])
        assertEquals("tid", main.key)
        assertEquals(listOf("subject", "createdAt"), main.record.required)
        val subject = assertIs<RefType>(main.record.properties["subject"])
        assertEquals("com.atproto.repo.strongRef", subject.ref)
        val createdAt = assertIs<StringType>(main.record.properties["createdAt"])
        assertEquals("datetime", createdAt.format)
    }

    @Test fun unionClosedVsOpen() {
        val closedJson = """{"lexicon":1,"id":"x.y","defs":{"main":{"type":"object","properties":{"f":{"type":"union","refs":["a.b"],"closed":true}}}}}"""
        val openJson = """{"lexicon":1,"id":"x.y","defs":{"main":{"type":"object","properties":{"f":{"type":"union","refs":["a.b"]}}}}}"""
        val closedU = assertIs<UnionType>(assertIs<ObjectDef>(parser.parse(closedJson).defs["main"]).properties["f"])
        val openU = assertIs<UnionType>(assertIs<ObjectDef>(parser.parse(openJson).defs["main"]).properties["f"])
        assertTrue(closedU.closed)
        assertTrue(!openU.closed)
    }

    @Test fun integerDefaultPreservedAsJsonElement() {
        val json = """
            {"lexicon":1,"id":"x.y","defs":{"main":{"type":"object","properties":{
              "limit":{"type":"integer","default":50,"maximum":100}
            }}}}
        """.trimIndent()
        val intF = assertIs<IntegerType>(assertIs<ObjectDef>(parser.parse(json).defs["main"]).properties["limit"])
        assertEquals(100L, intF.maximum)
        val def = assertIs<JsonPrimitive>(intF.default)
        assertEquals(50L, def.longOrNull)
    }

    @Test fun parsesEveryPrimitiveAndContainer() {
        val json = """
            {"lexicon":1,"id":"x.y","defs":{"main":{"type":"object","properties":{
              "n":{"type":"null"},
              "b":{"type":"boolean"},
              "i":{"type":"integer"},
              "s":{"type":"string"},
              "by":{"type":"bytes"},
              "c":{"type":"cid-link"},
              "a":{"type":"array","items":{"type":"string"}},
              "o":{"type":"object","properties":{}},
              "bl":{"type":"blob","accept":["image/*"],"maxSize":1000},
              "u":{"type":"unknown"},
              "r":{"type":"ref","ref":"a.b"},
              "un":{"type":"union","refs":["a.b","c.d#e"]}
            }}}}
        """.trimIndent()
        val props = assertIs<ObjectDef>(parser.parse(json).defs["main"]).properties
        assertIs<NullType>(props["n"])
        assertIs<BooleanType>(props["b"])
        assertIs<IntegerType>(props["i"])
        assertIs<StringType>(props["s"])
        assertIs<BytesType>(props["by"])
        assertIs<CidLinkType>(props["c"])
        val arr = assertIs<ArrayType>(props["a"])
        assertIs<StringType>(arr.items)
        assertIs<ObjectType>(props["o"])
        assertIs<BlobType>(props["bl"])
        assertIs<UnknownType>(props["u"])
        assertIs<RefType>(props["r"])
        val un = assertIs<UnionType>(props["un"])
        assertEquals(listOf("a.b", "c.d#e"), un.refs)
    }

    @Test fun parsesQueryWithParamsAndOutput() {
        val json = """
            {"lexicon":1,"id":"app.bsky.feed.getTimeline","defs":{"main":{
              "type":"query",
              "parameters":{"type":"params","required":["limit"],"properties":{"limit":{"type":"integer"}}},
              "output":{"encoding":"application/json","schema":{"type":"object","properties":{"cursor":{"type":"string"}}}}
            }}}
        """.trimIndent()
        val q = assertIs<QueryDef>(parser.parse(json).defs["main"])
        val params = assertNotNull(q.parameters)
        assertEquals(listOf("limit"), params.required)
        assertNotNull(q.output)
        assertEquals("application/json", q.output!!.encoding)
        assertIs<ObjectType>(q.output!!.schema)
    }

    @Test fun parsesProcedureAndSubscriptionAndTokenAndObjectDef() {
        val proc = """{"lexicon":1,"id":"x.y","defs":{"main":{"type":"procedure","input":{"encoding":"application/json","schema":{"type":"object"}},"errors":[{"name":"Oops"}]}}}"""
        assertIs<ProcedureDef>(parser.parse(proc).defs["main"])

        val sub = """{"lexicon":1,"id":"x.y","defs":{"main":{"type":"subscription","parameters":{"type":"params","properties":{}}}}}"""
        assertIs<SubscriptionDef>(parser.parse(sub).defs["main"])

        val tok = """{"lexicon":1,"id":"x.y","defs":{"foo":{"type":"token","description":"a thing"}}}"""
        val t = assertIs<TokenDef>(parser.parse(tok).defs["foo"])
        assertEquals("a thing", t.description)

        val obj = """{"lexicon":1,"id":"x.y","defs":{"viewRecord":{"type":"object","properties":{"uri":{"type":"string","format":"at-uri"}}}}}"""
        assertIs<ObjectDef>(parser.parse(obj).defs["viewRecord"])
    }

    @Test fun refStoredRaw() {
        val json = """{"lexicon":1,"id":"x.y","defs":{"main":{"type":"object","properties":{"r":{"type":"ref","ref":"#localFrag"}}}}}"""
        val r = assertIs<RefType>(assertIs<ObjectDef>(parser.parse(json).defs["main"]).properties["r"])
        assertEquals("#localFrag", r.ref)
    }
}
