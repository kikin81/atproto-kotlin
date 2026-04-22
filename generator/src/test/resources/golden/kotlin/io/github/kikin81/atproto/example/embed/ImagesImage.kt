package io.github.kikin81.atproto.example.embed

import io.github.kikin81.atproto.runtime.Blob
import kotlin.String
import kotlinx.serialization.Serializable

@Serializable
public data class ImagesImage(
  public val alt: String,
  public val image: Blob,
)
