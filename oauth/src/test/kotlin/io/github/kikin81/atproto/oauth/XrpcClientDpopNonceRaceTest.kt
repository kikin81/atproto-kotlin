package io.github.kikin81.atproto.oauth

import io.github.kikin81.atproto.runtime.XrpcClient
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
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End to end through [XrpcClient]'s real single retry: requests sent together
 * with a stale DPoP nonce must all succeed once the PDS rotates it, without
 * spending the refresh token. Regression for the 9.15.0 nonce race (#220).
 */
class XrpcClientDpopNonceRaceTest {

    @Test
    fun concurrentRequestsWithAStaleNonceAllSucceedWithoutRefreshing() = runTest {
        val requestCount = 4
        val serverNonce = "fresh-nonce"
        var staleArrivals = 0
        // Hold every stale response until all requests have arrived, so each
        // one is guaranteed to have been signed with the stale nonce.
        val allSentStale = CompletableDeferred<Unit>()
        var pdsCalls = 0

        val pds = MockEngine { request ->
            pdsCalls++
            if (proofNonce(request.headers["DPoP"]) != serverNonce) {
                if (++staleArrivals == requestCount) allSentStale.complete(Unit)
                allSentStale.await()
                respond(
                    ByteReadChannel("""{"error":"use_dpop_nonce","message":"DPoP nonce mismatch"}"""),
                    HttpStatusCode.Unauthorized,
                    headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        "DPoP-Nonce" to listOf(serverNonce),
                        HttpHeaders.WWWAuthenticate to listOf("""DPoP error="use_dpop_nonce""""),
                    ),
                )
            } else {
                respond(ByteReadChannel("""{"ok":true}"""), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }

        var tokenCalls = 0
        val tokenEndpoint = HttpClient(
            MockEngine { _ ->
                tokenCalls++
                respond(
                    ByteReadChannel("""{"access_token":"at_new","refresh_token":"rt_new","token_type":"DPoP"}"""),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val session = OAuthSession(
            accessToken = "opaque-access-token",
            refreshToken = "rt_old",
            did = "did:plc:testuser",
            handle = "alice.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            pdsNonce = "stale-nonce",
        )
        val store = object : OAuthSessionStore {
            var saved: OAuthSession? = session
            override suspend fun load(): OAuthSession? = saved
            override suspend fun save(session: OAuthSession) {
                saved = session
            }
            override suspend fun clear() {
                saved = null
            }
        }
        val provider = DpopAuthProvider(session, signer, store, tokenEndpoint)
        val client = XrpcClient(baseUrl = "https://pds.test", httpClient = HttpClient(pds), authProvider = provider)

        val results = List(requestCount) {
            async {
                runCatching {
                    client.query(
                        nsid = "app.bsky.actor.getPreferences",
                        params = Unit,
                        paramsSerializer = Unit.serializer(),
                        responseSerializer = JsonObject.serializer(),
                    )
                }
            }
        }.awaitAll()

        assertEquals(
            List(requestCount) { true },
            results.map { it.isSuccess },
            "every request must succeed after one retry, failures: ${results.mapNotNull { it.exceptionOrNull()?.message }}",
        )
        assertEquals(0, tokenCalls, "nonce recovery must never spend the refresh token")
        assertEquals(requestCount * 2, pdsCalls, "each request is sent once stale and retried once")
        assertEquals(serverNonce, store.saved?.pdsNonce)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun proofNonce(proof: String?): String? {
        val payload = proof?.split('.')?.getOrNull(1) ?: return null
        val decoded = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(payload).decodeToString()
        return Json.parseToJsonElement(decoded).jsonObject["nonce"]?.jsonPrimitive?.content
    }
}
