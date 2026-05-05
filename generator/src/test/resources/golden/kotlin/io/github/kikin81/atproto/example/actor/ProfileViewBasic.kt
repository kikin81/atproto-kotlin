package io.github.kikin81.atproto.example.actor

import io.github.kikin81.atproto.runtime.Did
import io.github.kikin81.atproto.runtime.Handle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("example.actor.defs#profileViewBasic")
public data class ProfileViewBasic(
  public val did: Did,
  public val handle: Handle,
)
