package io.github.kikin81.atproto.oauth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DpopAuthProviderTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    // A fresh channel per response: ByteReadChannel is consumed on read, so a
    // shared instance would surface as an empty body on the second call.
    private fun invalidGrantBody() = ByteReadChannel("""{"error":"invalid_grant","error_description":"token has been revoked"}""")

    private class InMemorySessionStore : OAuthSessionStore {
        var session: OAuthSession? = null
        var clearCalls = 0
        var saveCalls = 0
        override suspend fun load(): OAuthSession? = session
        override suspend fun save(session: OAuthSession) {
            saveCalls++
            this.session = session
        }
        override suspend fun clear() {
            clearCalls++
            session = null
        }
    }

    @Test
    fun onUnauthorizedRecoversNonceAndRefreshesExpiredTokenInOneCall() = runTest {
        // Regression for kikin81/atproto-kotlin#33: a single 401 carrying both
        // a fresh DPoP-Nonce and (implied by the about-to-fail-anyway expired
        // access token) `invalid_token` must produce nonce update AND refresh
        // in one onUnauthorized call. Otherwise XrpcClient's single retry
        // sends new-nonce + still-expired-token and surfaces XrpcError.Unknown.
        var tokenCalls = 0
        val refreshClient = HttpClient(
            MockEngine { _ ->
                tokenCalls++
                respond(
                    ByteReadChannel(
                        """{"access_token":"at_new","refresh_token":"rt_new","token_type":"DPoP"}""",
                    ),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val expiredToken = makeJwtWithExp((System.currentTimeMillis() / 1000) - 3600)
        val store = InMemorySessionStore()
        val session = OAuthSession(
            accessToken = expiredToken,
            refreshToken = "rt_old",
            did = "did:plc:testuser",
            handle = "alice.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            pdsNonce = "old-nonce",
        )
        store.session = session
        val provider = DpopAuthProvider(session, signer, store, refreshClient)

        val recovered = provider.onUnauthorized(
            mapOf(
                "DPoP-Nonce" to "fresh-nonce",
                "WWW-Authenticate" to """DPoP error="use_dpop_nonce"""",
            ),
        )

        assertTrue(recovered, "onUnauthorized must report a successful recovery")
        assertEquals(1, tokenCalls, "Token endpoint must be hit exactly once for the in-band refresh")
        val saved = store.session
        assertNotNull(saved)
        assertEquals("at_new", saved.accessToken, "Refreshed access token must be persisted")
        assertEquals("fresh-nonce", saved.pdsNonce, "New PDS nonce must be persisted")
    }

    @Test
    fun onUnauthorizedSkipsRefreshForOpaqueAccessTokenWhenOnlyNonceChanged() = runTest {
        // Preservation: if the access token isn't a parseable JWT (opaque
        // token, or a server that doesn't bound exp into the JWT), we cannot
        // determine expiry — must NOT speculatively refresh just because the
        // nonce changed. Real expiry will surface on the next 401.
        var tokenCalls = 0
        val refreshClient = HttpClient(
            MockEngine { _ ->
                tokenCalls++
                respond(ByteReadChannel(""), HttpStatusCode.OK)
            },
        )

        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val store = InMemorySessionStore()
        val session = OAuthSession(
            accessToken = "opaque-not-a-jwt",
            refreshToken = "rt",
            did = "did:plc:x",
            handle = "x.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            pdsNonce = "old-nonce",
        )
        store.session = session
        val provider = DpopAuthProvider(session, signer, store, refreshClient)

        val recovered = provider.onUnauthorized(mapOf("DPoP-Nonce" to "fresh-nonce"))

        assertTrue(recovered)
        assertEquals(0, tokenCalls, "Refresh must not run when expiry is undetermined and nonce is the only signal")
        assertEquals("fresh-nonce", store.session?.pdsNonce)
    }

    @Test
    fun onUnauthorizedRefreshesWhenNonceUnchanged() = runTest {
        // Preservation: existing fall-through path. Same-nonce 401 (no
        // use_dpop_nonce signal) goes straight to refresh.
        var tokenCalls = 0
        val refreshClient = HttpClient(
            MockEngine { _ ->
                tokenCalls++
                respond(
                    ByteReadChannel("""{"access_token":"at_new","token_type":"DPoP"}"""),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val store = InMemorySessionStore()
        val session = OAuthSession(
            accessToken = "opaque",
            refreshToken = "rt",
            did = "did:plc:x",
            handle = "x.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            pdsNonce = "same-nonce",
        )
        store.session = session
        val provider = DpopAuthProvider(session, signer, store, refreshClient)

        val recovered = provider.onUnauthorized(mapOf("DPoP-Nonce" to "same-nonce"))

        assertTrue(recovered)
        assertEquals(1, tokenCalls)
    }

    @Test
    fun onUnauthorizedPersistsNewNonceEvenWhenRefreshFails() = runTest {
        // If the access token is expired AND the server rotates the nonce in
        // the same 401, refreshTokens() may still throw on a network failure.
        // The new nonce must already be persisted so the next cold start
        // doesn't have to re-discover it (Copilot review on PR #34, comment 2).
        // A network-layer failure is TRANSIENT (no signal, not a revoked
        // token): it must throw the retryable OAuthRefreshFailedException and
        // leave the session intact — never OAuthSessionExpiredException, which
        // would clear the session and sign the user out.
        val refreshClient = HttpClient(
            MockEngine { _ ->
                throw java.io.IOException("simulated network failure")
            },
        )

        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val expiredToken = makeJwtWithExp((System.currentTimeMillis() / 1000) - 3600)
        val store = InMemorySessionStore()
        val session = OAuthSession(
            accessToken = expiredToken,
            refreshToken = "rt",
            did = "did:plc:x",
            handle = "x.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            pdsNonce = "old-nonce",
        )
        store.session = session
        val provider = DpopAuthProvider(session, signer, store, refreshClient)

        assertFailsWith<OAuthRefreshFailedException> {
            provider.onUnauthorized(mapOf("DPoP-Nonce" to "fresh-nonce"))
        }

        // Refresh threw, but the session survives and the rotated nonce is persisted.
        assertNotNull(store.session, "a network failure must NOT clear the session")
        assertEquals("fresh-nonce", store.session?.pdsNonce)
    }

    @Test
    fun refreshOnTransient5xxKeepsSessionAndThrowsRetryable() = runTest {
        // The core bug: a transient 5xx from the token endpoint (flaky signal,
        // gateway/captive-portal upstream) must NOT be treated as a revoked
        // refresh token. Leave the session intact and surface a retryable error.
        val refreshClient = HttpClient(
            MockEngine { _ -> respond(ByteReadChannel("upstream unavailable"), HttpStatusCode.ServiceUnavailable) },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        assertFailsWith<OAuthRefreshFailedException> { provider.onUnauthorized(emptyMap()) }
        assertNotNull(store.session, "a transient 5xx must NOT clear the session")
    }

    @Test
    fun refreshOn400WithoutInvalidGrantKeepsSession() = runTest {
        // A 400 whose OAuth error is NOT invalid_grant (e.g. a proxy/portal
        // error, or invalid_request) is not proof the refresh token is revoked
        // — keep the session and surface a retryable error.
        val refreshClient = HttpClient(
            MockEngine { _ -> respond(ByteReadChannel("""{"error":"invalid_request"}"""), HttpStatusCode.BadRequest, jsonHeaders) },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        assertFailsWith<OAuthRefreshFailedException> { provider.onUnauthorized(emptyMap()) }
        assertNotNull(store.session, "a non-invalid_grant 400 must NOT clear the session")
    }

    @Test
    fun refreshOnInvalidGrantClearsSessionAndThrowsExpired() = runTest {
        // The genuine revoked/expired refresh token case (RFC 6749 §5.2): the
        // token endpoint returns 400 with error=invalid_grant. THIS is the only
        // case that should clear the session and sign the user out.
        val refreshClient = HttpClient(
            MockEngine { _ -> respond(ByteReadChannel("""{"error":"invalid_grant"}"""), HttpStatusCode.BadRequest, jsonHeaders) },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        assertFailsWith<OAuthSessionExpiredException> { provider.onUnauthorized(emptyMap()) }
        assertNull(store.session, "a revoked refresh token (invalid_grant) must clear the session")
    }

    @Test
    fun refreshAfterNonceRetryTransientKeepsSession() = runTest {
        // Same guarantee on the nonce-retry path: if the first refresh gets a
        // rotated auth-server nonce (use_dpop_nonce) and the retried refresh
        // then hits a transient 5xx, keep the session and surface a retryable
        // error — don't sign the user out.
        var calls = 0
        val refreshClient = HttpClient(
            MockEngine { _ ->
                calls++
                if (calls == 1) {
                    respond(
                        ByteReadChannel("""{"error":"use_dpop_nonce"}"""),
                        HttpStatusCode.BadRequest,
                        headersOf("DPoP-Nonce", "srv-nonce"),
                    )
                } else {
                    respond(ByteReadChannel("upstream unavailable"), HttpStatusCode.ServiceUnavailable)
                }
            },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        assertFailsWith<OAuthRefreshFailedException> { provider.onUnauthorized(emptyMap()) }
        assertEquals(2, calls, "the nonce-retry must have been attempted")
        assertNotNull(store.session, "a transient 5xx on the nonce retry must NOT clear the session")
    }

    @Test
    fun refreshNonceRetryNetworkFailureKeepsSession() = runTest {
        // The nonce-retry leg must also treat a network failure as transient —
        // wrap submitForm so it surfaces as retryable, not a raw escape that a
        // caller might mishandle, and never clear the session.
        var calls = 0
        val refreshClient = HttpClient(
            MockEngine { _ ->
                calls++
                if (calls == 1) {
                    respond(
                        ByteReadChannel("""{"error":"use_dpop_nonce"}"""),
                        HttpStatusCode.BadRequest,
                        headersOf("DPoP-Nonce", "srv-nonce"),
                    )
                } else {
                    throw java.io.IOException("simulated network failure on nonce retry")
                }
            },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        assertFailsWith<OAuthRefreshFailedException> { provider.onUnauthorized(emptyMap()) }
        assertEquals(2, calls, "the nonce-retry must have been attempted")
        assertNotNull(store.session, "a network failure on the nonce retry must NOT clear the session")
    }

    @Test
    fun refreshDoesNotWrapCancellation() = runTest {
        // A cancelled coroutine must propagate CancellationException untouched —
        // wrapping it as OAuthRefreshFailedException would swallow cancellation
        // and break structured concurrency.
        val refreshClient = HttpClient(
            MockEngine { _ -> throw CancellationException("refresh cancelled") },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        assertFailsWith<CancellationException> { provider.onUnauthorized(emptyMap()) }
        assertNotNull(store.session, "cancellation must not clear the session")
    }

    @Test
    fun refreshPersistsTheRotatedRefreshToken() = runTest {
        // AT Proto refresh tokens are single-use and rotate on every refresh.
        // The server-issued replacement MUST be what lands in the store — a
        // stale persisted refresh token is a future invalid_grant (session
        // revocation via reuse detection) waiting for the next cold start.
        val refreshClient = HttpClient(
            MockEngine { _ ->
                respond(
                    ByteReadChannel(
                        """{"access_token":"at_new","refresh_token":"rt_rotated","token_type":"DPoP"}""",
                    ),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        assertTrue(provider.onUnauthorized(emptyMap()))

        assertEquals("rt_rotated", store.session?.refreshToken, "rotated refresh token must be persisted")
        assertEquals("at_new", store.session?.accessToken)
    }

    @Test
    fun concurrentUnauthorizedCallsRotateExactlyOnce() = runTest {
        // Refresh tokens are single-use: every redundant rotation widens the
        // window where the persisted token is stale. N callers racing into
        // onUnauthorized must produce exactly ONE token-endpoint POST; the
        // waiters queued behind the in-flight refresh adopt its result.
        var tokenCalls = 0
        val gate = CompletableDeferred<Unit>()
        val refreshClient = HttpClient(
            MockEngine { _ ->
                tokenCalls++
                gate.await()
                respond(
                    ByteReadChannel(
                        """{"access_token":"at_new","refresh_token":"rt_new","token_type":"DPoP"}""",
                    ),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        val results = (1..3).map { async { provider.onUnauthorized(emptyMap()) } }
        // Let all three callers run up to the mutex / in-flight request...
        testScheduler.advanceUntilIdle()
        // ...then release the single in-flight refresh response.
        gate.complete(Unit)
        val recovered = results.awaitAll()

        assertEquals(listOf(true, true, true), recovered, "every caller must report recovery")
        assertEquals(1, tokenCalls, "concurrent 401s must be coalesced into a single rotation")
        assertEquals("rt_new", store.session?.refreshToken)
    }

    @Test
    fun secondProviderSharingTheStoreAdoptsTheRotatedSessionInsteadOfReplayingTheConsumedToken() = runTest {
        // Two provider instances built from the same persisted session (e.g.
        // a client cache invalidation window): after A rotates, B still holds
        // the CONSUMED refresh token in memory. B must adopt the stored
        // rotated session instead of replaying the consumed token — replaying
        // trips the auth server's reuse detection and revokes the whole
        // session (the "user suddenly logged out" bug).
        var tokenCalls = 0
        val refreshClient = HttpClient(
            MockEngine { request ->
                tokenCalls++
                val form = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .decodeToString()
                if (form.contains("refresh_token=rt_consumed") && tokenCalls > 1) {
                    // Simulate server-side single-use reuse detection.
                    respond(
                        ByteReadChannel("""{"error":"invalid_grant"}"""),
                        HttpStatusCode.BadRequest,
                        jsonHeaders,
                    )
                } else {
                    respond(
                        ByteReadChannel(
                            """{"access_token":"at_new","refresh_token":"rt_new","token_type":"DPoP"}""",
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
            },
        )

        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val store = InMemorySessionStore()
        val session = OAuthSession(
            accessToken = makeJwtWithExp((System.currentTimeMillis() / 1000) - 3600),
            refreshToken = "rt_consumed",
            did = "did:plc:x",
            handle = "x.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            pdsNonce = "old-nonce",
        )
        store.session = session
        val providerA = DpopAuthProvider(session, signer, store, refreshClient)
        val providerB = DpopAuthProvider(session, signer, store, refreshClient)

        assertTrue(providerA.onUnauthorized(emptyMap()), "A performs the real rotation")
        assertTrue(providerB.onUnauthorized(emptyMap()), "B must recover by adopting the stored session")

        assertEquals(1, tokenCalls, "B must NOT replay the consumed refresh token")
        assertNotNull(store.session, "the session must survive")
        assertEquals("rt_new", store.session?.refreshToken)
        assertTrue(
            providerB.authHeaders("GET", "https://pds.test/xrpc/x")["Authorization"]!!.contains("at_new"),
            "B must serve the adopted rotated access token",
        )
    }

    @Test
    fun noncePersistenceMustNotClobberASessionRotatedByAnotherInstance() = runTest {
        // Instance B holds a stale (already-consumed) refresh token while the
        // shared store already contains A's rotated session. If B's 401
        // carries a fresh DPoP-Nonce, the eager nonce persistence must NOT
        // write B's stale session over the rotated one — that would erase the
        // only valid refresh token and set up the exact replay → reuse
        // detection → invalid_grant logout this PR eliminates. B must adopt
        // the stored rotated tokens and save the new nonce on top of them.
        var tokenCalls = 0
        val refreshClient = HttpClient(
            MockEngine { request ->
                tokenCalls++
                val form = (request.body as io.ktor.http.content.OutgoingContent.ByteArrayContent)
                    .bytes()
                    .decodeToString()
                if (form.contains("refresh_token=rt_consumed")) {
                    // Server-side single-use reuse detection.
                    respond(
                        ByteReadChannel("""{"error":"invalid_grant"}"""),
                        HttpStatusCode.BadRequest,
                        jsonHeaders,
                    )
                } else {
                    respond(
                        ByteReadChannel(
                            """{"access_token":"at_new2","refresh_token":"rt_new2","token_type":"DPoP"}""",
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
            },
        )

        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val store = InMemorySessionStore()
        val staleSession = OAuthSession(
            accessToken = makeJwtWithExp((System.currentTimeMillis() / 1000) - 3600),
            refreshToken = "rt_consumed",
            did = "did:plc:x",
            handle = "x.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            pdsNonce = "old-nonce",
        )
        // Another instance already rotated: the store holds the fresh session.
        store.session = staleSession.copy(accessToken = "at_rotated", refreshToken = "rt_rotated")
        val providerB = DpopAuthProvider(staleSession, signer, store, refreshClient)

        val recovered = providerB.onUnauthorized(mapOf("DPoP-Nonce" to "fresh-nonce"))

        assertTrue(recovered, "B must recover via adoption")
        assertEquals(0, tokenCalls, "B must neither replay the consumed token nor rotate redundantly")
        assertEquals(
            "rt_rotated",
            store.session?.refreshToken,
            "the rotated refresh token must survive B's nonce persistence",
        )
        assertEquals("fresh-nonce", store.session?.pdsNonce, "the fresh nonce must be saved on top of the rotated tokens")
        assertTrue(
            providerB.authHeaders("GET", "https://pds.test/xrpc/x")["Authorization"]!!.contains("at_rotated"),
            "B must serve the adopted rotated access token",
        )
    }

    @Test
    fun persistFailureAfterRotationStillRecoversAndSignalsOnPersistFailure() = runTest {
        // By the time the token endpoint returns 200 the server has ALREADY
        // consumed the old refresh token. If persisting the rotated session
        // fails, the in-memory session is still the only valid one — the
        // provider must keep serving it (return true) rather than throw,
        // and surface the persistence gap through onPersistFailure so the
        // app can report it. Throwing here would fail a request that holds
        // a perfectly good token and invite a consumed-token replay.
        val refreshClient = HttpClient(
            MockEngine { _ ->
                respond(
                    ByteReadChannel(
                        """{"access_token":"at_new","refresh_token":"rt_new","token_type":"DPoP"}""",
                    ),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )
        val store = object : OAuthSessionStore {
            var session: OAuthSession? = null
            var saveAttempts = 0
            override suspend fun load(): OAuthSession? = session
            override suspend fun save(session: OAuthSession) {
                saveAttempts++
                throw java.io.IOException("simulated persist failure #$saveAttempts")
            }
            override suspend fun clear() {
                session = null
            }
        }
        val persistFailures = mutableListOf<Throwable>()
        val (provider, _) = fixtureWithExpiredToken(refreshClient, store, persistFailures::add)

        assertTrue(provider.onUnauthorized(emptyMap()), "rotation succeeded server-side — must recover")

        assertEquals(2, store.saveAttempts, "save must be retried exactly once")
        assertEquals(1, persistFailures.size, "the persist failure must be signalled exactly once")
        assertTrue(
            provider.authHeaders("GET", "https://pds.test/xrpc/x")["Authorization"]!!.contains("at_new"),
            "the in-memory rotated token must be served despite the persist failure",
        )
    }

    @Test
    fun persistRetrySucceedsSilently() = runTest {
        val refreshClient = HttpClient(
            MockEngine { _ ->
                respond(
                    ByteReadChannel(
                        """{"access_token":"at_new","refresh_token":"rt_new","token_type":"DPoP"}""",
                    ),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )
        val store = object : OAuthSessionStore {
            var session: OAuthSession? = null
            var saveAttempts = 0
            override suspend fun load(): OAuthSession? = session
            override suspend fun save(session: OAuthSession) {
                saveAttempts++
                if (saveAttempts == 1) throw java.io.IOException("transient persist hiccup")
                this.session = session
            }
            override suspend fun clear() {
                session = null
            }
        }
        val persistFailures = mutableListOf<Throwable>()
        val (provider, _) = fixtureWithExpiredToken(refreshClient, store, persistFailures::add)

        assertTrue(provider.onUnauthorized(emptyMap()))

        assertEquals("rt_new", store.session?.refreshToken, "the retry must persist the rotated token")
        assertTrue(persistFailures.isEmpty(), "a recovered persist must not be signalled")
    }

    @Test
    fun preSendNetworkFailureIsMarkedAsTokenNotConsumed() = runTest {
        // A DNS / connect-phase failure means the refresh request never
        // reached the server, so the refresh token was NOT consumed — callers
        // (and their telemetry) can safely retry with the same token. The
        // thrown message must carry that distinction; a post-send failure
        // (response lost) stays ambiguous.
        val refreshClient = HttpClient(
            MockEngine { _ -> throw java.net.UnknownHostException("auth.test") },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        val failure = assertFailsWith<OAuthRefreshFailedException> { provider.onUnauthorized(emptyMap()) }

        assertTrue(
            failure.message!!.contains("not consumed"),
            "pre-send failures must state the refresh token was not consumed, got: ${failure.message}",
        )
        assertNotNull(store.session, "a pre-send failure must not clear the session")
    }

    @Test
    fun coalescedWaitersDoNotReplayADeadTokenAfterTerminalInvalidGrant() = runTest {
        // Regression for #164 §1. failRefresh clears the store but deliberately
        // leaves the in-memory session intact, so neither dedup check can see
        // that a prior waiter already failed terminally: `rotatedWhileWaiting`
        // reads unchanged tokens and adoptStoredSessionIfRotated reads an empty
        // store and reports "no rotation". The waiter then re-POSTs the dead
        // refresh token — a replay, which is exactly what AT Proto reuse
        // detection revokes on — and duplicate-clears.
        var tokenCalls = 0
        val refreshClient = HttpClient(
            MockEngine { _ ->
                tokenCalls++
                respond(invalidGrantBody(), HttpStatusCode.BadRequest, jsonHeaders)
            },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        listOf(
            async { runCatching { provider.onUnauthorized(emptyMap()) } },
            async { runCatching { provider.onUnauthorized(emptyMap()) } },
        ).awaitAll()

        assertEquals(1, tokenCalls, "the dead refresh token must be POSTed once for the whole coalesced set")
        assertEquals(1, store.clearCalls, "the session must be cleared exactly once")
    }

    @Test
    fun aNonceRotationAfterTerminalClearDoesNotResurrectTheClearedSession() = runTest {
        // Regression for #164 §4. persistNonces() saved unconditionally, so the
        // first 401 carrying a rotated DPoP-Nonce after a terminal clear wrote
        // the dead session back to the store. Once the token is dead EVERY
        // request 401s and the PDS rotates nonces routinely, so this fired on
        // the very next request — re-arming the logout loop.
        val refreshClient = HttpClient(
            MockEngine { _ -> respond(invalidGrantBody(), HttpStatusCode.BadRequest, jsonHeaders) },
        )
        // The access token here is OPAQUE, not an expired JWT, on purpose: it
        // makes isAccessTokenExpired() return false so the second
        // onUnauthorized returns right after persistNonces() instead of running
        // another refresh. With an expired JWT that follow-up refresh clears the
        // store a second time and masks the resurrect — the assertion would then
        // pass against unfixed code and prove nothing.
        val (provider, store) = fixtureWithOpaqueToken(refreshClient)

        assertFailsWith<OAuthSessionExpiredException> { provider.onUnauthorized(emptyMap()) }
        assertNull(store.session, "precondition: the terminal invalid_grant must have cleared the store")
        val savesBefore = store.saveCalls

        provider.onUnauthorized(mapOf("DPoP-Nonce" to "rotated-nonce"))

        assertEquals(savesBefore, store.saveCalls, "a cleared session must never be re-saved")
        assertNull(store.session, "a cleared session must stay cleared — never re-save a dead refresh token")
    }

    /**
     * Companion to [fixtureWithExpiredToken] whose access token is opaque, so
     * `isAccessTokenExpired()` cannot fire and `onUnauthorized` exercises only
     * the nonce branches plus the explicit fall-through refresh.
     */
    private fun fixtureWithOpaqueToken(refreshClient: HttpClient): Pair<DpopAuthProvider, InMemorySessionStore> {
        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val store = InMemorySessionStore()
        val session = OAuthSession(
            accessToken = "opaque-not-a-jwt",
            refreshToken = "rt_dead",
            did = "did:plc:x",
            handle = "x.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            pdsNonce = "old-nonce",
        )
        store.session = session
        return DpopAuthProvider(session, signer, store, refreshClient) to store
    }

    @Test
    fun invalidGrantCarryingANonceHeaderIsTerminalRatherThanRetried() = runTest {
        // Regression for #164 §5. The nonce-retry gate keyed only on
        // (401|400) + a DPoP-Nonce header, never on the body, so a rejection
        // that also rotated a nonce was misread as `use_dpop_nonce` and the
        // just-rejected refresh token was replayed. Production stacks show the
        // clear arriving via refreshTokensWithNonce, i.e. through this branch.
        var tokenCalls = 0
        val refreshClient = HttpClient(
            MockEngine { _ ->
                tokenCalls++
                respond(
                    invalidGrantBody(),
                    HttpStatusCode.BadRequest,
                    headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        "DPoP-Nonce" to listOf("rotated-nonce-$tokenCalls"),
                    ),
                )
            },
        )
        val (provider, store) = fixtureWithExpiredToken(refreshClient)

        assertFailsWith<OAuthSessionExpiredException> { provider.onUnauthorized(emptyMap()) }

        assertEquals(1, tokenCalls, "an explicit invalid_grant is terminal even when a nonce was also rotated")
        assertEquals(1, store.clearCalls, "the session must be cleared exactly once")
    }

    /**
     * Builds a [DpopAuthProvider] whose access token is already expired, so
     * `onUnauthorized` routes straight to the token-refresh path. Returns the
     * provider + its store for post-assertions on session survival.
     */
    private fun fixtureWithExpiredToken(refreshClient: HttpClient): Pair<DpopAuthProvider, InMemorySessionStore> {
        val store = InMemorySessionStore()
        val (provider, _) = fixtureWithExpiredToken(refreshClient, store) {}
        return provider to store
    }

    /**
     * Variant taking a custom [store] (e.g. one whose `save` fails) and an
     * [onPersistFailure] hook, for the rotation-persistence hardening tests.
     */
    private fun fixtureWithExpiredToken(
        refreshClient: HttpClient,
        store: OAuthSessionStore,
        onPersistFailure: (Throwable) -> Unit,
    ): Pair<DpopAuthProvider, OAuthSessionStore> {
        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val session = OAuthSession(
            accessToken = makeJwtWithExp((System.currentTimeMillis() / 1000) - 3600),
            refreshToken = "rt",
            did = "did:plc:x",
            handle = "x.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            pdsNonce = "old-nonce",
        )
        (store as? InMemorySessionStore)?.session = session
        return DpopAuthProvider(session, signer, store, refreshClient, onPersistFailure = onPersistFailure) to store
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun makeJwtWithExp(exp: Long): String {
        val header = Base64.UrlSafe.encode("""{"alg":"ES256","typ":"at+jwt"}""".toByteArray()).trimEnd('=')
        val payload = Base64.UrlSafe.encode("""{"exp":$exp}""".toByteArray()).trimEnd('=')
        return "$header.$payload.fakesignature"
    }
}
