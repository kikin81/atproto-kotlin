package io.github.kikin81.atproto.generator.resolved

import io.github.kikin81.atproto.generator.parser.LexiconParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DefKeyTest {
    @Test fun bareNsidResolvesToMain() {
        assertEquals(
            DefKey(Nsid("com.atproto.repo.strongRef"), "main"),
            DefKey.parse("com.atproto.repo.strongRef"),
        )
    }

    @Test fun crossFileFragment() {
        assertEquals(
            DefKey(Nsid("app.bsky.actor.defs"), "profileViewBasic"),
            DefKey.parse("app.bsky.actor.defs#profileViewBasic"),
        )
    }

    @Test fun localFragmentResolvesAgainstOrigin() {
        val key = DefKey.resolve("#viewRecord", Nsid("app.bsky.embed.record"))
        assertEquals(DefKey(Nsid("app.bsky.embed.record"), "viewRecord"), key)
    }

    @Test fun toStringCanonical() {
        assertEquals(
            "com.atproto.repo.strongRef",
            DefKey(Nsid("com.atproto.repo.strongRef"), "main").toString(),
        )
        assertEquals(
            "app.bsky.embed.record#viewRecord",
            DefKey(Nsid("app.bsky.embed.record"), "viewRecord").toString(),
        )
    }

    @Test fun localRefWithoutOriginIsRejected() {
        assertFailsWith<IllegalArgumentException> { DefKey.parse("#local") }
    }
}

class RefResolverTest {
    private val parser = LexiconParser()

    @Test fun mutuallyRecursiveLexiconPairResolves() {
        val recordDoc = parser.parse(
            """
            {"lexicon":1,"id":"app.bsky.embed.record","defs":{
              "main":{"type":"object","properties":{"record":{"type":"ref","ref":"#viewRecord"}}},
              "viewRecord":{"type":"object","properties":{
                "embeds":{"type":"array","items":{"type":"union","refs":["app.bsky.embed.recordWithMedia#view","#view"]}}
              }},
              "view":{"type":"object","properties":{"r":{"type":"ref","ref":"#viewRecord"}}}
            }}
            """.trimIndent(),
        )
        val rwmDoc = parser.parse(
            """
            {"lexicon":1,"id":"app.bsky.embed.recordWithMedia","defs":{
              "view":{"type":"object","properties":{"record":{"type":"ref","ref":"app.bsky.embed.record#view"}}}
            }}
            """.trimIndent(),
        )
        val symbols = SymbolTable.build(listOf(recordDoc, rwmDoc))
        assertTrue(DefKey(Nsid("app.bsky.embed.record"), "view") in symbols)
        assertTrue(DefKey(Nsid("app.bsky.embed.recordWithMedia"), "view") in symbols)
        RefResolver(symbols).validate()
    }

    @Test fun missingRefHalts() {
        val doc = parser.parse(
            """
            {"lexicon":1,"id":"x.y","defs":{
              "main":{"type":"object","properties":{"r":{"type":"ref","ref":"does.not.exist#nope"}}}
            }}
            """.trimIndent(),
        )
        val symbols = SymbolTable.build(listOf(doc))
        val ex = assertFailsWith<UnresolvedRefException> { RefResolver(symbols).validate() }
        assertTrue(ex.message!!.contains("does.not.exist#nope"))
        assertTrue(ex.message!!.contains("x.y"))
    }

    @Test fun missingUnionMemberHalts() {
        val doc = parser.parse(
            """
            {"lexicon":1,"id":"x.y","defs":{
              "main":{"type":"object","properties":{"u":{"type":"union","refs":["missing.thing"]}}}
            }}
            """.trimIndent(),
        )
        assertFailsWith<UnresolvedRefException> {
            RefResolver(SymbolTable.build(listOf(doc))).validate()
        }
    }
}

class ContextTaggingTest {
    private val parser = LexiconParser()

    @Test fun recordTagsTransitiveRefsAsMutation() {
        val post = parser.parse(
            """
            {"lexicon":1,"id":"app.bsky.feed.post","defs":{
              "main":{"type":"record","key":"tid","record":{"type":"object","properties":{
                "subject":{"type":"ref","ref":"com.atproto.repo.strongRef"}
              }}}
            }}
            """.trimIndent(),
        )
        val strongRef = parser.parse(
            """
            {"lexicon":1,"id":"com.atproto.repo.strongRef","defs":{
              "main":{"type":"object","required":["uri","cid"],"properties":{
                "uri":{"type":"string","format":"at-uri"},
                "cid":{"type":"string","format":"cid"}
              }}
            }}
            """.trimIndent(),
        )
        val symbols = SymbolTable.build(listOf(post, strongRef))
        val tags = ContextTagger(symbols).tag()
        assertEquals(UsageContext.Mutation, tags[DefKey(Nsid("com.atproto.repo.strongRef"), "main")])
    }

    @Test fun objectReachedFromBothContextsIsBoth() {
        val getFoo = parser.parse(
            """
            {"lexicon":1,"id":"x.getFoo","defs":{"main":{
              "type":"query",
              "parameters":{"type":"params","properties":{"p":{"type":"ref","ref":"x.shared"}}},
              "output":{"encoding":"application/json","schema":{"type":"object","properties":{"o":{"type":"ref","ref":"x.shared"}}}}
            }}}
            """.trimIndent(),
        )
        val shared = parser.parse(
            """
            {"lexicon":1,"id":"x.shared","defs":{"main":{"type":"object","properties":{"v":{"type":"string"}}}}}
            """.trimIndent(),
        )
        val symbols = SymbolTable.build(listOf(getFoo, shared))
        val tags = ContextTagger(symbols).tag()
        assertEquals(UsageContext.Both, tags[DefKey(Nsid("x.shared"), "main")])
    }

    @Test fun viewOnlyIsRead() {
        val getFoo = parser.parse(
            """
            {"lexicon":1,"id":"x.getFoo","defs":{"main":{
              "type":"query",
              "output":{"encoding":"application/json","schema":{"type":"object","properties":{"v":{"type":"ref","ref":"x.viewDef"}}}}
            }}}
            """.trimIndent(),
        )
        val viewDef = parser.parse(
            """
            {"lexicon":1,"id":"x.viewDef","defs":{"main":{"type":"object","properties":{"label":{"type":"string"}}}}}
            """.trimIndent(),
        )
        val tags = ContextTagger(SymbolTable.build(listOf(getFoo, viewDef))).tag()
        assertEquals(UsageContext.Read, tags[DefKey(Nsid("x.viewDef"), "main")])
    }

    @Test fun mutuallyRecursiveDoesNotStackOverflow() {
        val a = parser.parse(
            """
            {"lexicon":1,"id":"a.n","defs":{
              "main":{"type":"record","key":"tid","record":{"type":"object","properties":{"r":{"type":"ref","ref":"#sub"}}}},
              "sub":{"type":"object","properties":{"peer":{"type":"ref","ref":"b.n#sub"}}}
            }}
            """.trimIndent(),
        )
        val b = parser.parse(
            """
            {"lexicon":1,"id":"b.n","defs":{
              "sub":{"type":"object","properties":{"back":{"type":"ref","ref":"a.n#sub"}}}
            }}
            """.trimIndent(),
        )
        val symbols = SymbolTable.build(listOf(a, b))
        RefResolver(symbols).validate()
        val tags = ContextTagger(symbols).tag()
        assertEquals(UsageContext.Mutation, tags[DefKey(Nsid("a.n"), "sub")])
        assertEquals(UsageContext.Mutation, tags[DefKey(Nsid("b.n"), "sub")])
    }
}
