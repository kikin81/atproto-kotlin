package io.github.kikin81.atproto.oauth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Refresh behaviour of [DpopAuthProvider] when the token endpoint is reached
 * through a [TokenEndpointTransport] that is not the direct one — the shape a
 * token-mediating backend takes.
 */
class DpopAuthProviderTransportTest {

    private class InMemorySessionStore : OAuthSessionStore {
        var session: OAuthSession? = null
        var clearCalls = 0
        override suspend fun load(): OAuthSession? = session
        override suspend fun save(session: OAuthSession) {
            this.session = session
        }
        override suspend fun clear() {
            clearCalls++
            session = null
        }
    }

    /** Answers from a script, and records what it was asked and what was on disk at the time. */
    private class ScriptedTransport(
        private val store: InMemorySessionStore,
        override val tracksRefreshOutcome: Boolean = true,
        private val script: MutableList<suspend (TokenEndpointRequest) -> TokenEndpointResponse>,
    ) : TokenEndpointTransport {
        val requests = mutableListOf<TokenEndpointRequest>()
        val inDoubtOnDiskAtSend = mutableListOf<Boolean?>()

        override suspend fun send(request: TokenEndpointRequest): TokenEndpointResponse {
            requests += request
            inDoubtOnDiskAtSend += store.session?.refreshInDoubt
            return script.removeAt(0)(request)
        }
    }

    private fun success(
        origin: TokenEndpointResponseOrigin = TokenEndpointResponseOrigin.AuthorizationServer,
        kid: String? = null,
        method: String? = null,
    ): suspend (TokenEndpointRequest) -> TokenEndpointResponse = {
        TokenEndpointResponse(
            status = 200,
            body = """{"access_token":"at_new","refresh_token":"rt_new","token_type":"DPoP"}""",
            origin = origin,
            clientAuthKid = kid,
            clientAuthMethod = method,
        )
    }

    private fun error(
        status: Int,
        error: String,
        origin: TokenEndpointResponseOrigin,
        nonce: String? = null,
    ): suspend (TokenEndpointRequest) -> TokenEndpointResponse = {
        TokenEndpointResponse(status = status, body = """{"error":"$error"}""", dpopNonce = nonce, origin = origin)
    }

    private class Fixture(
        val provider: DpopAuthProvider,
        val store: InMemorySessionStore,
        val transport: ScriptedTransport,
        val signer: DpopSigner,
    )

    private fun fixture(
        tracksRefreshOutcome: Boolean = true,
        session: (OAuthSession) -> OAuthSession = { it },
        vararg script: suspend (TokenEndpointRequest) -> TokenEndpointResponse,
    ): Fixture {
        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val store = InMemorySessionStore()
        val initial = session(
            OAuthSession(
                accessToken = expiredJwt(),
                refreshToken = "rt_old",
                did = "did:plc:transport-${System.nanoTime()}",
                handle = "alice.test",
                pdsUrl = "https://pds.test",
                tokenEndpoint = "https://auth.test/oauth/token",
                clientId = "https://app.test/meta.json",
                dpopPrivateKey = exported.privateKeyEncoded,
                dpopPublicKey = exported.publicKeyEncoded,
            ),
        )
        store.session = initial
        val transport = ScriptedTransport(store, tracksRefreshOutcome, script.toMutableList())
        val provider = DpopAuthProvider(initial, signer, store, transport)
        return Fixture(provider, store, transport, signer)
    }

    private suspend fun Fixture.refresh(): Boolean = provider.onUnauthorized(mapOf("WWW-Authenticate" to """DPoP error="invalid_token""""))

    @OptIn(ExperimentalEncodingApi::class)
    private fun expiredJwt(): String {
        val header = Base64.UrlSafe.encode("""{"alg":"ES256","typ":"at+jwt"}""".toByteArray()).trimEnd('=')
        val exp = (System.currentTimeMillis() / 1000) - 3600
        val payload = Base64.UrlSafe.encode("""{"exp":$exp}""".toByteArray()).trimEnd('=')
        return "$header.$payload.fakesignature"
    }

    // --- Who may end a session ---

    @Test
    fun mediatorOriginInvalidGrantDoesNotClearTheSession() = runTest {
        val f = fixture(script = arrayOf(error(400, "invalid_grant", TokenEndpointResponseOrigin.Mediator)))

        assertFailsWith<OAuthRefreshFailedException> { f.refresh() }

        assertEquals(0, f.store.clearCalls, "A backend-produced response must never clear the session")
        assertEquals("rt_old", f.store.session?.refreshToken)
    }

    @Test
    fun authorizationServerOriginInvalidGrantClearsTheSession() = runTest {
        val f = fixture(script = arrayOf(error(400, "invalid_grant", TokenEndpointResponseOrigin.AuthorizationServer)))

        assertFailsWith<OAuthSessionExpiredException> { f.refresh() }

        assertEquals(1, f.store.clearCalls)
    }

    @Test
    fun mediatorOriginSuccessStatusIsNotTrustedAsTokens() = runTest {
        val f = fixture(script = arrayOf(success(origin = TokenEndpointResponseOrigin.Mediator)))

        assertFailsWith<OAuthRefreshFailedException> { f.refresh() }

        assertEquals("rt_old", f.store.session?.refreshToken, "Tokens only come from the authorization server")
    }

    @Test
    fun mediatorOriginNonceIsNotRetried() = runTest {
        val f = fixture(script = arrayOf(error(400, "use_dpop_nonce", TokenEndpointResponseOrigin.Mediator, nonce = "n1")))

        assertFailsWith<OAuthRefreshFailedException> { f.refresh() }

        assertEquals(1, f.transport.requests.size)
    }

    @Test
    fun nonceChallengeFromTheAuthorizationServerIsRetriedThroughTheTransport() = runTest {
        val f = fixture(
            script = arrayOf(
                error(400, "use_dpop_nonce", TokenEndpointResponseOrigin.AuthorizationServer, nonce = "n1"),
                success(),
            ),
        )

        assertTrue(f.refresh())

        assertEquals(2, f.transport.requests.size)
        assertEquals("rt_new", f.store.session?.refreshToken)
        assertEquals("n1", f.store.session?.authServerNonce)
    }

    // --- What the request carries ---

    @Test
    fun refreshRequestCarriesKindEndpointFormAndProof() = runTest {
        val f = fixture(script = arrayOf(success()))

        f.refresh()

        val request = f.transport.requests.single()
        assertEquals(TokenEndpointRequestKind.Refresh, request.kind)
        assertEquals("https://auth.test/oauth/token", request.endpointUrl)
        assertEquals(
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to "rt_old",
                "client_id" to "https://app.test/meta.json",
            ),
            request.formParameters,
        )
        assertEquals(2, request.dpopProof.count { it == '.' }, "DPoP proof must be a compact JWT")
    }

    @Test
    fun issuerIsDerivedFromTheTokenEndpointForSessionsStoredWithoutOne() = runTest {
        val f = fixture(script = arrayOf(success()))

        f.refresh()

        assertEquals("https://auth.test", f.transport.requests.single().issuer)
    }

    @Test
    fun storedIssuerIsSentAsIs() = runTest {
        val f = fixture(session = { it.copy(issuer = "https://issuer.example") }, script = arrayOf(success()))

        f.refresh()

        assertEquals("https://issuer.example", f.transport.requests.single().issuer)
    }

    // --- Key binding ---

    @Test
    fun keyIdReportedAlongsideAPublicMethodIsNotStored() = runTest {
        val f = fixture(script = arrayOf(success(kid = "k1", method = "none")))

        f.refresh()

        assertNull(f.store.session?.clientAuthKid, "Nothing is bound while the client is public")
        assertEquals("none", f.store.session?.clientAuthMethod)
    }

    @Test
    fun keyIdReportedAlongsideAConfidentialMethodIsStored() = runTest {
        val f = fixture(script = arrayOf(success(kid = "k1", method = "private_key_jwt")))

        f.refresh()

        assertEquals("k1", f.store.session?.clientAuthKid)
        assertEquals("private_key_jwt", f.store.session?.clientAuthMethod)
    }

    @Test
    fun storedKeyIdIsSentAndNeverReplaced() = runTest {
        val f = fixture(
            session = { it.copy(clientAuthKid = "k1", clientAuthMethod = "private_key_jwt") },
            script = arrayOf(success(kid = "k2", method = "private_key_jwt")),
        )

        f.refresh()

        assertEquals("k1", f.transport.requests.single().clientAuthKid)
        assertEquals("k1", f.store.session?.clientAuthKid, "A bound session keeps its key for life")
    }

    @Test
    fun sessionWithoutAKeyIdSendsNone() = runTest {
        val f = fixture(script = arrayOf(success()))

        f.refresh()

        assertNull(f.transport.requests.single().clientAuthKid)
    }

    // --- Refresh outcome in doubt ---

    @Test
    fun inDoubtFlagIsOnDiskBeforeTheTransportIsCalled() = runTest {
        val f = fixture(script = arrayOf(success()))

        f.refresh()

        assertEquals(listOf<Boolean?>(true), f.transport.inDoubtOnDiskAtSend)
        assertFalse(f.transport.requests.single().outcomeInDoubt, "A first attempt has no earlier attempt to doubt")
    }

    @Test
    fun successClearsTheInDoubtFlag() = runTest {
        val f = fixture(script = arrayOf(success()))

        f.refresh()

        assertEquals(false, f.store.session?.refreshInDoubt)
    }

    @Test
    fun transportFailureLeavesTheFlagSetAndTheNextAttemptSaysSo() = runTest {
        val f = fixture(
            script = arrayOf(
                { throw IOException("response lost") },
                success(),
            ),
        )

        assertFailsWith<OAuthRefreshFailedException> { f.refresh() }
        assertEquals(true, f.store.session?.refreshInDoubt)
        assertEquals("rt_old", f.store.session?.refreshToken)

        assertTrue(f.refresh())
        assertTrue(f.transport.requests[1].outcomeInDoubt, "The retry must tell the transport the token may be spent")
        assertEquals(false, f.store.session?.refreshInDoubt)
    }

    @Test
    fun flagSurvivesAProcessRestart() = runTest {
        val first = fixture(script = arrayOf({ throw IOException("process died mid-refresh") }))
        assertFailsWith<OAuthRefreshFailedException> { first.refresh() }
        val onDisk = assertNotNull(first.store.session)
        assertTrue(onDisk.refreshInDoubt)

        // A new provider built from what is on disk, as after a cold start.
        val roundTripped = Json.decodeFromString(OAuthSession.serializer(), Json.encodeToString(OAuthSession.serializer(), onDisk))
        val store = InMemorySessionStore().apply { session = roundTripped }
        val transport = ScriptedTransport(store, true, mutableListOf(success()))
        val provider = DpopAuthProvider(roundTripped, first.signer, store, transport)

        provider.onUnauthorized(mapOf("WWW-Authenticate" to """DPoP error="invalid_token""""))

        assertTrue(transport.requests.single().outcomeInDoubt)
    }

    @Test
    fun relayedErrorClearsTheFlag() = runTest {
        val f = fixture(script = arrayOf(error(400, "invalid_request", TokenEndpointResponseOrigin.AuthorizationServer)))

        assertFailsWith<OAuthRefreshFailedException> { f.refresh() }

        assertEquals(false, f.store.session?.refreshInDoubt, "The server answered, so the token's state is known")
    }

    @Test
    fun mediatorErrorLeavesTheFlagSet() = runTest {
        val f = fixture(script = arrayOf(error(503, "mediator_upstream_timeout", TokenEndpointResponseOrigin.Mediator)))

        assertFailsWith<OAuthRefreshFailedException> { f.refresh() }

        assertEquals(true, f.store.session?.refreshInDoubt)
    }

    @Test
    fun nonceRetryIsNotMarkedInDoubt() = runTest {
        val f = fixture(
            script = arrayOf(
                error(400, "use_dpop_nonce", TokenEndpointResponseOrigin.AuthorizationServer, nonce = "n1"),
                success(),
            ),
        )

        f.refresh()

        assertFalse(f.transport.requests[1].outcomeInDoubt, "A nonce challenge proves the token was not consumed")
    }

    @Test
    fun transportThatDoesNotTrackOutcomesNeverSetsTheFlag() = runTest {
        val f = fixture(tracksRefreshOutcome = false, script = arrayOf({ throw IOException("lost") }))

        assertFailsWith<OAuthRefreshFailedException> { f.refresh() }

        assertEquals(listOf<Boolean?>(false), f.transport.inDoubtOnDiskAtSend)
        assertEquals(false, f.store.session?.refreshInDoubt)
    }

    @Test
    fun requestNotSentIsReportedAsNotConsumed() = runTest {
        val f = fixture(script = arrayOf({ throw TokenRequestNotSentException("backend unreachable") }))

        val failure = assertFailsWith<OAuthRefreshFailedException> { f.refresh() }

        assertTrue(failure.message.orEmpty().contains("refresh token not consumed"))
    }

    @Test
    fun requestNotSentTakesBackTheMarkItWrote() = runTest {
        val f = fixture(
            script = arrayOf(
                { throw TokenRequestNotSentException("backend unreachable") },
                success(),
            ),
        )

        assertFailsWith<OAuthRefreshFailedException> { f.refresh() }

        assertEquals(listOf<Boolean?>(true), f.transport.inDoubtOnDiskAtSend, "The mark is still written before sending")
        assertEquals(false, f.store.session?.refreshInDoubt, "A request that never left leaves no doubt on disk either")

        assertTrue(f.refresh())
        assertFalse(f.transport.requests[1].outcomeInDoubt)
    }

    @Test
    fun requestNotSentAfterAnUnknownOutcomeKeepsTheMark() = runTest {
        val f = fixture(
            script = arrayOf(
                { throw IOException("response lost") },
                { throw TokenRequestNotSentException("backend unreachable") },
                success(),
            ),
        )

        assertFailsWith<OAuthRefreshFailedException> { f.refresh() }
        assertFailsWith<OAuthRefreshFailedException> { f.refresh() }

        assertEquals(true, f.store.session?.refreshInDoubt, "The first attempt's outcome is still unknown")
        assertTrue(f.refresh())
        assertTrue(f.transport.requests[2].outcomeInDoubt)
    }
}
