package io.github.kikin81.atproto.example.embed

import kotlin.collections.List
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("example.embed.images#view")
public data class ImagesView(
  public val images: List<ImagesViewImage>,
) : RecordViewRecordEmbedsUnion
