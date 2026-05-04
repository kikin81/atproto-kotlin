package io.github.kikin81.atproto.example

import io.github.kikin81.atproto.runtime.Blob
import kotlinx.serialization.Serializable

@Serializable
public data class UploadBlobResponse(
  public val blob: Blob,
)
