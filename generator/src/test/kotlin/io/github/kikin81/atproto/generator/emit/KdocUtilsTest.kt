package io.github.kikin81.atproto.generator.emit

import kotlin.test.Test
import kotlin.test.assertEquals

class KdocUtilsTest {

    @Test
    fun escapesPercent() {
        assertEquals("100%% of quota", "100% of quota".sanitizeForKdoc())
    }

    @Test
    fun escapesDollar() {
        assertEquals("Uses \${'$'}type for dispatch", "Uses \$type for dispatch".sanitizeForKdoc())
    }

    @Test
    fun passesPlainTextThrough() {
        assertEquals("A simple description.", "A simple description.".sanitizeForKdoc())
    }

    @Test
    fun escapesBothPercentAndDollar() {
        assertEquals("50%% off for \${'$'}user", "50% off for \$user".sanitizeForKdoc())
    }
}
