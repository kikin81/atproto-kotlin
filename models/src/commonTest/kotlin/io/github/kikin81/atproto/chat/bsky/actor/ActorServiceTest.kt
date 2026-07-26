package io.github.kikin81.atproto.chat.bsky.actor

import io.github.kikin81.atproto.test.MockXrpcFixture
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ActorServiceTest {

    private lateinit var fixture: MockXrpcFixture

    @BeforeTest
    fun setup() {
        fixture = MockXrpcFixture()
    }

    private fun service() = ActorService(fixture.client)

    private fun assertChatProxyApplied() {
        // chat.bsky.* services route through the bsky chat appview by default.
        assertEquals(
            "did:web:api.bsky.chat#bsky_chat",
            fixture.capturedHeaders["atproto-proxy"],
        )
    }

    @Test
    fun getStatusIsGetWithoutParamsAndDecodesViewerChatState() = runTest {
        fixture.respondWith(
            """{"chatDisabled":false,"canCreateGroups":true,"groupMemberLimit":100}""",
        )

        val response = service().getStatus()

        assertEquals(HttpMethod.Get, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/chat.bsky.actor.getStatus")
        assertChatProxyApplied()
        assertEquals(false, response.chatDisabled)
        assertEquals(true, response.canCreateGroups)
        assertEquals(100L, response.groupMemberLimit)
    }

    @Test
    fun deleteAccountIsPostWithoutBody() = runTest {
        fixture.respondWith("{}")

        service().deleteAccount()

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/chat.bsky.actor.deleteAccount")
        assertChatProxyApplied()
    }

    @Test
    fun methodProxyOverrideWins() = runTest {
        fixture.respondWith(
            """{"chatDisabled":false,"canCreateGroups":true,"groupMemberLimit":100}""",
        )

        service().getStatus(proxy = "did:web:custom#svc")

        assertEquals("did:web:custom#svc", fixture.capturedHeaders["atproto-proxy"])
    }
}
