package io.github.kikin81.atproto.oauth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.Url

/**
 * Carries the OAuth requests that authenticate the client — the pushed
 * authorization request, the authorization-code exchange, the refresh, and
 * the revocation — to the authorization server and brings its answer back.
 *
 * The default, [DirectTokenEndpointTransport], posts straight to the server,
 * which is all a public client needs. A confidential client supplies its own
 * transport that sends the request through a backend holding the client's
 * signing key (a "token-mediating backend"); the backend adds the client
 * assertion and relays the server's response. XRPC calls never go through a
 * transport.
 *
 * The DPoP proof in a request is always made for the authorization server's
 * own endpoint ([TokenEndpointRequest.endpointUrl]). A transport that relays
 * must forward it unchanged.
 *
 * ### Failures
 *
 * Throw [TokenRequestNotSentException] when the request provably never left
 * (so nothing single-use was spent). Any other exception is treated as an
 * unknown outcome: the server may have acted on the request.
 */
fun interface TokenEndpointTransport {

    /**
     * Whether a refresh sent through this transport can end with an unknown
     * outcome that a *different route* must not retry.
     *
     * When `true`, [DpopAuthProvider] durably marks the session
     * ([OAuthSession.refreshInDoubt]) before each refresh and reports an
     * earlier unknown outcome on the next one
     * ([TokenEndpointRequest.outcomeInDoubt]), at the cost of one extra
     * session write per refresh. The direct transport has only one route, so
     * it leaves this `false`.
     */
    val tracksRefreshOutcome: Boolean get() = false

    suspend fun send(request: TokenEndpointRequest): TokenEndpointResponse
}

/** Which client-authenticated OAuth request a [TokenEndpointRequest] is. */
enum class TokenEndpointRequestKind {
    /** RFC 9126 pushed authorization request. Spends nothing; safe to re-send. */
    PushedAuthorization,

    /** Authorization-code grant. The code is single-use. */
    CodeExchange,

    /** Refresh-token grant. The refresh token is single-use. */
    Refresh,

    /** RFC 7009 token revocation. Best effort. */
    Revoke,
}

/**
 * One client-authenticated OAuth request.
 *
 * @property endpointUrl The authorization server's endpoint for this request.
 * @property issuer The authorization server's issuer identifier. A relaying
 *   transport should send this, not [endpointUrl], so the relay can look the
 *   endpoint up itself rather than trust a URL from a device.
 * @property formParameters The `application/x-www-form-urlencoded` body,
 *   including `client_id`.
 * @property dpopProof The DPoP proof JWT, made for [endpointUrl].
 * @property clientAuthKid The id of the client key this session or sign-in is
 *   bound to, if one has been recorded. A relay must sign with exactly this
 *   key or refuse: the server deletes a session presented with another key.
 * @property outcomeInDoubt `true` when an earlier attempt with this same
 *   refresh token ended without a definitive answer. The token may already be
 *   spent, so the request must not be sent by any route that cannot recover
 *   the earlier response. Only ever `true` for [TokenEndpointRequestKind.Refresh]
 *   and only when [TokenEndpointTransport.tracksRefreshOutcome] is set.
 */
class TokenEndpointRequest(
    val kind: TokenEndpointRequestKind,
    val endpointUrl: String,
    val issuer: String,
    val formParameters: Map<String, String>,
    val dpopProof: String,
    val clientAuthKid: String? = null,
    val outcomeInDoubt: Boolean = false,
)

/** Who produced a [TokenEndpointResponse]. */
enum class TokenEndpointResponseOrigin {
    /** The authorization server's own answer, direct or relayed unchanged. */
    AuthorizationServer,

    /**
     * Produced by something between the device and the server. Never treated
     * as tokens, as a nonce challenge, or as grounds to end a session.
     */
    Mediator,
}

/**
 * The answer to a [TokenEndpointRequest].
 *
 * @property serverDate The authorization server's `Date` header, used to
 *   correct clock skew in later DPoP proofs.
 * @property origin Whether the authorization server said this. Only an
 *   authorization-server `invalid_grant` ends a session; a transport that
 *   cannot tell must report [TokenEndpointResponseOrigin.Mediator].
 * @property clientAuthKid The id of the client key the request was signed
 *   with, when a relay performed client authentication.
 * @property clientAuthMethod The client's registered
 *   `token_endpoint_auth_method` as the relay last saw it (`"none"` or
 *   `"private_key_jwt"`), or `null` when unknown.
 * @property replayed `true` when a relay returned a response it had stored
 *   for a refresh token that was already spent, rather than a fresh one.
 */
class TokenEndpointResponse(
    val status: Int,
    val body: String,
    val dpopNonce: String? = null,
    val serverDate: String? = null,
    val origin: TokenEndpointResponseOrigin = TokenEndpointResponseOrigin.AuthorizationServer,
    val clientAuthKid: String? = null,
    val clientAuthMethod: String? = null,
    val replayed: Boolean = false,
)

/**
 * Thrown by a [TokenEndpointTransport] when a request provably did not reach
 * the authorization server, so the code or refresh token it carried was not
 * spent and may be sent again.
 */
class TokenRequestNotSentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Posts each request straight to the authorization server — the behaviour of
 * a public client.
 *
 * @param httpClient Used for the pushed authorization request and revocation.
 * @param singleUseHttpClient Used for the code exchange and the refresh,
 *   which spend something single-use. **Pass a client that never silently
 *   re-sends a request** (with OkHttp, `retryOnConnectionFailure(false)`) and
 *   that has a request timeout. Defaults to [httpClient].
 */
class DirectTokenEndpointTransport(
    private val httpClient: HttpClient,
    private val singleUseHttpClient: HttpClient = httpClient,
) : TokenEndpointTransport {

    override suspend fun send(request: TokenEndpointRequest): TokenEndpointResponse {
        val client = when (request.kind) {
            TokenEndpointRequestKind.CodeExchange, TokenEndpointRequestKind.Refresh -> singleUseHttpClient
            TokenEndpointRequestKind.PushedAuthorization, TokenEndpointRequestKind.Revoke -> httpClient
        }
        val response = client.submitForm(
            url = request.endpointUrl,
            formParameters = Parameters.build {
                request.formParameters.forEach { (name, value) -> append(name, value) }
            },
        ) {
            header("DPoP", request.dpopProof)
        }
        return TokenEndpointResponse(
            status = response.status.value,
            body = response.bodyAsText(),
            dpopNonce = response.headers["DPoP-Nonce"],
            serverDate = response.headers[HttpHeaders.Date],
            origin = TokenEndpointResponseOrigin.AuthorizationServer,
        )
    }
}

/** `token_endpoint_auth_method` value of a confidential atproto client. */
internal const val CLIENT_AUTH_PRIVATE_KEY_JWT = "private_key_jwt"

/**
 * The issuer of the authorization server that owns [endpointUrl]. AT Protocol
 * requires an issuer to be a bare origin, so this is the endpoint's origin
 * with a default port dropped. Used for sessions stored before the issuer was
 * recorded.
 */
internal fun issuerFromEndpoint(endpointUrl: String): String {
    val url = Url(endpointUrl)
    val port = if (url.specifiedPort == 0 || url.specifiedPort == url.protocol.defaultPort) "" else ":${url.specifiedPort}"
    return "${url.protocol.name}://${url.host}$port"
}
