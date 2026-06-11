package io.github.kikin81.atproto.chat.bsky.group

import kotlin.String
import kotlinx.serialization.Serializable

@Serializable
public data class CreateGroupResponse(
  public val convoId: String,
)
