package io.github.kikin81.atproto.example.feed

import io.github.kikin81.atproto.example.embed.Images
import io.github.kikin81.atproto.example.embed.RecordWithMediaView
import io.github.kikin81.atproto.runtime.OpenUnionMember
import io.github.kikin81.atproto.runtime.OpenUnionSerializer
import io.github.kikin81.atproto.runtime.UnknownMemberSerializer
import io.github.kikin81.atproto.runtime.UnknownOpenUnionMember
import kotlin.String
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable(with = PostEmbedUnionSerializer::class)
public interface PostEmbedUnion : OpenUnionMember {
  @Serializable(with = PostEmbedUnionUnknownSerializer::class)
  public data class Unknown(
    override val type: String,
    override val raw: JsonObject,
  ) : PostEmbedUnion,
      UnknownOpenUnionMember
}

public object PostEmbedUnionUnknownSerializer :
    UnknownMemberSerializer<PostEmbedUnion.Unknown>("io.github.kikin81.atproto.example.feed.PostEmbedUnion.Unknown")
    {
  public override fun construct(type: String, raw: JsonObject): PostEmbedUnion.Unknown =
      PostEmbedUnion.Unknown(type, raw)
}

public object PostEmbedUnionSerializer : OpenUnionSerializer<PostEmbedUnion>(PostEmbedUnion::class)
    {
  public override fun selectKnownDeserializer(type: String): KSerializer<out PostEmbedUnion>? = when
      (type) {
    "example.embed.images" -> Images.serializer()
    "example.embed.recordWithMedia#view" -> RecordWithMediaView.serializer()
    else -> null
  }

  public override fun selectKnownSerializer(`value`: PostEmbedUnion):
      KSerializer<out PostEmbedUnion>? = when (value) {
    is Images -> Images.serializer()
    is RecordWithMediaView -> RecordWithMediaView.serializer()
    is PostEmbedUnion.Unknown -> null
    else -> null
  }

  public override fun unknownSerializer(): KSerializer<out PostEmbedUnion> =
      PostEmbedUnionUnknownSerializer
}
