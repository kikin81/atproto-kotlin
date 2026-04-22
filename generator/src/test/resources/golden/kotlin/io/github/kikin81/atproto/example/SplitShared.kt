package io.github.kikin81.atproto.example

import io.github.kikin81.atproto.runtime.Did
import kotlin.Long
import kotlin.String
import kotlinx.serialization.Serializable

@Serializable
public data class SplitShared(
  public val id: Did,
  public val note: String? = null,
  public val score: Long? = null,
)
