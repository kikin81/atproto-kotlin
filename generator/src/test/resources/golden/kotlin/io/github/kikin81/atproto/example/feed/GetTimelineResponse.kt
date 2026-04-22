package io.github.kikin81.atproto.example.feed

import io.github.kikin81.atproto.example.StrongRef
import kotlin.String
import kotlin.collections.List
import kotlinx.serialization.Serializable

@Serializable
public data class GetTimelineResponse(
  public val cursor: String? = null,
  public val feed: List<StrongRef>,
)
