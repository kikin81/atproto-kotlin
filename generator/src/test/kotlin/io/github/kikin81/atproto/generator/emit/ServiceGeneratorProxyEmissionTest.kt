package io.github.kikin81.atproto.generator.emit

import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.TypeSpec
import io.github.kikin81.atproto.generator.naming.NamingMatrix
import io.github.kikin81.atproto.generator.parser.LexiconParser
import io.github.kikin81.atproto.generator.resolved.ContextTagger
import io.github.kikin81.atproto.generator.resolved.RefResolver
import io.github.kikin81.atproto.generator.resolved.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies that `ServiceGenerator` emits a per-call `proxy: String? = null`
 * parameter on every generated query/procedure method (regardless of input
 * shape), and forwards it correctly:
 *
 * - Services with a constructor `proxy` property (services whose NSIDs hit
 *   a `ProxyMapping` entry — `chat.bsky.*` today): forwarding expression
 *   `proxy = proxy ?: this.proxy`. Method-level override wins when non-null;
 *   constructor default wins when null.
 * - Services without a constructor `proxy` property (everyone else):
 *   forwarding expression `proxy = proxy`. Null when caller omits, no
 *   `atproto-proxy` header.
 *
 * Uses a synthetic inline lexicon corpus covering every input shape:
 * query, JSON procedure, params-only procedure, no-input procedure, and
 * raw-bytes procedure — for both a proxied and an unproxied namespace.
 *
 * Byte-for-byte regression is covered by [io.github.kikin81.atproto.generator.golden.GoldenFileTest].
 */
class ServiceGeneratorProxyEmissionTest {

    private val parser = LexiconParser()

    private val proxiedLexicons = listOf(
        // chat query
        """
        {
          "lexicon": 1,
          "id": "chat.bsky.fake.listFake",
          "defs": {
            "main": {
              "type": "query",
              "parameters": {
                "type": "params",
                "properties": { "limit": { "type": "integer" } }
              },
              "output": {
                "encoding": "application/json",
                "schema": {
                  "type": "object",
                  "required": ["items"],
                  "properties": { "items": { "type": "array", "items": { "type": "string" } } }
                }
              }
            }
          }
        }
        """,
        // chat JSON procedure
        """
        {
          "lexicon": 1,
          "id": "chat.bsky.fake.doFake",
          "defs": {
            "main": {
              "type": "procedure",
              "input": {
                "encoding": "application/json",
                "schema": {
                  "type": "object",
                  "required": ["payload"],
                  "properties": { "payload": { "type": "string" } }
                }
              },
              "output": {
                "encoding": "application/json",
                "schema": {
                  "type": "object",
                  "required": ["ok"],
                  "properties": { "ok": { "type": "boolean" } }
                }
              }
            }
          }
        }
        """,
    )

    private val unproxiedLexicons = listOf(
        // non-chat query
        """
        {
          "lexicon": 1,
          "id": "app.bsky.fake.getFake",
          "defs": {
            "main": {
              "type": "query",
              "parameters": {
                "type": "params",
                "properties": { "id": { "type": "string" } }
              },
              "output": {
                "encoding": "application/json",
                "schema": {
                  "type": "object",
                  "required": ["value"],
                  "properties": { "value": { "type": "string" } }
                }
              }
            }
          }
        }
        """,
        // non-chat JSON procedure
        """
        {
          "lexicon": 1,
          "id": "app.bsky.fake.putFake",
          "defs": {
            "main": {
              "type": "procedure",
              "input": {
                "encoding": "application/json",
                "schema": {
                  "type": "object",
                  "required": ["value"],
                  "properties": { "value": { "type": "string" } }
                }
              }
            }
          }
        }
        """,
        // non-chat params-only procedure (no input body, only URL params)
        """
        {
          "lexicon": 1,
          "id": "app.bsky.fake.searchFake",
          "defs": {
            "main": {
              "type": "procedure",
              "parameters": {
                "type": "params",
                "properties": { "q": { "type": "string" } }
              },
              "output": {
                "encoding": "application/json",
                "schema": {
                  "type": "object",
                  "required": ["hits"],
                  "properties": { "hits": { "type": "integer" } }
                }
              }
            }
          }
        }
        """,
        // non-chat no-input procedure (has output schema so a Response class is
        // emitted and the method is not silently filtered out of ServiceGenerator.emitAll)
        """
        {
          "lexicon": 1,
          "id": "app.bsky.fake.pingFake",
          "defs": {
            "main": {
              "type": "procedure",
              "output": {
                "encoding": "application/json",
                "schema": {
                  "type": "object",
                  "required": ["pong"],
                  "properties": { "pong": { "type": "boolean" } }
                }
              }
            }
          }
        }
        """,
        // non-chat raw-bytes procedure
        """
        {
          "lexicon": 1,
          "id": "app.bsky.fake.uploadFake",
          "defs": {
            "main": {
              "type": "procedure",
              "input": { "encoding": "image/png" },
              "output": {
                "encoding": "application/json",
                "schema": {
                  "type": "object",
                  "required": ["blob"],
                  "properties": { "blob": { "type": "blob" } }
                }
              }
            }
          }
        }
        """,
    )

    private fun emissionsFor(jsonLexicons: List<String>): List<ServiceGenerator.ServiceEmission> {
        val docs = jsonLexicons.map { parser.parse(it.trimIndent()) }
        val symbols = SymbolTable.build(docs)
        RefResolver(symbols).validate()
        val contexts = ContextTagger(symbols).tag()
        val plan = EmissionPlan.build(symbols, NamingMatrix(), contexts)
        return ServiceGenerator(plan, symbols).emitAll()
    }

    private fun TypeSpec.functions(): List<FunSpec> = funSpecs

    @Test
    fun proxied_service_appends_proxy_param_and_forwards_with_fallback() {
        val emissions = emissionsFor(proxiedLexicons)
        val service = emissions.singleOrNull { it.fqName.simpleName == "FakeService" }
        assertNotNull(service, "expected exactly one FakeService emission in: ${emissions.map { it.fqName }}")

        val funs = service.typeSpec.functions()
        assertTrue(funs.size >= 2, "expected at least 2 methods on proxied FakeService, got: ${funs.map { it.name }}")

        for (fn in funs) {
            val last = fn.parameters.lastOrNull()
            assertNotNull(last, "method ${fn.name} has no parameters")
            assertEquals("proxy", last.name, "method ${fn.name} last param is not 'proxy'")
            assertEquals("kotlin.String?", last.type.toString(), "method ${fn.name} 'proxy' param has wrong type: ${last.type}")
            assertEquals("null", last.defaultValue?.toString(), "method ${fn.name} 'proxy' default is not 'null': ${last.defaultValue}")

            val body = fn.body.toString()
            assertTrue(
                body.contains("proxy = proxy ?: this.proxy"),
                "method ${fn.name} body missing 'proxy = proxy ?: this.proxy':\n$body",
            )
        }
    }

    @Test
    fun unproxied_service_appends_proxy_param_and_forwards_plain() {
        val emissions = emissionsFor(unproxiedLexicons)
        val service = emissions.singleOrNull { it.fqName.simpleName == "FakeService" }
        assertNotNull(service, "expected exactly one FakeService emission in: ${emissions.map { it.fqName }}")

        val funs = service.typeSpec.functions()
        assertTrue(funs.size >= 5, "expected at least 5 methods on unproxied FakeService, got: ${funs.map { it.name }}")

        for (fn in funs) {
            val last = fn.parameters.lastOrNull()
            assertNotNull(last, "method ${fn.name} has no parameters")
            assertEquals("proxy", last.name, "method ${fn.name} last param is not 'proxy'")
            assertEquals("kotlin.String?", last.type.toString(), "method ${fn.name} 'proxy' param has wrong type: ${last.type}")
            assertEquals("null", last.defaultValue?.toString(), "method ${fn.name} 'proxy' default is not 'null': ${last.defaultValue}")

            val body = fn.body.toString()
            assertTrue(
                body.contains("proxy = proxy"),
                "method ${fn.name} body missing 'proxy = proxy':\n$body",
            )
            assertTrue(
                !body.contains("this.proxy"),
                "method ${fn.name} body should NOT contain 'this.proxy' on an unproxied service:\n$body",
            )
        }
    }

    @Test
    fun raw_bytes_procedure_also_appends_proxy_param() {
        val emissions = emissionsFor(unproxiedLexicons)
        val service = emissions.single { it.fqName.simpleName == "FakeService" }
        val uploadFn = service.typeSpec.functions().single { it.name == "uploadFake" }

        // raw-bytes shape: parameters are (input, inputContentType, proxy)
        assertEquals(3, uploadFn.parameters.size, "raw-bytes method should have 3 params, got: ${uploadFn.parameters.map { it.name }}")
        assertEquals("input", uploadFn.parameters[0].name)
        assertEquals("inputContentType", uploadFn.parameters[1].name)
        assertEquals("proxy", uploadFn.parameters[2].name)
        assertEquals("null", uploadFn.parameters[2].defaultValue?.toString())

        val body = uploadFn.body.toString()
        assertTrue(body.contains("proxy = proxy"), "raw-bytes body missing 'proxy = proxy':\n$body")
        assertTrue(!body.contains("this.proxy"), "raw-bytes body on unproxied service should not contain 'this.proxy':\n$body")
    }
}
