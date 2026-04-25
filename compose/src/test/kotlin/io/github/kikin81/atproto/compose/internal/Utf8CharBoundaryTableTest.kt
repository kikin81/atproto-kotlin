package io.github.kikin81.atproto.compose.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Utf8CharBoundaryTableTest {
    @Test
    fun `pure ASCII byte offset equals char offset`() {
        val table = Utf8CharBoundaryTable("hello world")
        assertEquals(11, table.utf8ByteLength)
        for (n in 0..11) {
            assertEquals(n, table.byteToChar(n), "ascii byte $n")
        }
    }

    @Test
    fun `two-byte UTF-8 character maps end byte to next char`() {
        // "é" is U+00E9, encoded as 0xC3 0xA9 in UTF-8 → 2 bytes, 1 char.
        // "aé" is 3 bytes, 2 chars.
        val table = Utf8CharBoundaryTable("aé")
        assertEquals(3, table.utf8ByteLength)
        assertEquals(0, table.byteToChar(0))
        assertEquals(1, table.byteToChar(1))
        // Byte 2 is the second byte of é — falls inside the codepoint.
        assertNull(table.byteToChar(2))
        assertEquals(2, table.byteToChar(3))
    }

    @Test
    fun `three-byte UTF-8 CJK character maps end byte to next char`() {
        // "中" is U+4E2D, encoded as E4 B8 AD in UTF-8 → 3 bytes, 1 char.
        // "a中" is 4 bytes, 2 chars.
        val table = Utf8CharBoundaryTable("a中")
        assertEquals(4, table.utf8ByteLength)
        assertEquals(0, table.byteToChar(0))
        assertEquals(1, table.byteToChar(1))
        assertNull(table.byteToChar(2))
        assertNull(table.byteToChar(3))
        assertEquals(2, table.byteToChar(4))
    }

    @Test
    fun `four-byte UTF-8 emoji maps to surrogate pair char count`() {
        // "👋" is U+1F44B, encoded as F0 9F 91 8B in UTF-8 → 4 bytes,
        // and 2 UTF-16 chars (surrogate pair: D83D DC4B).
        val table = Utf8CharBoundaryTable("👋")
        assertEquals(4, table.utf8ByteLength)
        assertEquals(0, table.byteToChar(0))
        assertNull(table.byteToChar(1))
        assertNull(table.byteToChar(2))
        assertNull(table.byteToChar(3))
        assertEquals(2, table.byteToChar(4))
    }

    @Test
    fun `emoji followed by ASCII keeps boundaries aligned for following chars`() {
        // "👋 a" — 4 bytes + 1 byte + 1 byte = 6 bytes, 2 chars + 1 char
        // + 1 char = 4 chars.
        val table = Utf8CharBoundaryTable("👋 a")
        assertEquals(6, table.utf8ByteLength)
        assertEquals(0, table.byteToChar(0))
        assertEquals(2, table.byteToChar(4)) // after the emoji
        assertEquals(3, table.byteToChar(5)) // after the space
        assertEquals(4, table.byteToChar(6)) // end of text
    }

    @Test
    fun `combining mark contributes its own byte and char count`() {
        // "é" can be encoded as 'e' + combining acute (U+0301).
        // 'e' = 1 byte, 1 char. U+0301 = 2 bytes, 1 char.
        // Total: 3 bytes, 2 chars (since combining marks are their
        // own UTF-16 chars — facets address codepoints, not graphemes).
        val composed = "é"
        val table = Utf8CharBoundaryTable(composed)
        assertEquals(3, table.utf8ByteLength)
        assertEquals(0, table.byteToChar(0))
        assertEquals(1, table.byteToChar(1)) // after 'e'
        assertNull(table.byteToChar(2))
        assertEquals(2, table.byteToChar(3)) // after combining mark
    }

    @Test
    fun `out of range byte offset returns null`() {
        val table = Utf8CharBoundaryTable("hi")
        assertNull(table.byteToChar(-1))
        assertNull(table.byteToChar(3))
        assertNull(table.byteToChar(Int.MAX_VALUE))
    }

    @Test
    fun `empty text has only the zero boundary`() {
        val table = Utf8CharBoundaryTable("")
        assertEquals(0, table.utf8ByteLength)
        assertEquals(0, table.byteToChar(0))
        assertNull(table.byteToChar(1))
    }
}
