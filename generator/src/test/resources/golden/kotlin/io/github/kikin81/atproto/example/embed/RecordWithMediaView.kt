package io.github.kikin81.atproto.example.embed

import io.github.kikin81.atproto.example.feed.PostEmbedUnion
import kotlinx.serialization.Serializable

@Serializable
public data class RecordWithMediaView(
  public val media: ImagesView,
  public val record: RecordView,
) : RecordViewRecordEmbedsUnion,
    PostEmbedUnion
