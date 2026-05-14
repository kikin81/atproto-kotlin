package io.github.kikin81.atproto.chat.bsky.convo

import kotlin.String
import kotlin.collections.List
import kotlinx.serialization.Serializable

@Serializable
public data class ListConvosResponse(
  public val convos: List<String>,
  public val cursor: String? = null,
)
