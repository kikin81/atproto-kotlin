package io.github.kikin81.atproto.chat.bsky.convo

import io.github.kikin81.atproto.runtime.XrpcClient
import io.github.kikin81.atproto.runtime.paginate
import io.github.kikin81.atproto.runtime.paginatePages
import kotlin.String
import kotlin.collections.List
import kotlinx.coroutines.flow.Flow

public class ConvoService(
  private val client: XrpcClient,
  private val proxy: String? = "did:web:api.bsky.chat#bsky_chat",
) {
  /**
   * List conversations.
   */
  public suspend fun listConvos(request: ListConvosRequest = ListConvosRequest()): ListConvosResponse = client.query(
      nsid = "chat.bsky.convo.listConvos",
      params = request,
      paramsSerializer = ListConvosRequest.serializer(),
      responseSerializer = ListConvosResponse.serializer(),
      proxy = proxy,
  )
}

/**
 * List conversations.
 */
public fun ConvoService.listConvosFlow(request: ListConvosRequest = ListConvosRequest()): Flow<String> = paginate(
    fetch = { cursor -> listConvos(request.copy(cursor = cursor)) },
    getCursor = { it.cursor },
    getItems = { it.convos },
)

/**
 * List conversations.
 */
public fun ConvoService.listConvosPageFlow(request: ListConvosRequest = ListConvosRequest()): Flow<List<String>> = paginatePages(
    fetch = { cursor -> listConvos(request.copy(cursor = cursor)) },
    getCursor = { it.cursor },
    getItems = { it.convos },
)
