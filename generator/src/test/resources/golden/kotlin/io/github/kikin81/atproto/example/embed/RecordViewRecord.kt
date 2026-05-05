package io.github.kikin81.atproto.example.embed

import io.github.kikin81.atproto.example.actor.ProfileViewBasic
import io.github.kikin81.atproto.runtime.AtField
import io.github.kikin81.atproto.runtime.AtFieldSerializer
import io.github.kikin81.atproto.runtime.AtUri
import io.github.kikin81.atproto.runtime.Cid
import io.github.kikin81.atproto.runtime.Datetime
import kotlin.OptIn
import kotlin.collections.List
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
@SerialName("example.embed.record#viewRecord")
@OptIn(ExperimentalSerializationApi::class)
public data class RecordViewRecord(
  public val author: ProfileViewBasic,
  public val cid: Cid,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  @Serializable(with = AtFieldSerializer::class)
  public val embeds: AtField<List<RecordViewRecordEmbedsUnion>> = AtField.Missing,
  public val indexedAt: Datetime,
  public val uri: AtUri,
  /**
   * The record data itself.
   */
  public val `value`: JsonObject,
) : RecordViewRecordUnion
