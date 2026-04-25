package io.github.kikin81.atproto.compose.internal

/**
 * One-pass UTF-8 byte ↔ UTF-16 char boundary table for a [String].
 *
 * AT Protocol facets address ranges by UTF-8 byte offsets, but the JVM
 * (and `androidx.compose.ui.text.AnnotatedString`) addresses by UTF-16
 * char offsets. The two diverge whenever the text contains a codepoint
 * above U+007F: a 3-byte CJK character contributes 1 UTF-16 char, a
 * 4-byte BMP-supplement emoji contributes 2 UTF-16 chars (a surrogate
 * pair), and so on.
 *
 * This table walks the text once at construction, recording every
 * codepoint boundary, then answers [byteToChar] queries via binary
 * search with zero allocation per call.
 *
 * The lookup convention matches the facet contract: the requested byte
 * offset must land *exactly* on a codepoint boundary (the start of a
 * codepoint or the position immediately after the last byte). A query
 * for a byte offset that falls *inside* a codepoint's UTF-8 byte
 * sequence — or one that is negative or past the end — returns `null`,
 * which callers translate to "skip this malformed facet."
 */
internal class Utf8CharBoundaryTable(private val text: String) {
    /** Total UTF-8 byte length of [text]. */
    val utf8ByteLength: Int

    /**
     * `true` when every char in [text] is below U+0080 — pure ASCII.
     * Byte offsets equal char offsets in this case, so we skip building
     * the boundary tables entirely and answer [byteToChar] with the
     * identity mapping. Bluesky posts have a 300-char limit, so even a
     * single emoji opt-out is fine, but ASCII-only posts are common
     * and benefit from the zero-allocation path on cold-scroll.
     */
    private val asciiOnly: Boolean

    /**
     * Sorted byte offsets at which a codepoint starts. The very last
     * entry is [utf8ByteLength], representing the end-of-text boundary
     * (so a facet whose `byteEnd == utf8ByteLength` is mappable).
     * Empty in the [asciiOnly] fast path.
     */
    private val byteBoundaries: IntArray

    /**
     * For each entry in [byteBoundaries], the corresponding UTF-16 char
     * index in [text]. Always the same length as [byteBoundaries].
     * Empty in the [asciiOnly] fast path.
     */
    private val charBoundaries: IntArray

    init {
        // Cheap first pass: scan char codes only, no allocation. Lets
        // the common case (English-only posts) skip the boundary-table
        // build entirely on the fast path below.
        val allAscii = isAsciiOnly(text)

        if (allAscii) {
            asciiOnly = true
            utf8ByteLength = text.length
            byteBoundaries = EMPTY_INT_ARRAY
            charBoundaries = EMPTY_INT_ARRAY
        } else {
            asciiOnly = false
            // Worst case: every char is its own codepoint. Allocate
            // text.length + 1 (for the end-of-text sentinel) up front,
            // then copy down to exact size after the walk so binary
            // search has a tight bound.
            val maxBoundaries = text.length + 1
            val bytesAt = IntArray(maxBoundaries)
            val charsAt = IntArray(maxBoundaries)
            var byteIdx = 0
            var charIdx = 0
            var boundaryCount = 0

            bytesAt[boundaryCount] = byteIdx
            charsAt[boundaryCount] = charIdx
            boundaryCount++

            while (charIdx < text.length) {
                val codePoint = text.codePointAt(charIdx)
                byteIdx += utf8ByteCount(codePoint)
                charIdx += Character.charCount(codePoint)
                bytesAt[boundaryCount] = byteIdx
                charsAt[boundaryCount] = charIdx
                boundaryCount++
            }

            utf8ByteLength = byteIdx
            byteBoundaries = bytesAt.copyOf(boundaryCount)
            charBoundaries = charsAt.copyOf(boundaryCount)
        }
    }

    /**
     * Translate a UTF-8 byte offset into a UTF-16 char offset.
     *
     * Returns `null` when [byte] is negative, falls inside a codepoint's
     * UTF-8 byte sequence, or is past the end of the text. Callers
     * silently skip facets that produce a `null` translation.
     */
    fun byteToChar(byte: Int): Int? {
        if (byte < 0 || byte > utf8ByteLength) return null
        if (asciiOnly) return byte
        val idx = byteBoundaries.binarySearch(byte)
        if (idx < 0) return null
        return charBoundaries[idx]
    }

    private companion object {
        val EMPTY_INT_ARRAY = IntArray(0)
    }

    private fun utf8ByteCount(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    private fun isAsciiOnly(s: String): Boolean {
        for (i in 0 until s.length) {
            if (s[i].code >= 0x80) return false
        }
        return true
    }
}
