package io.github.kikin81.atproto.chat.bsky.group

import io.github.kikin81.atproto.runtime.NoXrpcParams
import io.github.kikin81.atproto.runtime.XrpcClient
import kotlin.String

public class GroupService(
  private val client: XrpcClient,
  private val proxy: String? = "did:web:api.bsky.chat#bsky_chat",
) {
  /**
   * Create a group convo with up to 49 invited members.
   */
  public suspend fun createGroup(request: CreateGroupRequest, proxy: String? = null): CreateGroupResponse = client.procedure(
      nsid = "chat.bsky.group.createGroup",
      params = NoXrpcParams,
      paramsSerializer = NoXrpcParams.serializer(),
      input = request,
      inputSerializer = CreateGroupRequest.serializer(),
      responseSerializer = CreateGroupResponse.serializer(),
      proxy = proxy ?: this.proxy,
  )
}
