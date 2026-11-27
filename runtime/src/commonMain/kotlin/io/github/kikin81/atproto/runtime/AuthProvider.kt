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

    /**
     * Called by [XrpcClient] when a request returns HTTP 401, together with
     * the auth headers that request was sent with. This is the overload
     * [XrpcClient] calls.
     *
     * A 401 can arrive after the provider's credentials have changed: another
     * request may have refreshed them while this one was in flight. What the
     * provider holds when the 401 arrives is then not what the server
     * rejected. [requestAuthHeaders] is, so a provider whose refresh spends a
     * single-use credential should compare against it before refreshing again.
     *
     * The default ignores [requestAuthHeaders] and delegates to the
     * one-argument overload, so an implementation that only overrides that one
     * behaves as before. The exception is a provider that wraps another with
     * `by` delegation: delegation forwards this overload straight to the
     * wrapped provider, so a wrapper that intercepts 401s must override it too.
     *
     * @param responseHeaders The headers of the 401 response.
     * @param requestAuthHeaders What [authHeaders] returned for the rejected
     *   request.
     * @return `true` if the request should be retried with fresh
     *   [authHeaders], `false` otherwise.
     */
    public suspend fun onUnauthorized(
        responseHeaders: Map<String, String>,
        requestAuthHeaders: Map<String, String>,
    ): Boolean = onUnauthorized(responseHeaders)
}

/** No authentication — requests are sent without auth headers. */
public object NoAuth : AuthProvider {
    override suspend fun authHeaders(method: String, url: String): Map<String, String> = emptyMap()
}

/** Fixed bearer token. Suitable for app-password sessions or service tokens. */
public class BearerTokenAuth(private val token: String) : AuthProvider {
    override suspend fun authHeaders(method: String, url: String): Map<String, String> = mapOf("Authorization" to "Bearer $token")
}
