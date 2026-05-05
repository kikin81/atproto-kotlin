package io.github.kikin81.atproto.example.embed

import io.github.kikin81.atproto.runtime.Uri
import kotlin.String
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("example.embed.images#viewImage")
public data class ImagesViewImage(
  public val alt: String,
  public val fullsize: Uri,
  public val thumb: Uri,
)
