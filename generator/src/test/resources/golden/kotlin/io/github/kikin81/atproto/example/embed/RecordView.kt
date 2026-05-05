package io.github.kikin81.atproto.example.embed

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("example.embed.record#view")
public data class RecordView(
  public val record: RecordViewRecordUnion,
) : RecordViewRecordEmbedsUnion
