package io.github.kikin81.atproto.runtime

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaginationTest {

    data class Page(val cursor: String?, val items: List<String>)

    @Test
    fun paginateEmitsAllItemsAcrossPages() = runTest {
        val pages = listOf(
            Page("c1", listOf("a", "b")),
            Page("c2", listOf("c", "d")),
            Page(null, listOf("e")),
        )
        val cursorsReceived = mutableListOf<String?>()
        val items = paginate(
            fetch = { cursor ->
                cursorsReceived.add(cursor)
                pages[cursorsReceived.size - 1]
            },
            getCursor = { it.cursor },
            getItems = { it.items },
        ).toList()

        assertEquals(listOf("a", "b", "c", "d", "e"), items)
        assertEquals(listOf(null, "c1", "c2"), cursorsReceived)
    }

    @Test
    fun paginateEmptyFirstPageTerminates() = runTest {
        val items = paginate(
            fetch = { Page(null, emptyList()) },
            getCursor = { it.cursor },
            getItems = { it.items },
        ).toList()

        assertEquals(emptyList(), items)
    }

    @Test
    fun paginateTakeLimitsPages() = runTest {
        var fetchCount = 0
        val items = paginate(
            fetch = { cursor: String? ->
                fetchCount++
                Page("next", (1..10).map { "item$it" })
            },
            getCursor = { it.cursor },
            getItems = { it.items },
        ).take(5).toList()

        assertEquals(5, items.size)
        assertEquals(1, fetchCount)
    }

    @Test
    fun paginatePropagatsFetcherException() = runTest {
        var callCount = 0
        assertFailsWith<RuntimeException> {
            paginate(
                fetch = { cursor: String? ->
                    callCount++
                    if (callCount == 2) throw RuntimeException("network error")
                    Page("c1", listOf("a"))
                },
                getCursor = { it.cursor },
                getItems = { it.items },
            ).toList()
        }
    }

    @Test
    fun paginateRepeatedCursorTerminates() = runTest {
        var fetchCount = 0
        val items = paginate(
            fetch = { cursor: String? ->
                fetchCount++
                Page("stuck", listOf("item$fetchCount"))
            },
            getCursor = { it.cursor },
            getItems = { it.items },
        ).toList()

        // First call: cursor=null, returns "stuck" → emits item1, advances
        // Second call: cursor="stuck", returns "stuck" → guard breaks
        assertEquals(listOf("item1"), items)
        assertEquals(2, fetchCount)
    }

    @Test
    fun paginatePagesEmitsListPerPage() = runTest {
        val pages = listOf(
            Page("c1", listOf("a", "b")),
            Page("c2", listOf("c", "d", "e")),
            Page(null, listOf("f")),
        )
        var idx = 0
        val result = paginatePages(
            fetch = { pages[idx++] },
            getCursor = { it.cursor },
            getItems = { it.items },
        ).toList()

        assertEquals(3, result.size)
        assertEquals(listOf("a", "b"), result[0])
        assertEquals(listOf("c", "d", "e"), result[1])
        assertEquals(listOf("f"), result[2])
    }
}
