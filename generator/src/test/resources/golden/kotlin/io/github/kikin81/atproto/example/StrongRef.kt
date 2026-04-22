package io.github.kikin81.atproto.example

import io.github.kikin81.atproto.runtime.AtUri
import io.github.kikin81.atproto.runtime.Cid
import kotlinx.serialization.Serializable

@Serializable
public data class StrongRef(
  public val cid: Cid,
  public val uri: AtUri,
)
