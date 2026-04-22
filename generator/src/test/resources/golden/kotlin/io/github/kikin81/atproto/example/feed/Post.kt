package io.github.kikin81.atproto.example.feed

import io.github.kikin81.atproto.runtime.AtField
import io.github.kikin81.atproto.runtime.AtFieldSerializer
import io.github.kikin81.atproto.runtime.Datetime
import io.github.kikin81.atproto.runtime.Language
import kotlin.OptIn
import kotlin.String
import kotlin.collections.List
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
@OptIn(ExperimentalSerializationApi::class)
public data class Post(
  public val createdAt: Datetime,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  @Serializable(with = AtFieldSerializer::class)
  public val embed: AtField<PostEmbedUnion> = AtField.Missing,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  @Serializable(with = AtFieldSerializer::class)
  public val langs: AtField<List<Language>> = AtField.Missing,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  @Serializable(with = AtFieldSerializer::class)
  public val reply: AtField<PostReplyRef> = AtField.Missing,
  public val text: String,
)
