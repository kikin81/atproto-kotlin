package io.github.kikin81.atproto.app.bsky.draft

import io.github.kikin81.atproto.test.MockXrpcFixture
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DraftServiceTest {

    private lateinit var fixture: MockXrpcFixture

    @BeforeTest
    fun setup() {
        fixture = MockXrpcFixture()
    }

    @Test
    fun createDraftPostsJsonEncodedInputToCorrectXrpcPath() = runTest {
        fixture.respondWith("""{"id":"draft-123"}""")

        val response = DraftService(fixture.client).createDraft(
            CreateDraftRequest(
                draft = Draft(
                    posts = listOf(DraftPost(text = "hello from a draft")),
                ),
            ),
        )

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/app.bsky.draft.createDraft")

        val contentType = fixture.capturedContentType
        assertNotNull(contentType)
        assertContains(contentType, "application/json")

        val body = fixture.capturedBody
        assertNotNull(body)
        assertContains(body, "\"draft\"")
        assertContains(body, "hello from a draft")

        assertEquals("draft-123", response.id)
    }
}
