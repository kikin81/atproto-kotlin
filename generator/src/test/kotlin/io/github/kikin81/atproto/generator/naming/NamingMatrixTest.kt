package io.github.kikin81.atproto.generator.naming

import io.github.kikin81.atproto.generator.ir.HttpBody
import io.github.kikin81.atproto.generator.ir.ObjectDef
import io.github.kikin81.atproto.generator.ir.ObjectType
import io.github.kikin81.atproto.generator.ir.ParamsType
import io.github.kikin81.atproto.generator.ir.ProcedureDef
import io.github.kikin81.atproto.generator.ir.QueryDef
import io.github.kikin81.atproto.generator.ir.RecordDef
import io.github.kikin81.atproto.generator.ir.StringType
import io.github.kikin81.atproto.generator.ir.TokenDef
import io.github.kikin81.atproto.generator.resolved.DefKey
import io.github.kikin81.atproto.generator.resolved.Nsid
import io.github.kikin81.atproto.generator.resolved.UsageContext
import io.github.kikin81.atproto.generator.verify.FqName
import kotlin.test.Test
import kotlin.test.assertEquals

class NamingMatrixTest {
    private val n = NamingMatrix()

    @Test fun packageDropsLastSegment() {
        assertEquals("io.github.kikin81.atproto.app.bsky.feed", n.packageFor(Nsid("app.bsky.feed.post")))
        assertEquals("io.github.kikin81.atproto.com.atproto.repo", n.packageFor(Nsid("com.atproto.repo.strongRef")))
    }

    @Test fun defsAndSiblingFileLandInSamePackage() {
        val a = n.packageFor(Nsid("app.bsky.actor.defs"))
        val b = n.packageFor(Nsid("app.bsky.actor.profile"))
        assertEquals(a, b)
        assertEquals("io.github.kikin81.atproto.app.bsky.actor", a)
    }

    @Test fun recordPrimaryName() {
        val key = DefKey(Nsid("app.bsky.feed.post"), "main")
        val def = RecordDef(key = "tid", record = ObjectType())
        val emitted = n.namesFor(key, def)
        assertEquals(
            listOf(EmittedClass(key, NameRole.Primary, FqName("io.github.kikin81.atproto.app.bsky.feed", "Post"))),
            emitted,
        )
    }

    @Test fun queryEmitsRequestAndResponse() {
        val key = DefKey(Nsid("app.bsky.feed.getTimeline"), "main")
        val def = QueryDef(
            parameters = ParamsType(properties = mapOf("limit" to StringType())),
            output = HttpBody(encoding = "application/json", schema = ObjectType()),
        )
        val emitted = n.namesFor(key, def).map { it.fqName.simpleName }
        assertEquals(listOf("GetTimelineRequest", "GetTimelineResponse"), emitted)
    }

    @Test fun queryWithoutParamsStillEmitsResponseOnly() {
        val key = DefKey(Nsid("app.bsky.feed.getTimeline"), "main")
        val def = QueryDef(output = HttpBody(encoding = "application/json", schema = ObjectType()))
        val emitted = n.namesFor(key, def).map { it.role }
        assertEquals(listOf(NameRole.Response), emitted)
    }

    @Test fun procedureEmitsRequestAndResponse() {
        val key = DefKey(Nsid("com.atproto.server.createSession"), "main")
        val def = ProcedureDef(
            input = HttpBody(encoding = "application/json", schema = ObjectType()),
            output = HttpBody(encoding = "application/json", schema = ObjectType()),
        )
        val emitted = n.namesFor(key, def).map { it.fqName.simpleName }
        assertEquals(listOf("CreateSessionRequest", "CreateSessionResponse"), emitted)
    }

    @Test fun secondaryInDefsFileIsBareFragment() {
        val key = DefKey(Nsid("app.bsky.actor.defs"), "profileView")
        val def = ObjectDef(
            required = listOf("did", "handle"),
            properties = mapOf(
                "did" to StringType(format = "did"),
                "handle" to StringType(format = "handle"),
            ),
        )
        val emitted = n.namesFor(key, def).single()
        assertEquals(FqName("io.github.kikin81.atproto.app.bsky.actor", "ProfileView"), emitted.fqName)
    }

    @Test fun secondaryInNonDefsFileIsPrefixedWithPrimary() {
        val key = DefKey(Nsid("app.bsky.feed.post"), "replyRef")
        val def = ObjectDef(
            required = listOf("root", "parent"),
            properties = mapOf(
                "root" to StringType(),
                "parent" to StringType(),
            ),
        )
        val emitted = n.namesFor(key, def).single()
        assertEquals(FqName("io.github.kikin81.atproto.app.bsky.feed", "PostReplyRef"), emitted.fqName)
    }

    @Test fun contextualSplitFiresWhenBothAndHasOptionalFields() {
        val key = DefKey(Nsid("x.shared"), "main")
        val def = ObjectDef(
            required = listOf("a"),
            properties = mapOf("a" to StringType(), "b" to StringType()),
        )
        val emitted = n.namesFor(key, def) { UsageContext.Both }
        assertEquals(listOf(NameRole.Primary, NameRole.Input), emitted.map { it.role })
        assertEquals("Shared", emitted[0].fqName.simpleName)
        assertEquals("SharedInput", emitted[1].fqName.simpleName)
    }

    @Test fun contextualSplitSuppressedWhenAllFieldsRequired() {
        // strongRef has `uri` and `cid`, both required — same shape in both contexts, so single class.
        val key = DefKey(Nsid("com.atproto.repo.strongRef"), "main")
        val def = ObjectDef(
            required = listOf("uri", "cid"),
            properties = mapOf(
                "uri" to StringType(format = "at-uri"),
                "cid" to StringType(format = "cid"),
            ),
        )
        val emitted = n.namesFor(key, def) { UsageContext.Both }
        assertEquals(1, emitted.size)
        assertEquals("StrongRef", emitted[0].fqName.simpleName)
    }

    @Test fun contextualSplitSuppressedWhenSingleContext() {
        val key = DefKey(Nsid("x.shared"), "main")
        val def = ObjectDef(
            required = listOf("a"),
            properties = mapOf("a" to StringType(), "b" to StringType()),
        )
        val mutationOnly = n.namesFor(key, def) { UsageContext.Mutation }
        val readOnly = n.namesFor(key, def) { UsageContext.Read }
        assertEquals(1, mutationOnly.size)
        assertEquals(1, readOnly.size)
    }

    @Test fun tokenDefGetsPrimaryName() {
        val key = DefKey(Nsid("app.bsky.graph.defs"), "modlist")
        val emitted = n.namesFor(key, TokenDef()).single()
        assertEquals(FqName("io.github.kikin81.atproto.app.bsky.graph", "Modlist"), emitted.fqName)
    }
}
