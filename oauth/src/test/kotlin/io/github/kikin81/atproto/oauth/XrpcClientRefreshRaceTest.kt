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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End to end through [XrpcClient]'s real single retry: a request that leaves
 * with the old access token while a refresh is in flight, and is answered
 * `401 invalid_token` after that refresh has finished, must be re-sent with
 * the new token. It must not rotate the single-use refresh token again.
 *
 * Seen on a device: two successful rotations 0.4 s apart, the second one
 * caused by a `getTimeline` that had been sent during the first.
 */
class XrpcClientRefreshRaceTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun aRequestSentDuringARefreshAndRejectedAfterItSucceedsWithoutASecondRotation() = runTest {
        // The server accepts only the newest access token it has issued.
        // "opaque-at-old" has already expired there; the client cannot tell,
        // because the token is opaque.
        val acceptedToken = AtomicReference("issued-before-this-test")
        val tokensSeenByThePds = ConcurrentLinkedQueue<String>()
        val rejections = AtomicInteger()

        val refreshInFlight = CompletableDeferred<Unit>()
        val secondRequestSentWithTheOldToken = CompletableDeferred<Unit>()
        val rotationApplied = CompletableDeferred<Unit>()

        val pds = MockEngine { request ->
            val token = request.headers[HttpHeaders.Authorization].orEmpty().removePrefix("DPoP ")
            tokensSeenByThePds += token
            if (token == acceptedToken.get()) {
                // A request can only carry the new token once the provider has
                // applied the rotation, which is when the late 401 may arrive.
                rotationApplied.complete(Unit)
                respond(ByteReadChannel("""{"ok":true}"""), HttpStatusCode.OK, jsonHeaders)
            } else {
                if (rejections.incrementAndGet() == 2) {
                    secondRequestSentWithTheOldToken.complete(Unit)
                    rotationApplied.await()
                }
                respond(
                    ByteReadChannel("""{"error":"InvalidToken","message":"Invalid token"}"""),
                    HttpStatusCode.Unauthorized,
                    headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        HttpHeaders.WWWAuthenticate to listOf("""DPoP error="invalid_token", error_description="Invalid token""""),
                    ),
                )
            }
        }

        val tokenCalls = AtomicInteger()
        val tokenEndpoint = HttpClient(
            MockEngine { _ ->
                val call = tokenCalls.incrementAndGet()
                refreshInFlight.complete(Unit)
                // Answer only once the second request is on the wire with the
                // old token, so it was provably sent during this refresh.
                secondRequestSentWithTheOldToken.await()
                acceptedToken.set("at_$call")
                respond(
                    ByteReadChannel("""{"access_token":"at_$call","refresh_token":"rt_$call","token_type":"DPoP"}"""),
                    HttpStatusCode.OK,
                    jsonHeaders,
                )
            },
        )

        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val session = OAuthSession(
            accessToken = "opaque-at-old",
            refreshToken = "rt_old",
            did = "did:plc:refresh-race",
            handle = "alice.test",
            pdsUrl = "https://pds.test",
            tokenEndpoint = "https://auth.test/token",
            clientId = "https://app.test/meta.json",
            dpopPrivateKey = exported.privateKeyEncoded,
            dpopPublicKey = exported.publicKeyEncoded,
            pdsNonce = "nonce",
        )
        val store = object : OAuthSessionStore {
            @Volatile var saved: OAuthSession? = session
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

        suspend fun request(nsid: String): JsonObject = client.query(
            nsid = nsid,
            params = Unit,
            paramsSerializer = Unit.serializer(),
            responseSerializer = JsonObject.serializer(),
        )

        // Any request that still fails throws here and fails the test.
        val first = async { request("app.bsky.actor.getPreferences") }
        refreshInFlight.await()
        val second = async { request("app.bsky.feed.getTimeline") }
        first.await()
        second.await()

        assertEquals(1, tokenCalls.get(), "one expired token must cost one rotation, however many requests carried it")
        assertEquals("rt_1", store.saved?.refreshToken)
        assertEquals(
            listOf("at_1", "at_1", "opaque-at-old", "opaque-at-old"),
            tokensSeenByThePds.sorted(),
            "each request is sent once with the old token and retried once with the rotated one",
        )
    }
}
