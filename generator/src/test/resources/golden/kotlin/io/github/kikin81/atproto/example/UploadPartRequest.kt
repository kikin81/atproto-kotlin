package io.github.kikin81.atproto.example

import kotlin.Long
import kotlin.String
import kotlinx.serialization.Serializable

/**
 * Upload one part of a multipart upload.
 */
@Serializable
public data class UploadPartRequest(
  public val jobId: String,
  public val partNumber: Long,
)
