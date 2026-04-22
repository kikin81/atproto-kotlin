package io.github.kikin81.atproto.example.feed

import io.github.kikin81.atproto.example.StrongRef
import kotlinx.serialization.Serializable

@Serializable
public data class PostReplyRef(
  public val parent: StrongRef,
  public val root: StrongRef,
)
