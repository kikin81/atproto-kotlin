package io.github.kikin81.atproto.example.embed

import io.github.kikin81.atproto.runtime.Blob
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("example.embed.images#image")
public data class ImagesImage(
  public val alt: String,
  public val image: Blob,
)
