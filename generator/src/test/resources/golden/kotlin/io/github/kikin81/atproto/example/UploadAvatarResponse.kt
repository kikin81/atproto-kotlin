package io.github.kikin81.atproto.example

import io.github.kikin81.atproto.runtime.Blob
import kotlinx.serialization.Serializable

@Serializable
public data class UploadAvatarResponse(
  public val blob: Blob,
)
