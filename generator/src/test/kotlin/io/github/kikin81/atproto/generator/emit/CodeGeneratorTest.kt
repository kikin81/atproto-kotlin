package io.github.kikin81.atproto.generator.emit

import io.github.kikin81.atproto.generator.parser.LexiconParser
import io.github.kikin81.atproto.generator.verify.VerificationFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodeGeneratorTest {

    private val parser = LexiconParser()

    private fun generate(vararg jsons: String): List<com.squareup.kotlinpoet.FileSpec> {
        val docs = jsons.map { parser.parse(it) }
        return CodeGenerator().generate(docs)
    }

    private fun renderAll(files: List<com.squareup.kotlinpoet.FileSpec>): String = files.sortedBy { "${it.packageName}.${it.name}" }.joinToString("\n---\n") { it.toString() }

    // ----------------------------------------------------------------------
    // Fixtures
    // ----------------------------------------------------------------------

    private val strongRefJson = """
        {
          "lexicon": 1,
          "id": "com.atproto.repo.strongRef",
          "defs": {
            "main": {
              "type": "object",
              "required": ["uri", "cid"],
              "properties": {
                "uri": { "type": "string", "format": "at-uri" },
                "cid": { "type": "string", "format": "cid" }
              }
            }
          }
        }
    """.trimIndent()

    private val simplePostJson = """
        {
          "lexicon": 1,
          "id": "app.bsky.feed.post",
          "defs": {
            "main": {
              "type": "record",
              "key": "tid",
              "record": {
                "type": "object",
                "required": ["text", "createdAt"],
                "properties": {
                  "text": { "type": "string" },
                  "createdAt": { "type": "string", "format": "datetime" },
                  "reply": { "type": "ref", "ref": "com.atproto.repo.strongRef" }
                }
              }
            }
          }
        }
    """.trimIndent()

    private val getTimelineJson = """
        {
          "lexicon": 1,
          "id": "app.bsky.feed.getTimeline",
          "defs": {
            "main": {
              "type": "query",
              "parameters": {
                "type": "params",
                "required": ["algorithm"],
                "properties": {
                  "algorithm": { "type": "string" },
                  "limit": { "type": "integer" }
                }
              },
              "output": {
                "encoding": "application/json",
                "schema": {
                  "type": "object",
                  "required": ["cursor"],
                  "properties": {
                    "cursor": { "type": "string" },
                    "feedLength": { "type": "integer" }
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    private val createPostProcedureJson = """
        {
          "lexicon": 1,
          "id": "app.bsky.feed.createPost",
          "defs": {
            "main": {
              "type": "procedure",
              "input": {
                "encoding": "application/json",
                "schema": {
                  "type": "object",
                  "required": ["text"],
                  "properties": {
                    "text": { "type": "string" },
                    "langs": { "type": "array", "items": { "type": "string", "format": "language" } }
                  }
                }
              },
              "output": {
                "encoding": "application/json",
                "schema": {
                  "type": "object",
                  "required": ["uri"],
                  "properties": {
                    "uri": { "type": "string", "format": "at-uri" }
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    // A shared object referenced from both a mutation root (record) and a read
    // root (query output) → should produce a contextual split Foo + FooInput.
    private val sharedSplitObjectJson = """
        {
          "lexicon": 1,
          "id": "app.bsky.actor.profile",
          "defs": {
            "main": {
              "type": "record",
              "key": "self",
              "record": {
                "type": "object",
                "required": ["displayName"],
                "properties": {
                  "displayName": { "type": "string" },
                  "settings": { "type": "ref", "ref": "#settings" }
                }
              }
            },
            "settings": {
              "type": "object",
              "required": ["theme"],
              "properties": {
                "theme": { "type": "string" },
                "accent": { "type": "string" }
              }
            }
          }
        }
    """.trimIndent()

    private val readSideForSplitJson = """
        {
          "lexicon": 1,
          "id": "app.bsky.actor.getProfile",
          "defs": {
            "main": {
              "type": "query",
              "parameters": {
                "type": "params",
                "required": ["actor"],
                "properties": { "actor": { "type": "string" } }
              },
              "output": {
                "encoding": "application/json",
                "schema": {
                  "type": "ref",
                  "ref": "app.bsky.actor.profile#settings"
                }
              }
            }
          }
        }
    """.trimIndent()

    private val unionJson = """
        {
          "lexicon": 1,
          "id": "app.bsky.embed.post",
          "defs": {
            "main": {
              "type": "object",
              "required": ["embed"],
              "properties": {
                "embed": {
                  "type": "union",
                  "refs": [
                    "app.bsky.embed.post#images",
                    "app.bsky.embed.post#video"
                  ]
                }
              }
            },
            "images": {
              "type": "object",
              "required": ["count"],
              "properties": {
                "count": { "type": "integer" }
              }
            },
            "video": {
              "type": "object",
              "required": ["url"],
              "properties": {
                "url": { "type": "string", "format": "uri" }
              }
            }
          }
        }
    """.trimIndent()

    private val subscriptionJson = """
        {
          "lexicon": 1,
          "id": "com.atproto.sync.subscribeRepos",
          "defs": {
            "main": {
              "type": "subscription",
              "parameters": {
                "type": "params",
                "properties": { "cursor": { "type": "integer" } }
              },
              "message": {
                "schema": {
                  "type": "object",
                  "required": ["seq"],
                  "properties": { "seq": { "type": "integer" } }
                }
              }
            }
          }
        }
    """.trimIndent()

    // ----------------------------------------------------------------------
    // Tests
    // ----------------------------------------------------------------------

    @Test
    fun `simple record with ref emits class with correct fields and package`() {
        val files = generate(strongRefJson, simplePostJson)
        val text = renderAll(files)

        // Post class was generated in the right package
        val postFile = files.firstOrNull { it.name == "Post" && it.packageName == "io.github.kikin81.atproto.app.bsky.feed" }
        assertNotNull(postFile, "Post file missing. Got:\n$text")
        val postStr = postFile.toString()
        assertTrue("text: String" in postStr, "expected String text field:\n$postStr")
        assertTrue("Datetime" in postStr, "expected Datetime field type:\n$postStr")
        assertTrue("StrongRef" in postStr, "expected StrongRef ref usage:\n$postStr")
        assertTrue("@Serializable" in postStr)
    }

    @Test
    fun `query emits Request and Response with correct fields`() {
        val files = generate(getTimelineJson)
        val text = renderAll(files)

        val req = files.firstOrNull { it.name == "GetTimelineRequest" }
        assertNotNull(req, "missing request\n$text")
        val resp = files.firstOrNull { it.name == "GetTimelineResponse" }
        assertNotNull(resp, "missing response\n$text")

        val reqStr = req.toString()
        assertTrue("algorithm: String" in reqStr, reqStr)
        // Query params land in the URL query string and thus use read-shape
        // (T? = null), not AtField — URL params can't express "null vs absent".
        assertTrue("limit: Long? = null" in reqStr, "expected nullable Long for query param:\n$reqStr")
        assertTrue("AtField" !in reqStr, "query request must not wrap params in AtField:\n$reqStr")

        val respStr = resp.toString()
        assertTrue("cursor: String" in respStr, respStr)
        // feedLength optional + read shape -> nullable Long
        assertTrue("feedLength: Long? = null" in respStr, respStr)
    }

    @Test
    fun `procedure request uses mutation context for optional fields`() {
        val files = generate(createPostProcedureJson)
        val req = files.firstOrNull { it.name == "CreatePostRequest" }
        assertNotNull(req)
        val reqStr = req.toString()
        assertTrue("text: String" in reqStr, reqStr)
        // optional langs -> AtField<List<Language>>
        assertTrue("AtField" in reqStr, reqStr)
        assertTrue("Language" in reqStr, reqStr)
        // Mutation-shape classes carry the experimental opt-in.
        assertTrue("ExperimentalSerializationApi" in reqStr, reqStr)
        assertTrue("EncodeDefault" in reqStr, reqStr)
    }

    @Test
    fun `contextual split emits Primary plus Input`() {
        val files = generate(sharedSplitObjectJson, readSideForSplitJson)
        val text = renderAll(files)

        // The `settings` secondary def is reached from both a mutation root
        // (profile record) and a read root (getProfile output), and has an
        // optional field → must split.
        val settings = files.firstOrNull { it.name == "ProfileSettings" }
        assertNotNull(settings, "missing ProfileSettings primary\n$text")
        val settingsInput = files.firstOrNull { it.name == "ProfileSettingsInput" }
        assertNotNull(settingsInput, "missing ProfileSettingsInput\n$text")

        val primaryStr = settings.toString()
        val inputStr = settingsInput.toString()
        // Primary is read-shape: nullable String accent
        assertTrue("accent: String? = null" in primaryStr, primaryStr)
        // Input is mutation-shape: AtField<String> accent
        assertTrue("AtField" in inputStr, inputStr)
    }

    @Test
    fun `union field emits sealed interface and serializers and target extends it`() {
        val files = generate(unionJson)
        val text = renderAll(files)

        // There should be a synthesized union. The owner Primary is "Post"
        // (terminalPascal of app.bsky.embed.post), and the field is "embed" →
        // union class "PostEmbedUnion".
        val union = files.firstOrNull { it.name == "PostEmbedUnion" }
        assertNotNull(union, "missing union file\n$text")
        val unionStr = union.toString()
        assertTrue("interface PostEmbedUnion" in unionStr, unionStr)
        assertTrue("public object PostEmbedUnionSerializer" in unionStr || "object PostEmbedUnionSerializer" in unionStr, unionStr)
        assertTrue("PostEmbedUnionUnknownSerializer" in unionStr, unionStr)
        assertTrue("class Unknown" in unionStr, unionStr)
        assertTrue("OpenUnionMember" in unionStr, unionStr)
        assertTrue("UnknownOpenUnionMember" in unionStr, unionStr)
        // The when maps the $type discriminator to each member.
        assertTrue("app.bsky.embed.post#images" in unionStr, unionStr)
        assertTrue("app.bsky.embed.post#video" in unionStr, unionStr)

        // The target classes (PostImages, PostVideo) should extend the union.
        val images = files.firstOrNull { it.name == "PostImages" }
        assertNotNull(images)
        val imagesStr = images.toString()
        assertTrue("PostEmbedUnion" in imagesStr, "PostImages must extend PostEmbedUnion:\n$imagesStr")
    }

    @Test
    fun `subscriptions are skipped with a warning`() {
        val files = generate(subscriptionJson)
        // No types should have been emitted for the subscription.
        for (f in files) {
            assertTrue(
                "SubscribeRepos" !in f.toString() || f.name.contains("SubscribeRepos").not(),
                "unexpected subscription output: ${f.name}",
            )
        }
        // Accept that we may have emitted nothing at all, which is the point.
        val anyWithSubscribe = files.any { it.name.contains("SubscribeRepos") }
        assertEquals(false, anyWithSubscribe, "subscription file should not exist")
    }

    @Test
    fun `generation is deterministic`() {
        val a = generate(
            strongRefJson,
            simplePostJson,
            getTimelineJson,
            createPostProcedureJson,
            unionJson,
        )
        val b = generate(
            strongRefJson,
            simplePostJson,
            getTimelineJson,
            createPostProcedureJson,
            unionJson,
        )
        assertEquals(a.size, b.size)
        val ra = renderAll(a)
        val rb = renderAll(b)
        assertEquals(ra, rb)
    }

    @Test
    fun `verification pass fires on naming collision between two DefKeys`() {
        // Two unrelated defs in different NSIDs whose package-and-name
        // derivations produce the same FqName. The naming matrix drops the
        // last NSID segment for the package, so `example.dup.StrongRef`
        // (nsid = example.dup.strongRef#main → package example.dup, class
        // StrongRef) would collide with a `example.dup.alias` def whose
        // `#strongRef` fragment naming also lands at `example.dup.StrongRef`.
        // We force the collision by giving two sibling defs in a .defs file
        // the same fragment name would be caught by duplicate-key handling,
        // so instead we use two files whose Primary names collide.
        val aJson = """
            {"lexicon":1,"id":"example.dup.foo","defs":{"main":{"type":"object","properties":{}}}}
        """.trimIndent()
        // The naming matrix emits `example.dup.foo#main` → package `example.dup`, class `Foo`.
        // Create a second def whose .defs fragment PascalCases to `Foo` too:
        val bJson = """
            {"lexicon":1,"id":"example.dup.defs","defs":{"foo":{"type":"object","properties":{}}}}
        """.trimIndent()
        // `example.dup.defs#foo` in a .defs file emits as bare fragment `Foo`
        // in package `example.dup` — same FqName as `example.dup.foo#main`.
        val ex = assertFailsWith<VerificationFailure> { generate(aJson, bJson) }
        assertTrue(ex.message!!.contains("INV-2"), "expected INV-2 diagnostic, got: ${ex.message}")
        assertTrue(ex.message!!.contains("Foo"), "expected collision name 'Foo' in: ${ex.message}")
    }

    @Test
    fun `record def emits @SerialName with bare lexicon NSID`() {
        // Regression for #76: open-union members were serializing $type as the
        // Kotlin FQCN because @SerialName was missing. Records (lexicon `record`
        // type) must be annotated with the bare NSID (no #main suffix).
        val files = generate(strongRefJson, simplePostJson)
        val post = files.first { it.name == "Post" }
        val postStr = post.toString()
        assertTrue(
            "@SerialName(\"app.bsky.feed.post\")" in postStr,
            "expected @SerialName(\"app.bsky.feed.post\") on record class:\n$postStr",
        )
    }

    @Test
    fun `object def main emits @SerialName with bare lexicon NSID`() {
        // The #main object def — used as a union member in the embed shape —
        // must serialize its $type as the bare NSID, never the Kotlin FQCN.
        val files = generate(unionJson)
        val post = files.first { it.name == "Post" }
        val postStr = post.toString()
        assertTrue(
            "@SerialName(\"app.bsky.embed.post\")" in postStr,
            "expected @SerialName(\"app.bsky.embed.post\") on #main object class:\n$postStr",
        )
    }

    @Test
    fun `object sub-def emits @SerialName with nsid#name form`() {
        // Sub-definitions (e.g. `app.bsky.embed.post#images`) participate in
        // open unions, so their $type must round-trip back to the same form.
        val files = generate(unionJson)
        val images = files.first { it.name == "PostImages" }
        val imagesStr = images.toString()
        assertTrue(
            "@SerialName(\"app.bsky.embed.post#images\")" in imagesStr,
            "expected @SerialName(\"app.bsky.embed.post#images\") on sub-def class:\n$imagesStr",
        )
    }

    @Test
    fun `xrpc Request and Response classes do not emit @SerialName`() {
        // Synthesized XRPC types have no canonical lexicon definition — they
        // are not union members and are never serialized with a $type
        // discriminator, so annotating them would be misleading.
        val files = generate(getTimelineJson)
        val req = files.first { it.name == "GetTimelineRequest" }
        val resp = files.first { it.name == "GetTimelineResponse" }
        assertTrue("@SerialName" !in req.toString(), "request must not carry @SerialName:\n$req")
        assertTrue("@SerialName" !in resp.toString(), "response must not carry @SerialName:\n$resp")
    }

    @Test
    fun `datetime format uses runtime Datetime value class`() {
        val files = generate(strongRefJson, simplePostJson)
        val post = files.first { it.name == "Post" }
        val postStr = post.toString()
        assertTrue(
            "io.github.kikin81.atproto.runtime.Datetime" in postStr || "Datetime" in postStr,
            "expected Datetime reference in generated Post class:\n$postStr",
        )
    }
}
