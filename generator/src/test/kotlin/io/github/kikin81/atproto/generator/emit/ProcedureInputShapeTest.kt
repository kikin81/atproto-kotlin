package io.github.kikin81.atproto.generator.emit

import io.github.kikin81.atproto.generator.ir.HttpBody
import io.github.kikin81.atproto.generator.ir.ObjectType
import io.github.kikin81.atproto.generator.ir.ParamsType
import io.github.kikin81.atproto.generator.ir.ProcedureDef
import io.github.kikin81.atproto.generator.ir.StringType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProcedureInputShapeTest {

    @Test
    fun `wildcard encoding with no schema classifies as RawBytes with no default`() {
        val def = ProcedureDef(input = HttpBody(encoding = "*/*"))
        val shape = EmissionPlan.classifyProcedureInput(def, "test.example")
        assertTrue(shape is ProcedureInputShape.RawBytes)
        assertEquals("*/*", shape.encoding)
        assertNull(shape.defaultContentType)
    }

    @Test
    fun `pinned image encoding classifies as RawBytes with a constant default`() {
        val def = ProcedureDef(input = HttpBody(encoding = "image/png"))
        val shape = EmissionPlan.classifyProcedureInput(def, "test.example")
        assertTrue(shape is ProcedureInputShape.RawBytes)
        assertEquals("image/png", shape.encoding)
        assertEquals(KtorContentTypeRef.Constant("Image", "PNG"), shape.defaultContentType)
    }

    @Test
    fun `unknown concrete encoding classifies as RawBytes with a parsed default`() {
        val def = ProcedureDef(input = HttpBody(encoding = "application/x-custom"))
        val shape = EmissionPlan.classifyProcedureInput(def, "test.example")
        assertTrue(shape is ProcedureInputShape.RawBytes)
        assertEquals(KtorContentTypeRef.Parsed("application/x-custom"), shape.defaultContentType)
    }

    @Test
    fun `JSON encoding with no schema still classifies as Json (defensive)`() {
        val def = ProcedureDef(input = HttpBody(encoding = "application/json"))
        assertEquals(ProcedureInputShape.Json, EmissionPlan.classifyProcedureInput(def, "test.example"))
    }

    @Test
    fun `input with object schema classifies as Json regardless of encoding`() {
        val schema = ObjectType(required = listOf("id"), properties = mapOf("id" to StringType()))
        val def = ProcedureDef(input = HttpBody(encoding = "application/json", schema = schema))
        assertEquals(ProcedureInputShape.Json, EmissionPlan.classifyProcedureInput(def, "test.example"))
    }

    @Test
    fun `no input but params present classifies as ParamsOnly`() {
        val params = ParamsType(properties = mapOf("q" to StringType()))
        val def = ProcedureDef(parameters = params)
        assertEquals(ProcedureInputShape.ParamsOnly, EmissionPlan.classifyProcedureInput(def, "test.example"))
    }

    @Test
    fun `no input and no params classifies as None`() {
        val def = ProcedureDef()
        assertEquals(ProcedureInputShape.None, EmissionPlan.classifyProcedureInput(def, "test.example"))
    }

    @Test
    fun `raw-bytes input combined with params classifies as UnsupportedRawBytesWithParams carrying lexiconId`() {
        val def = ProcedureDef(
            input = HttpBody(encoding = "*/*"),
            parameters = ParamsType(properties = mapOf("did" to StringType())),
        )
        val shape = EmissionPlan.classifyProcedureInput(def, "com.example.uploadWithParams")
        assertTrue(shape is ProcedureInputShape.UnsupportedRawBytesWithParams)
        assertEquals("*/*", shape.encoding)
        assertEquals("com.example.uploadWithParams", shape.lexiconId)
    }
}
