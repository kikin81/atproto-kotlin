package io.github.kikin81.atproto.example

import io.github.kikin81.atproto.runtime.NoXrpcParams
import io.github.kikin81.atproto.runtime.XrpcClient

public class ExampleService(
  private val client: XrpcClient,
) {
  /**
   * Round-trips a Shared value through a procedure.
   */
  public suspend fun sendShared(request: SendSharedRequest): SendSharedResponse = client.procedure(
      nsid = "example.sendShared",
      params = NoXrpcParams,
      paramsSerializer = NoXrpcParams.serializer(),
      input = request,
      inputSerializer = SendSharedRequest.serializer(),
      responseSerializer = SendSharedResponse.serializer(),
  )
}
