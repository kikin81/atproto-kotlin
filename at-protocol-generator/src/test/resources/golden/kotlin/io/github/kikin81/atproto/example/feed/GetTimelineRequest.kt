package io.github.kikin81.atproto.example.feed

import kotlin.Long
import kotlin.String
import kotlinx.serialization.Serializable

/**
 * Get a paginated timeline.
 */
@Serializable
public data class GetTimelineRequest(
  public val cursor: String? = null,
  public val limit: Long? = null,
)
