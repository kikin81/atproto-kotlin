package io.github.kikin81.atproto.app.bsky.video

import io.github.kikin81.atproto.test.MockXrpcFixture
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class VideoServiceTest {

    private lateinit var fixture: MockXrpcFixture

    @BeforeTest
    fun setup() {
        fixture = MockXrpcFixture()
    }

    @Test
    fun uploadPartPostsRawBytesWithJobAndPartNumberInTheQuery() = runTest {
        fixture.respondWith("""{"partNumber":3,"sizeBytes":5}""")

        val response = VideoService(fixture.client).uploadPart(
            request = UploadPartRequest(jobId = "job-1", partNumber = 3),
            input = "hello".encodeToByteArray(),
        )

        assertEquals(HttpMethod.Post, fixture.capturedMethod)
        val url = fixture.capturedUrl
        assertNotNull(url)
        assertContains(url, "/xrpc/app.bsky.video.uploadPart")
        assertContains(url, "jobId=job-1")
        assertContains(url, "partNumber=3")
        // The lexicon's input.encoding is application/octet-stream; that is the default.
        assertEquals("application/octet-stream", fixture.capturedContentType)
        // Bytes go in the body verbatim, never JSON-encoded.
        assertEquals("hello", fixture.capturedBody)
        assertEquals(3L, response.partNumber)
        assertEquals(5L, response.sizeBytes)
    }
}
