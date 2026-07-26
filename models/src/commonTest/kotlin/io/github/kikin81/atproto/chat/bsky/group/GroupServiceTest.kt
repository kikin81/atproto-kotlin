package io.github.kikin81.atproto.chat.bsky.group

import io.github.kikin81.atproto.runtime.AtField
import io.github.kikin81.atproto.runtime.Did
import io.github.kikin81.atproto.test.MockXrpcFixture
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class GroupServiceTest {

    private lateinit var fixture: MockXrpcFixture

    @BeforeTest
    fun setup() {
        fixture = MockXrpcFixture()
    }

    private fun service() = GroupService(fixture.client)

    /** Minimal valid JSON for the reused complex view types. */
    private val convoJson =
        """{"id":"3kconvo","members":[],"muted":false,"rev":"r1","unreadCount":0}"""
    private val joinLinkJson =
        """{"code":"abc123","createdAt":"2026-01-01T00:00:00Z",""" +
            """"enabledStatus":"enabled","joinRule":"open","requireApproval":false}"""

    private fun assertChatProxyApplied() {
        // chat.bsky.* services route through the bsky chat appview by default.
        assertEquals(
            "did:web:api.bsky.chat#bsky_chat",
            fixture.capturedHeaders["atproto-proxy"],
        )
    }

    // ---- procedures returning ConvoView ----------------------------------

    @Test
    fun createGroupPostsMembersAndNameWithChatProxy() = runTest {
        fixture.respondWith("""{"convo":$convoJson}""")

        val response = service().createGroup(
            CreateGroupRequest(
                members = listOf(Did("did:plc:alice"), Did("did:plc:bob")),
                name = "Trip planning",
            ),
        )

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/chat.bsky.group.createGroup")
        assertChatProxyApplied()
        val body = fixture.capturedBody
        assertNotNull(body)
        assertContains(body, "did:plc:alice")
        assertContains(body, "did:plc:bob")
        assertContains(body, "Trip planning")
        assertEquals("3kconvo", response.convo.id)
    }

    @Test
    fun createGroupSerializesUpTo49Members() = runTest {
        fixture.respondWith("""{"convo":$convoJson}""")

        val members = (1..49).map { Did("did:plc:member$it") }
        service().createGroup(CreateGroupRequest(members = members, name = "Max group"))

        val body = fixture.capturedBody
        assertNotNull(body)
        assertContains(body, "did:plc:member1\"")
        assertContains(body, "did:plc:member49")
    }

    @Test
    fun addMembersPostsConvoIdAndMembers() = runTest {
        fixture.respondWith("""{"convo":$convoJson}""")

        val response = service().addMembers(
            AddMembersRequest(convoId = "3kconvo", members = listOf(Did("did:plc:carol"))),
        )

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        assertContains(fixture.capturedUrl!!, "/xrpc/chat.bsky.group.addMembers")
        assertChatProxyApplied()
        assertContains(fixture.capturedBody!!, "did:plc:carol")
        assertEquals("3kconvo", response.convo.id)
    }

    @Test
    fun removeMembersPostsConvoIdAndMembers() = runTest {
        fixture.respondWith("""{"convo":$convoJson}""")

        val response = service().removeMembers(
            RemoveMembersRequest(convoId = "3kconvo", members = listOf(Did("did:plc:carol"))),
        )

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        assertContains(fixture.capturedUrl!!, "/xrpc/chat.bsky.group.removeMembers")
        assertChatProxyApplied()
        assertContains(fixture.capturedBody!!, "did:plc:carol")
        assertEquals("3kconvo", response.convo.id)
    }

    @Test
    fun editGroupPostsConvoIdAndName() = runTest {
        fixture.respondWith("""{"convo":$convoJson}""")

        val response = service().editGroup(EditGroupRequest(convoId = "3kconvo", name = "Renamed"))

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        assertContains(fixture.capturedUrl!!, "/xrpc/chat.bsky.group.editGroup")
        assertChatProxyApplied()
        assertContains(fixture.capturedBody!!, "Renamed")
        assertEquals("3kconvo", response.convo.id)
    }

    @Test
    fun approveJoinRequestPostsConvoIdAndMember() = runTest {
        fixture.respondWith("""{"convo":$convoJson}""")

        val response = service().approveJoinRequest(
            ApproveJoinRequestRequest(convoId = "3kconvo", member = Did("did:plc:dan")),
        )

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        assertContains(fixture.capturedUrl!!, "/xrpc/chat.bsky.group.approveJoinRequest")
        assertChatProxyApplied()
        assertContains(fixture.capturedBody!!, "did:plc:dan")
        assertEquals("3kconvo", response.convo.id)
    }

    @Test
    fun requestJoinPostsCodeAndDeserializesStatus() = runTest {
        fixture.respondWith("""{"status":"joined","convo":$convoJson}""")

        val response = service().requestJoin(RequestJoinRequest(code = "invite-xyz"))

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        assertContains(fixture.capturedUrl!!, "/xrpc/chat.bsky.group.requestJoin")
        assertChatProxyApplied()
        assertContains(fixture.capturedBody!!, "invite-xyz")
        assertEquals("joined", response.status)
        assertEquals("3kconvo", response.convo?.id)
    }

    // ---- procedure with empty response -----------------------------------

    @Test
    fun rejectJoinRequestPostsConvoIdAndMember() = runTest {
        fixture.respondWith("{}")

        service().rejectJoinRequest(
            RejectJoinRequestRequest(convoId = "3kconvo", member = Did("did:plc:dan")),
        )

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        assertContains(fixture.capturedUrl!!, "/xrpc/chat.bsky.group.rejectJoinRequest")
        assertChatProxyApplied()
        assertContains(fixture.capturedBody!!, "did:plc:dan")
    }

    // ---- join-link procedures returning JoinLinkView ---------------------

    @Test
    fun createJoinLinkPostsRuleAndApproval() = runTest {
        fixture.respondWith("""{"joinLink":$joinLinkJson}""")

        val response = service().createJoinLink(
            CreateJoinLinkRequest(
                convoId = "3kconvo",
                joinRule = "open",
                requireApproval = AtField.Defined(true),
            ),
        )

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        assertContains(fixture.capturedUrl!!, "/xrpc/chat.bsky.group.createJoinLink")
        assertChatProxyApplied()
        assertContains(fixture.capturedBody!!, "\"requireApproval\":true")
        assertEquals("abc123", response.joinLink.code)
    }

    @Test
    fun editJoinLinkPostsConvoId() = runTest {
        fixture.respondWith("""{"joinLink":$joinLinkJson}""")

        val response = service().editJoinLink(
            EditJoinLinkRequest(convoId = "3kconvo", joinRule = AtField.Defined("closed")),
        )

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        assertContains(fixture.capturedUrl!!, "/xrpc/chat.bsky.group.editJoinLink")
        assertChatProxyApplied()
        assertContains(fixture.capturedBody!!, "closed")
        assertEquals("abc123", response.joinLink.code)
    }

    @Test
    fun enableJoinLinkPostsConvoId() = runTest {
        fixture.respondWith("""{"joinLink":$joinLinkJson}""")

        val response = service().enableJoinLink(EnableJoinLinkRequest(convoId = "3kconvo"))

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        assertContains(fixture.capturedUrl!!, "/xrpc/chat.bsky.group.enableJoinLink")
        assertChatProxyApplied()
        assertContains(fixture.capturedBody!!, "3kconvo")
        assertEquals("abc123", response.joinLink.code)
    }

    @Test
    fun disableJoinLinkPostsConvoId() = runTest {
        fixture.respondWith("""{"joinLink":$joinLinkJson}""")

        val response = service().disableJoinLink(DisableJoinLinkRequest(convoId = "3kconvo"))

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        assertContains(fixture.capturedUrl!!, "/xrpc/chat.bsky.group.disableJoinLink")
        assertChatProxyApplied()
        assertContains(fixture.capturedBody!!, "3kconvo")
        assertEquals("abc123", response.joinLink.code)
    }

    // ---- queries ----------------------------------------------------------

    @Test
    fun listJoinRequestsIsGetWithPaginatedParams() = runTest {
        fixture.respondWith("""{"cursor":"next","requests":[]}""")

        val response = service().listJoinRequests(
            ListJoinRequestsRequest(convoId = "3kconvo", limit = 25, cursor = "abc"),
        )

        assertEquals(HttpMethod.Get, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/chat.bsky.group.listJoinRequests")
        assertContains(url, "convoId=3kconvo")
        assertContains(url, "limit=25")
        assertContains(url, "cursor=abc")
        assertChatProxyApplied()
        assertEquals("next", response.cursor)
        assertEquals(emptyList(), response.requests)
    }

    @Test
    fun getGroupPublicInfoIsGetWithCodeParam() = runTest {
        fixture.respondWith(
            """{"group":{"memberCount":5,"name":"My Group",""" +
                """"owner":{"did":"did:plc:owner","handle":"owner.test"},""" +
                """"requireApproval":false}}""",
        )

        val response = service().getGroupPublicInfo(GetGroupPublicInfoRequest(code = "invite-xyz"))

        assertEquals(HttpMethod.Get, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/chat.bsky.group.getGroupPublicInfo")
        assertContains(url, "code=invite-xyz")
        assertChatProxyApplied()
        assertEquals(5L, response.group.memberCount)
        assertEquals("My Group", response.group.name)
    }

    // ---- join-link previews / mutual groups ------------------------------

    @Test
    fun getJoinLinkPreviewsDecodesEachUnionMemberAndFallsBackOnUnknown() = runTest {
        // One of each known member, plus a $type the SDK has never seen — the
        // open union must degrade to Unknown rather than failing the response.
        fixture.respondWith(
            """{"joinLinkPreviews":[""" +
                """{"${'$'}type":"chat.bsky.group.defs#joinLinkPreviewView",""" +
                """"code":"abc123","convoId":"3kconvo","joinRule":"open",""" +
                """"memberCount":5,"memberLimit":100,"name":"My Group",""" +
                """"owner":{"did":"did:plc:alice"},"requireApproval":false},""" +
                """{"${'$'}type":"chat.bsky.group.defs#disabledJoinLinkPreviewView","code":"dead01"},""" +
                """{"${'$'}type":"chat.bsky.group.defs#invalidJoinLinkPreviewView","code":"bogus1"},""" +
                """{"${'$'}type":"chat.bsky.group.defs#futureJoinLinkPreviewView","code":"new001"}]}""",
        )

        val response = service().getJoinLinkPreviews(
            GetJoinLinkPreviewsRequest(codes = listOf("abc123", "dead01", "bogus1", "new001")),
        )

        assertEquals(HttpMethod.Get, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/chat.bsky.group.getJoinLinkPreviews")
        // Array param repeats the key — all four codes survive the round trip.
        assertContains(url, "codes=abc123")
        assertContains(url, "codes=new001")
        assertChatProxyApplied()

        val previews = response.joinLinkPreviews
        assertEquals(4, previews.size)
        val known = previews[0]
        assertIs<JoinLinkPreviewView>(known)
        assertEquals("My Group", known.name)
        assertEquals(5L, known.memberCount)
        assertEquals(Did("did:plc:alice"), known.owner.did)
        assertIs<DisabledJoinLinkPreviewView>(previews[1])
        assertIs<InvalidJoinLinkPreviewView>(previews[2])
        val unknown = previews[3]
        assertIs<GetJoinLinkPreviewsResponseJoinLinkPreviewsUnion.Unknown>(unknown)
        assertEquals("chat.bsky.group.defs#futureJoinLinkPreviewView", unknown.type)
    }

    @Test
    fun listMutualGroupsIsGetWithSubjectAndPaginationParams() = runTest {
        fixture.respondWith("""{"cursor":"next","convos":[$convoJson]}""")

        val response = service().listMutualGroups(
            ListMutualGroupsRequest(subject = Did("did:plc:bob"), limit = 10, cursor = "abc"),
        )

        assertEquals(HttpMethod.Get, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/chat.bsky.group.listMutualGroups")
        assertContains(url, "subject=did%3Aplc%3Abob")
        assertContains(url, "limit=10")
        assertContains(url, "cursor=abc")
        assertChatProxyApplied()
        assertEquals("next", response.cursor)
        assertEquals(1, response.convos.size)
        assertEquals("3kconvo", response.convos[0].id)
    }

    // ---- join-request procedures -----------------------------------------

    @Test
    fun updateJoinRequestsReadPostsConvoId() = runTest {
        fixture.respondWith("{}")

        service().updateJoinRequestsRead(UpdateJoinRequestsReadRequest(convoId = "3kconvo"))

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/chat.bsky.group.updateJoinRequestsRead")
        assertChatProxyApplied()
        val body = fixture.capturedBody
        assertNotNull(body)
        assertContains(body, "3kconvo")
    }

    @Test
    fun withdrawJoinRequestPostsConvoId() = runTest {
        fixture.respondWith("{}")

        service().withdrawJoinRequest(WithdrawJoinRequestRequest(convoId = "3kconvo"))

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/chat.bsky.group.withdrawJoinRequest")
        assertChatProxyApplied()
        val body = fixture.capturedBody
        assertNotNull(body)
        assertContains(body, "3kconvo")
    }

    @Test
    fun methodProxyOverrideWins() = runTest {
        fixture.respondWith("""{"convo":$convoJson}""")

        service().editGroup(
            EditGroupRequest(convoId = "3kconvo", name = "x"),
            proxy = "did:web:custom#svc",
        )

        assertEquals("did:web:custom#svc", fixture.capturedHeaders["atproto-proxy"])
    }
}
