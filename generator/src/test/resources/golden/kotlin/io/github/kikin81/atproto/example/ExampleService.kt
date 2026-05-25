package io.github.kikin81.atproto.example

import io.github.kikin81.atproto.runtime.NoXrpcParams
import io.github.kikin81.atproto.runtime.XrpcClient
import io.ktor.http.ContentType
import kotlin.ByteArray
import kotlin.String

public class ExampleService(
  private val client: XrpcClient,
) {
  /**
   * Round-trips a Shared value through a procedure.
   */
  public suspend fun sendShared(request: SendSharedRequest, proxy: String? = null): SendSharedResponse = client.procedure(
      nsid = "example.sendShared",
      params = NoXrpcParams,
      paramsSerializer = NoXrpcParams.serializer(),
      input = request,
      inputSerializer = SendSharedRequest.serializer(),
      responseSerializer = SendSharedResponse.serializer(),
      proxy = proxy,
  )

  /**
   * Upload a PNG avatar.
   */
  public suspend fun uploadAvatar(
    input: ByteArray,
    inputContentType: ContentType = ContentType.Image.PNG,
    proxy: String? = null,
  ): UploadAvatarResponse = client.procedure(
      nsid = "example.uploadAvatar",
      params = NoXrpcParams,
      paramsSerializer = NoXrpcParams.serializer(),
      input = input,
      inputContentType = inputContentType,
      responseSerializer = UploadAvatarResponse.serializer(),
      proxy = proxy,
  )

  /**
   * Upload a blob and receive its CID reference.
   */
  public suspend fun uploadBlob(
    input: ByteArray,
    inputContentType: ContentType,
    proxy: String? = null,
  ): UploadBlobResponse = client.procedure(
      nsid = "example.uploadBlob",
      params = NoXrpcParams,
      paramsSerializer = NoXrpcParams.serializer(),
      input = input,
      inputContentType = inputContentType,
      responseSerializer = UploadBlobResponse.serializer(),
      proxy = proxy,
  )
}
