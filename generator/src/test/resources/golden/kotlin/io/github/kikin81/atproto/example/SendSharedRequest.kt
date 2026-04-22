package io.github.kikin81.atproto.example

import kotlinx.serialization.Serializable

/**
 * Round-trips a Shared value through a procedure.
 */
@Serializable
public data class SendSharedRequest(
  public val req: SplitShared,
)
