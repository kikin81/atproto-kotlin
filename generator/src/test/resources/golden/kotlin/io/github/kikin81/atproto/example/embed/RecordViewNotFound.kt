package io.github.kikin81.atproto.example.embed

import io.github.kikin81.atproto.runtime.AtUri
import kotlin.Boolean
import kotlinx.serialization.Serializable

@Serializable
public data class RecordViewNotFound(
  public val notFound: Boolean,
  public val uri: AtUri,
) : RecordViewRecordUnion
