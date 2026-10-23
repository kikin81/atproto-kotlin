package io.github.kikin81.atproto.example

import kotlin.Long
import kotlinx.serialization.Serializable

@Serializable
public data class UploadChunkResponse(
  public val received: Long,
)
