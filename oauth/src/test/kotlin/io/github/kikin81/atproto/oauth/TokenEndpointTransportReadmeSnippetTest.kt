package io.github.kikin81.atproto.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps the "Confidential clients" example in `oauth/README.md` honest: the
 * transport below is that snippet, and it has to compile and behave as the
 * surrounding text says. Change one, change the other.
 */
class TokenEndpointTransportReadmeSnippetTest {

    class MediatedTransport(
        private val backendUrl: String,
        private val httpClient: HttpClient,
    ) : TokenEndpointTransport {

        // Ask the SDK to remember a refresh whose outcome is unknown, so a lost
        // response is never followed by a retry over a different route.
        override val tracksRefreshOutcome: Boolean = true

        override suspend fun send(request: TokenEndpointRequest): TokenEndpointResponse {
            val path = when (request.kind) {
                TokenEndpointRequestKind.PushedAuthorization -> "par"
                TokenEndpointRequestKind.CodeExchange, TokenEndpointRequestKind.Refresh -> "token"
                TokenEndpointRequestKind.Revoke -> "revoke"
            }
            val response = httpClient.submitForm(
                url = "$backendUrl/$path",
                formParameters = Parameters.build {
                    request.formParameters.forEach { (name, value) -> append(name, value) }
                },
            ) {
                header("DPoP", request.dpopProof) // made for the server's endpoint; forward unchanged
                header("X-Issuer", request.issuer) // the backend looks the endpoint up itself
                request.clientAuthKid?.let { header("X-Client-Auth-Kid", it) }
            }
            val relayed = response.headers["X-Origin"] == "upstream"
            return TokenEndpointResponse(
                status = response.status.value,
                body = response.bodyAsText(),
                dpopNonce = response.headers["DPoP-Nonce"],
                serverDate = response.headers["X-Upstream-Date"],
                origin = if (relayed) {
                    TokenEndpointResponseOrigin.AuthorizationServer
                } else {
                    TokenEndpointResponseOrigin.Mediator
                },
                clientAuthKid = response.headers["X-Client-Auth-Kid"],
                clientAuthMethod = response.headers["X-Client-Auth-Method"],
            )
        }
    }

    private fun request() = TokenEndpointRequest(
        kind = TokenEndpointRequestKind.Refresh,
        endpointUrl = "https://auth.test/oauth/token",
        issuer = "https://auth.test",
        formParameters = mapOf("grant_type" to "refresh_token"),
        dpopProof = "proof.jwt.sig",
        clientAuthKid = "k1",
    )

    @Test
    fun relayedAnswerIsMarkedAsTheAuthorizationServers() = runTest {
        var seenUrl = ""
        var seenIssuer: String? = null
        val client = HttpClient(
            MockEngine { call ->
                seenUrl = call.url.toString()
                seenIssuer = call.headers["X-Issuer"]
                respond(
                    ByteReadChannel("""{"access_token":"at"}"""),
                    HttpStatusCode.OK,
                    headersOf(
                        "X-Origin" to listOf("upstream"),
                        "X-Client-Auth-Kid" to listOf("k1"),
                        "X-Client-Auth-Method" to listOf("private_key_jwt"),
                    ),
                )
            },
        )
        val transport = MediatedTransport("https://auth.example.com", client)

        val response = transport.send(request())

        assertTrue(transport.tracksRefreshOutcome)
        assertEquals("https://auth.example.com/token", seenUrl)
        assertEquals("https://auth.test", seenIssuer)
        assertEquals(TokenEndpointResponseOrigin.AuthorizationServer, response.origin)
        assertEquals("k1", response.clientAuthKid)
        assertEquals("private_key_jwt", response.clientAuthMethod)
    }

    @Test
    fun anAnswerWithoutTheRelayMarkIsTheBackendsOwn() = runTest {
        val client = HttpClient(
            MockEngine { respond(ByteReadChannel("""{"error":"invalid_grant"}"""), HttpStatusCode.BadRequest) },
        )

        val response = MediatedTransport("https://auth.example.com", client).send(request())

        assertEquals(TokenEndpointResponseOrigin.Mediator, response.origin)
    }
}
