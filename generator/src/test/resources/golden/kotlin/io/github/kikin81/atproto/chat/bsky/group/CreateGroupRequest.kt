package io.github.kikin81.atproto.chat.bsky.group

import io.github.kikin81.atproto.runtime.Did
import kotlin.String
import kotlin.collections.List
import kotlinx.serialization.Serializable

/**
 * Create a group convo with up to 49 invited members.
 */
@Serializable
public data class CreateGroupRequest(
  public val members: List<Did>,
  public val name: String,
)
