package io.github.kikin81.atproto.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

public fun <R, T> paginate(
    fetch: suspend (cursor: String?) -> R,
    getCursor: (R) -> String?,
    getItems: (R) -> List<T>,
): Flow<T> = flow {
    var currentCursor: String? = null
    do {
        val response = fetch(currentCursor)
        val nextCursor = getCursor(response)
        if (nextCursor == currentCursor) break
        val items = getItems(response)
        for (item in items) emit(item)
        currentCursor = nextCursor
    } while (currentCursor != null)
}

public fun <R, T> paginatePages(
    fetch: suspend (cursor: String?) -> R,
    getCursor: (R) -> String?,
    getItems: (R) -> List<T>,
): Flow<List<T>> = flow {
    var currentCursor: String? = null
    do {
        val response = fetch(currentCursor)
        val nextCursor = getCursor(response)
        if (nextCursor == currentCursor) break
        emit(getItems(response))
        currentCursor = nextCursor
    } while (currentCursor != null)
}
