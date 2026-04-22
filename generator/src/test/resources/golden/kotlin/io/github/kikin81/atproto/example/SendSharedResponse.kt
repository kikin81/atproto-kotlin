package io.github.kikin81.atproto.example

import kotlinx.serialization.Serializable

@Serializable
public data class SendSharedResponse(
  public val resp: SplitShared,
)
