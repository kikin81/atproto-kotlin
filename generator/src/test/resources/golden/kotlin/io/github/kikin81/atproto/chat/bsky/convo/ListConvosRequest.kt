package io.github.kikin81.atproto.chat.bsky.convo

import kotlin.Long
import kotlin.String
import kotlinx.serialization.Serializable

/**
 * List conversations.
 */
@Serializable
public data class ListConvosRequest(
  public val cursor: String? = null,
  public val limit: Long? = null,
)
