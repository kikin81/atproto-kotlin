package io.github.kikin81.atproto.example.feed

import io.github.kikin81.atproto.example.StrongRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@SerialName("example.feed.post#replyRef")
public data class PostReplyRef(
  public val parent: StrongRef,
  public val root: StrongRef,
)
