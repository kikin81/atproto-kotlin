package io.github.kikin81.atproto.runtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class XrpcErrorListenerTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private class RecordingListener : XrpcErrorListener {
        val calls = mutableListOf<Pair<String, XrpcError>>()
        override fun onError(nsid: String, error: XrpcError) {
            calls += nsid to error
        }
    }

    /** Recovers a 401 once, as a DPoP nonce retry would. */
    private object RetryOnceAuth : AuthProvider {
        override suspend fun authHeaders(method: String, url: String): Map<String, String> = emptyMap()
        override suspend fun onUnauthorized(responseHeaders: Map<String, String>): Boolean = true
    }

    private fun client(
        listener: XrpcErrorListener?,
        auth: AuthProvider = NoAuth,
        statuses: List<Pair<HttpStatusCode, String>>,
    ): Pair<XrpcClient, MockEngine> {
        var i = 0
        val engine = MockEngine { _ ->
            val (status, body) = statuses[minOf(i++, statuses.lastIndex)]
            respond(ByteReadChannel(body), status, jsonHeaders)
        }
        return XrpcClient("https://pds.test", HttpClient(engine), authProvider = auth, errorListener = listener) to engine
    }

    private suspend fun XrpcClient.getPreferences(): JsonObject = query(
        nsid = "app.bsky.actor.getPreferences",
        params = Unit,
        paramsSerializer = Unit.serializer(),
        responseSerializer = JsonObject.serializer(),
    )

    private val notFound = HttpStatusCode.NotFound to """{"error":"NotFound","message":"nope"}"""
    private val unauthorized = HttpStatusCode.Unauthorized to """{"error":"use_dpop_nonce","message":"nonce"}"""
    private val ok = HttpStatusCode.OK to """{"ok":true}"""

    @Test
    fun aFailedCallIsReportedOnceWithItsNsidAndTheThrownError() = runTest {
        val listener = RecordingListener()
        val (client, _) = client(listener, statuses = listOf(notFound))

        val thrown = assertFailsWith<XrpcError> { client.getPreferences() }

        assertEquals(1, listener.calls.size)
        assertEquals("app.bsky.actor.getPreferences", listener.calls.single().first)
        assertSame(thrown, listener.calls.single().second)
        assertEquals("NotFound", thrown.errorName)
        assertEquals(404, thrown.status)
    }

    @Test
    fun aSuccessfulCallIsNotReported() = runTest {
        val listener = RecordingListener()
        val (client, _) = client(listener, statuses = listOf(ok))

        client.getPreferences()

        assertTrue(listener.calls.isEmpty())
    }

    @Test
    fun aUnauthorizedThatTheRetryRecoversIsNotReported() = runTest {
        val listener = RecordingListener()
        val (client, engine) = client(listener, RetryOnceAuth, listOf(unauthorized, ok))

        client.getPreferences()

        assertEquals(2, engine.requestHistory.size)
        assertTrue(listener.calls.isEmpty(), "a recovered 401 is not a failure: ${listener.calls}")
    }

    @Test
    fun aRetryThatStillFailsIsReportedOnce() = runTest {
        val listener = RecordingListener()
        val (client, _) = client(listener, RetryOnceAuth, listOf(unauthorized, unauthorized))

        assertFailsWith<XrpcError> { client.getPreferences() }

        assertEquals(1, listener.calls.size)
        assertEquals("use_dpop_nonce", listener.calls.single().second.errorName)
    }

    @Test
    fun aFailingProcedureIsReportedToo() = runTest {
        val listener = RecordingListener()
        val (client, _) = client(listener, statuses = listOf(notFound))

        assertFailsWith<XrpcError> {
            client.procedure(
                nsid = "app.bsky.actor.putPreferences",
                params = Unit,
                paramsSerializer = Unit.serializer(),
                input = JsonObject(emptyMap()),
                inputSerializer = JsonObject.serializer(),
                responseSerializer = JsonObject.serializer(),
            )
        }

        assertEquals("app.bsky.actor.putPreferences", listener.calls.single().first)
    }

    @Test
    fun aListenerThatThrowsDoesNotChangeTheErrorTheCallerGets() = runTest {
        val (client, _) = client(XrpcErrorListener { _, _ -> error("listener broke") }, statuses = listOf(notFound))

        val thrown = assertFailsWith<XrpcError> { client.getPreferences() }

        assertEquals("NotFound", thrown.errorName)
        assertEquals("listener broke", thrown.suppressedExceptions.single().message)
    }

    @Test
    fun noListenerBehavesAsBefore() = runTest {
        val (client, _) = client(null, statuses = listOf(notFound))

        val thrown = assertFailsWith<XrpcError> { client.getPreferences() }

        assertEquals("NotFound", thrown.errorName)
    }
}
