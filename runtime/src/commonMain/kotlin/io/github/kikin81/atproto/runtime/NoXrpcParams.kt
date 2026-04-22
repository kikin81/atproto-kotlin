package io.github.kikin81.atproto.runtime

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Sentinel "no query-string parameters" value for XRPC calls that take no
 * URL parameters (e.g. procedures with a JSON input body and nothing else,
 * or queries with no filters). Emitted by the generator's service classes
 * wherever a `params` argument is required by the [XrpcClient] API but the
 * underlying lexicon def declares no parameters.
 *
 * Serializes to an empty JSON object, which [XrpcClient.appendQueryParams]
 * then iterates over and emits zero query params — the desired behavior.
 */
@Serializable
public data object NoXrpcParams

/**
 * Stable [KSerializer] reference for procedures and queries whose
 * `output.schema` is absent. The generator's service classes use this as
 * the `responseSerializer` argument when a method's return type is `Unit`
 * so callers don't have to construct one themselves.
 */
public val UnitResponseSerializer: KSerializer<Unit> = serializer()
