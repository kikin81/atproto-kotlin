package io.github.kikin81.atproto.runtime

/**
 * Supplies authentication headers for XRPC requests. Attached at client
 * construction or per-call via [XrpcClient.query] / [XrpcClient.procedure]
 * overrides.
 *
 * The method receives the HTTP [method] and target [url] so that
 * proof-of-possession schemes like DPoP can bind the proof to the
 * specific request.
 *
 * Return an empty map for unauthenticated requests.
 *
 * ## Migration from bearerToken()
 *
 * This interface was widened from `bearerToken(): String?` to
 * `authHeaders(method, url): Map<String, String>` to support DPoP
 * (which needs both `Authorization: DPoP <token>` and `DPoP: <proof>`).
 * Existing consumers should migrate:
 *
 * ```
 * // Before
 * val auth = BearerTokenAuth("my-token")
 *
 * // After — same class, same constructor, new internals
 * val auth = BearerTokenAuth("my-token")
 * ```
 *
 * `BearerTokenAuth` and `NoAuth` are updated in-place; custom impls
 * need to implement `authHeaders` instead of `bearerToken`.
 */
public interface AuthProvider {
    /**
     * Returns the HTTP headers to attach to an XRPC request.
     *
     * @param method The HTTP method (GET, POST, etc.)
     * @param url The full target URL (scheme + host + path)
     * @return A map of header name → header value. Typically contains
     *   at least `"Authorization"`. Empty for unauthenticated requests.
     */
    public suspend fun authHeaders(method: String, url: String): Map<String, String>

    /**
     * Called by [XrpcClient] when a request returns HTTP 401. The provider
     * can inspect the response headers (e.g. `DPoP-Nonce`) and update its
     * internal state for a retry.
     *
     * @return `true` if the provider handled the error and the request
     *   should be retried with fresh [authHeaders], `false` otherwise.
     */
    public suspend fun onUnauthorized(responseHeaders: Map<String, String>): Boolean = false
}

/** No authentication — requests are sent without auth headers. */
public object NoAuth : AuthProvider {
    override suspend fun authHeaders(method: String, url: String): Map<String, String> = emptyMap()
}

/** Fixed bearer token. Suitable for app-password sessions or service tokens. */
public class BearerTokenAuth(private val token: String) : AuthProvider {
    override suspend fun authHeaders(method: String, url: String): Map<String, String> = mapOf("Authorization" to "Bearer $token")
}
