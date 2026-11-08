package io.github.kikin81.atproto.example

import kotlin.Long
import kotlin.String
import kotlinx.serialization.Serializable

/**
 * Upload a chunk, optionally resuming at an offset.
 */
@Serializable
public data class UploadChunkRequest(
  public val label: String? = null,
  public val offset: Long? = null,
)
