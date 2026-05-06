package io.github.kikin81.atproto.example.embed

import io.github.kikin81.atproto.runtime.OpenUnionMember
import io.github.kikin81.atproto.runtime.OpenUnionSerializer
import io.github.kikin81.atproto.runtime.UnknownMemberSerializer
import io.github.kikin81.atproto.runtime.UnknownOpenUnionMember
import kotlin.String
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable(with = RecordViewRecordEmbedsUnionSerializer::class)
public interface RecordViewRecordEmbedsUnion : OpenUnionMember {
  @Serializable(with = RecordViewRecordEmbedsUnionUnknownSerializer::class)
  public data class Unknown(
    override val type: String,
    override val raw: JsonObject,
  ) : RecordViewRecordEmbedsUnion,
      UnknownOpenUnionMember
}

public object RecordViewRecordEmbedsUnionUnknownSerializer : UnknownMemberSerializer<RecordViewRecordEmbedsUnion.Unknown>("io.github.kikin81.atproto.example.embed.RecordViewRecordEmbedsUnion.Unknown") {
  public override fun construct(type: String, raw: JsonObject): RecordViewRecordEmbedsUnion.Unknown = RecordViewRecordEmbedsUnion.Unknown(type, raw)
}

public object RecordViewRecordEmbedsUnionSerializer : OpenUnionSerializer<RecordViewRecordEmbedsUnion>(RecordViewRecordEmbedsUnion::class) {
  public override fun selectKnownDeserializer(type: String): KSerializer<out RecordViewRecordEmbedsUnion>? = when (type) {
    "example.embed.images#view" -> ImagesView.serializer()
    "example.embed.record#view" -> RecordView.serializer()
    "example.embed.recordWithMedia#view" -> RecordWithMediaView.serializer()
    else -> null
  }

  public override fun selectKnownSerializer(`value`: RecordViewRecordEmbedsUnion): KSerializer<out RecordViewRecordEmbedsUnion>? = when (value) {
    is ImagesView -> ImagesView.serializer()
    is RecordView -> RecordView.serializer()
    is RecordWithMediaView -> RecordWithMediaView.serializer()
    is RecordViewRecordEmbedsUnion.Unknown -> null
    else -> null
  }

  public override fun unknownSerializer(): KSerializer<out RecordViewRecordEmbedsUnion> = RecordViewRecordEmbedsUnionUnknownSerializer
}
