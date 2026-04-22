package io.github.kikin81.atproto.example.embed

import io.github.kikin81.atproto.example.feed.PostEmbedUnion
import kotlin.collections.List
import kotlinx.serialization.Serializable

@Serializable
public data class Images(
  public val images: List<ImagesImage>,
) : PostEmbedUnion
