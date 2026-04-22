package io.github.kikin81.atproto.example

import io.github.kikin81.atproto.runtime.AtField
import io.github.kikin81.atproto.runtime.AtFieldSerializer
import io.github.kikin81.atproto.runtime.Did
import kotlin.Long
import kotlin.OptIn
import kotlin.String
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
@OptIn(ExperimentalSerializationApi::class)
public data class SplitSharedInput(
  public val id: Did,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  @Serializable(with = AtFieldSerializer::class)
  public val note: AtField<String> = AtField.Missing,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  @Serializable(with = AtFieldSerializer::class)
  public val score: AtField<Long> = AtField.Missing,
)
