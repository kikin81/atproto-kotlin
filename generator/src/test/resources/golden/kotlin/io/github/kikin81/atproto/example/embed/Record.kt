package io.github.kikin81.atproto.example.embed

import io.github.kikin81.atproto.example.StrongRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("example.embed.record")
public data class Record(
  public val record: StrongRef,
)
