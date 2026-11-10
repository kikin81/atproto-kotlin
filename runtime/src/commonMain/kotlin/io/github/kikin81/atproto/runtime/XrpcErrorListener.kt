package io.github.kikin81.atproto.runtime

/**
 * Observes XRPC calls that finally fail, for example to report them to
 * telemetry. Called once per failed call, after any auth retry, with the
 * method's NSID and the [XrpcError] about to be thrown.
 *
 * Keep it fast and non-blocking: it runs on the calling coroutine. Exceptions
 * it throws are attached to the error as suppressed and do not change what
 * the caller receives.
 */
public fun interface XrpcErrorListener {
    public fun onError(
        nsid: String,
        error: XrpcError,
    )
}
