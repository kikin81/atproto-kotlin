package io.github.kikin81.atproto.example.actor

import io.github.kikin81.atproto.runtime.Did
import io.github.kikin81.atproto.runtime.Handle
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("example.actor.defs#profileView")
public data class ProfileView(
  public val description: String? = null,
  public val did: Did,
  public val displayName: String? = null,
  public val handle: Handle,
)
