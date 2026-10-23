package io.github.kikin81.atproto.example

import kotlin.Long
import kotlinx.serialization.Serializable

@Serializable
public data class UploadPartResponse(
  public val partNumber: Long,
  public val sizeBytes: Long,
)
