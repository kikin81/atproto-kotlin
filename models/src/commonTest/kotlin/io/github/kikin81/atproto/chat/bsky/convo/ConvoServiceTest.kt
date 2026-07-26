package io.github.kikin81.atproto.chat.bsky.convo

import io.github.kikin81.atproto.test.MockXrpcFixture
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class ConvoServiceTest {

    private lateinit var fixture: MockXrpcFixture

    @BeforeTest
    fun setup() {
        fixture = MockXrpcFixture()
    }

    private fun service() = ConvoService(fixture.client)

    private fun assertChatProxyApplied() {
        // chat.bsky.* services route through the bsky chat appview by default.
        assertEquals(
            "did:web:api.bsky.chat#bsky_chat",
            fixture.capturedHeaders["atproto-proxy"],
        )
    }

    @Test
    fun getUnreadCountsIsGetWithOptionalGroupFlagAndSplitCounts() = runTest {
        fixture.respondWith("""{"unreadAcceptedConvos":7,"unreadRequestConvos":2}""")

        val response = service().getUnreadCounts(
            GetUnreadCountsRequest(includeGroupChats = true),
        )

        assertEquals(HttpMethod.Get, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/chat.bsky.convo.getUnreadCounts")
        assertContains(url, "includeGroupChats=true")
        assertChatProxyApplied()
        assertEquals(7L, response.unreadAcceptedConvos)
        assertEquals(2L, response.unreadRequestConvos)
    }

    @Test
    fun getUnreadCountsOmitsTheGroupFlagWhenUnset() = runTest {
        fixture.respondWith("""{"unreadAcceptedConvos":0,"unreadRequestConvos":0}""")

        service().getUnreadCounts()

        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/chat.bsky.convo.getUnreadCounts")
        assertFalse(url.contains("includeGroupChats"))
    }

    @Test
    fun getConvoMembersIsGetWithPaginatedParamsAndChatProxy() = runTest {
        fixture.respondWith(
            """{"cursor":"next","members":[""" +
                """{"did":"did:plc:alice","handle":"alice.test"},""" +
                """{"did":"did:plc:bob","handle":"bob.test"}]}""",
        )

        val response = service().getConvoMembers(
            GetConvoMembersRequest(convoId = "3kconvo", limit = 25, cursor = "abc"),
        )

        assertEquals(HttpMethod.Get, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/chat.bsky.convo.getConvoMembers")
        assertContains(url, "convoId=3kconvo")
        assertContains(url, "limit=25")
        assertContains(url, "cursor=abc")
        assertChatProxyApplied()
        assertEquals("next", response.cursor)
        assertEquals(2, response.members.size)
        assertEquals("did:plc:alice", response.members[0].did.raw)
        assertEquals("bob.test", response.members[1].handle.raw)
    }

    @Test
    fun getConvoMembersOmitsOptionalParamsWhenAbsent() = runTest {
        fixture.respondWith("""{"members":[]}""")

        val response = service().getConvoMembers(GetConvoMembersRequest(convoId = "3kconvo"))

        assertEquals(HttpMethod.Get, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/chat.bsky.convo.getConvoMembers")
        assertContains(url, "convoId=3kconvo")
        assertChatProxyApplied()
        assertEquals(null, response.cursor)
        assertEquals(emptyList(), response.members)
    }
}
