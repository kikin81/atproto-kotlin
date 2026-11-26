package io.github.kikin81.atproto.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.net.ConnectException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TokenEndpointTransportTest {

    private class Recorded(val url: String, val form: Map<String, String>, val dpop: String?)

    private fun recordingClient(
        into: MutableList<Recorded>,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = """{"ok":true}""",
    ): HttpClient = HttpClient(
        MockEngine { request ->
            val form = parseQueryString(request.body.toByteArray().decodeToString())
                .entries().associate { (key, values) -> key to values.last() }
            into += Recorded(request.url.toString(), form, request.headers["DPoP"])
            respond(
                ByteReadChannel(body),
                status,
                headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    "DPoP-Nonce" to listOf("nonce-1"),
                    HttpHeaders.Date to listOf("Wed, 25 Nov 2026 10:00:00 GMT"),
                ),
            )
        },
    )

    private fun request(kind: TokenEndpointRequestKind) = TokenEndpointRequest(
        kind = kind,
        endpointUrl = "https://auth.test/oauth/endpoint",
        issuer = "https://auth.test",
        formParameters = mapOf("client_id" to "https://app.test/meta.json", "a" to "b c"),
        dpopProof = "proof.jwt.sig",
        clientAuthKid = "k1",
    )

    @Test
    fun directTransportPostsTheFormAndProofToTheAuthorizationServer() = runTest {
        val calls = mutableListOf<Recorded>()
        val transport = DirectTokenEndpointTransport(recordingClient(calls))

        val response = transport.send(request(TokenEndpointRequestKind.PushedAuthorization))

        val call = calls.single()
        assertEquals("https://auth.test/oauth/endpoint", call.url)
        assertEquals(mapOf("client_id" to "https://app.test/meta.json", "a" to "b c"), call.form)
        assertEquals("proof.jwt.sig", call.dpop)
        assertEquals(200, response.status)
        assertEquals("""{"ok":true}""", response.body)
        assertEquals("nonce-1", response.dpopNonce)
        assertEquals("Wed, 25 Nov 2026 10:00:00 GMT", response.serverDate)
        assertEquals(TokenEndpointResponseOrigin.AuthorizationServer, response.origin)
        assertNull(response.clientAuthKid, "The direct path performs no client authentication")
        assertNull(response.clientAuthMethod)
        assertFalse(response.replayed)
    }

    @Test
    fun directTransportRelaysErrorStatusAndBody() = runTest {
        val calls = mutableListOf<Recorded>()
        val transport = DirectTokenEndpointTransport(
            recordingClient(calls, HttpStatusCode.BadRequest, """{"error":"use_dpop_nonce"}"""),
        )

        val response = transport.send(request(TokenEndpointRequestKind.Refresh))

        assertEquals(400, response.status)
        assertEquals("""{"error":"use_dpop_nonce"}""", response.body)
    }

    @Test
    fun singleUseRequestsGoThroughTheSingleUseClient() = runTest {
        val general = mutableListOf<Recorded>()
        val singleUse = mutableListOf<Recorded>()
        val transport = DirectTokenEndpointTransport(recordingClient(general), recordingClient(singleUse))

        transport.send(request(TokenEndpointRequestKind.PushedAuthorization))
        transport.send(request(TokenEndpointRequestKind.Revoke))
        transport.send(request(TokenEndpointRequestKind.CodeExchange))
        transport.send(request(TokenEndpointRequestKind.Refresh))

        assertEquals(2, general.size, "PAR and revoke spend nothing single-use")
        assertEquals(2, singleUse.size, "Code exchange and refresh must never be silently re-sent")
    }

    @Test
    fun directTransportDoesNotTrackRefreshOutcomes() {
        assertFalse(DirectTokenEndpointTransport(HttpClient(MockEngine { respond("") })).tracksRefreshOutcome)
    }

    @Test
    fun directTransportLetsNetworkFailuresThrough() = runTest {
        val transport = DirectTokenEndpointTransport(HttpClient(MockEngine { throw ConnectException("refused") }))

        assertFailsWith<ConnectException> { transport.send(request(TokenEndpointRequestKind.Refresh)) }
    }

    @Test
    fun issuerIsTheOriginOfAnEndpoint() {
        assertEquals("https://bsky.social", issuerFromEndpoint("https://bsky.social/oauth/token"))
        assertEquals("https://pds.example:8443", issuerFromEndpoint("https://pds.example:8443/oauth/token?x=1"))
        assertEquals("https://pds.example", issuerFromEndpoint("https://pds.example:443/oauth/token"))
    }

    @Test
    fun sessionStoredByAnEarlierReleaseStillLoads() {
        // Exactly the fields 10.2.x wrote: no issuer, key id, method or in-doubt flag.
        val stored = """
            {"accessToken":"at","refreshToken":"rt","did":"did:plc:x","handle":"alice.test",
             "pdsUrl":"https://pds.test","tokenEndpoint":"https://auth.test/oauth/token",
             "revocationEndpoint":"https://auth.test/oauth/revoke","clientId":"https://app.test/meta.json",
             "dpopPrivateKey":[1,2,3],"dpopPublicKey":[4,5,6],"authServerNonce":"n","clockOffsetSeconds":2,"pdsNonce":"p"}
        """.trimIndent()

        val session = Json.decodeFromString(OAuthSession.serializer(), stored)

        assertEquals("rt", session.refreshToken)
        assertNull(session.issuer)
        assertNull(session.clientAuthKid)
        assertNull(session.clientAuthMethod)
        assertFalse(session.refreshInDoubt)
    }

    @Test
    fun pendingLoginStoredByAnEarlierReleaseStillLoads() {
        val stored = """
            {"state":"s","codeVerifier":"v","redirectUri":"https://app.test/cb","flowOrigin":"Login","authServerNonce":null,
             "dpopPrivateKey":[1],"dpopPublicKey":[2],"issuer":"https://auth.test","authorizationEndpoint":"https://auth.test/authorize",
             "tokenEndpoint":"https://auth.test/token","parEndpoint":"https://auth.test/par"}
        """.trimIndent()

        val pending = Json.decodeFromString(PendingAuth.serializer(), stored)

        assertNull(pending.clientAuthKid)
    }

    @Test
    fun pendingLoginRoundTripsItsKeyId() {
        val pending = PendingAuth(
            state = "s",
            codeVerifier = "v",
            redirectUri = "https://app.test/cb",
            flowOrigin = "Login",
            authServerNonce = null,
            dpopPrivateKey = byteArrayOf(1),
            dpopPublicKey = byteArrayOf(2),
            issuer = "https://auth.test",
            authorizationEndpoint = "https://auth.test/authorize",
            tokenEndpoint = "https://auth.test/token",
            parEndpoint = "https://auth.test/par",
            clientAuthKid = "k1",
        )

        val back = Json.decodeFromString(PendingAuth.serializer(), Json.encodeToString(PendingAuth.serializer(), pending))

        assertEquals(pending, back)
        assertEquals("k1", back.clientAuthKid)
        assertFalse(pending == pending.copy(clientAuthKid = "k2"), "The key id is part of the record's identity")
    }
}
