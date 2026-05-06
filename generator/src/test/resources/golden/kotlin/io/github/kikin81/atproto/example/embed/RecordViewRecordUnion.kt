package io.github.kikin81.atproto.example.embed

import io.github.kikin81.atproto.runtime.OpenUnionMember
import io.github.kikin81.atproto.runtime.OpenUnionSerializer
import io.github.kikin81.atproto.runtime.UnknownMemberSerializer
import io.github.kikin81.atproto.runtime.UnknownOpenUnionMember
import kotlin.String
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable(with = RecordViewRecordUnionSerializer::class)
public interface RecordViewRecordUnion : OpenUnionMember {
  @Serializable(with = RecordViewRecordUnionUnknownSerializer::class)
  public data class Unknown(
    override val type: String,
    override val raw: JsonObject,
  ) : RecordViewRecordUnion,
      UnknownOpenUnionMember
}

public object RecordViewRecordUnionUnknownSerializer : UnknownMemberSerializer<RecordViewRecordUnion.Unknown>("io.github.kikin81.atproto.example.embed.RecordViewRecordUnion.Unknown") {
  public override fun construct(type: String, raw: JsonObject): RecordViewRecordUnion.Unknown = RecordViewRecordUnion.Unknown(type, raw)
}

public object RecordViewRecordUnionSerializer : OpenUnionSerializer<RecordViewRecordUnion>(RecordViewRecordUnion::class) {
  public override fun selectKnownDeserializer(type: String): KSerializer<out RecordViewRecordUnion>? = when (type) {
    "example.embed.record#viewNotFound" -> RecordViewNotFound.serializer()
    "example.embed.record#viewRecord" -> RecordViewRecord.serializer()
    else -> null
  }

  public override fun selectKnownSerializer(`value`: RecordViewRecordUnion): KSerializer<out RecordViewRecordUnion>? = when (value) {
    is RecordViewNotFound -> RecordViewNotFound.serializer()
    is RecordViewRecord -> RecordViewRecord.serializer()
    is RecordViewRecordUnion.Unknown -> null
    else -> null
  }

  public override fun unknownSerializer(): KSerializer<out RecordViewRecordUnion> = RecordViewRecordUnionUnknownSerializer
}
