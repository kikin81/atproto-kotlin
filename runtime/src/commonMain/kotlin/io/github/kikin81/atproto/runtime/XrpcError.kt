package io.github.kikin81.atproto.runtime

import kotlinx.serialization.Serializable

/**
 * Base type for all errors surfaced by [XrpcClient]. Generated XRPC bindings
 * extend this with typed subclasses for each declared error name; unknown
 * error names fall through to [Unknown].
 */
public open class XrpcError(
    public val errorName: String,
    public val errorMessage: String?,
    public val status: Int,
) : RuntimeException("$errorName${errorMessage?.let { ": $it" } ?: ""} (HTTP $status)") {

    /** Fallback for any error name the caller has not mapped to a typed variant. */
    public class Unknown(
        name: String,
        message: String?,
        status: Int,
    ) : XrpcError(name, message, status)
}

/**
 * Maps a decoded `{error, message}` body onto a typed [XrpcError]. Generated
 * per-endpoint code supplies a mapper that pattern-matches on [name]; any
 * unmapped name SHOULD be returned as [XrpcError.Unknown].
 */
public fun interface XrpcErrorMapper {
    public fun map(name: String, message: String?, status: Int): XrpcError
}

/** Default mapper that always returns [XrpcError.Unknown]. */
public val DefaultXrpcErrorMapper: XrpcErrorMapper =
    XrpcErrorMapper { name, message, status -> XrpcError.Unknown(name, message, status) }

@Serializable
internal data class XrpcErrorBody(
    val error: String? = null,
    val message: String? = null,
)
