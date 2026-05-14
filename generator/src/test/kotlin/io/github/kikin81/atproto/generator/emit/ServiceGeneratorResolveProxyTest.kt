package io.github.kikin81.atproto.generator.emit

import io.github.kikin81.atproto.generator.resolved.SymbolTable
import io.github.kikin81.atproto.generator.verify.VerificationFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceGeneratorResolveProxyTest {

    private val generator = ServiceGenerator(
        plan = EmissionPlan(
            classes = emptyMap(),
            unionSites = emptyList(),
            unionMembership = emptyMap(),
        ),
        symbols = SymbolTable.build(emptyList()),
    )

    @Test
    fun all_unproxied_returns_null() {
        assertNull(
            generator.resolveProxyForPackage(
                pkg = "io.github.kikin81.atproto.app.bsky.feed",
                nsids = listOf("app.bsky.feed.getTimeline", "app.bsky.feed.getAuthorFeed"),
            ),
        )
    }

    @Test
    fun all_proxied_to_same_did_returns_that_did() {
        assertEquals(
            "did:web:api.bsky.chat#bsky_chat",
            generator.resolveProxyForPackage(
                pkg = "io.github.kikin81.atproto.chat.bsky.convo",
                nsids = listOf("chat.bsky.convo.listConvos", "chat.bsky.convo.getConvo"),
            ),
        )
    }

    @Test
    fun single_nsid_proxied_returns_proxy() {
        assertEquals(
            "did:web:api.bsky.chat#bsky_chat",
            generator.resolveProxyForPackage(
                pkg = "io.github.kikin81.atproto.chat.bsky.convo",
                nsids = listOf("chat.bsky.convo.listConvos"),
            ),
        )
    }

    @Test
    fun empty_nsids_returns_null() {
        assertNull(
            generator.resolveProxyForPackage(
                pkg = "io.github.kikin81.atproto.empty",
                nsids = emptyList(),
            ),
        )
    }

    @Test
    fun mixed_proxied_and_unproxied_throws() {
        val failure = kotlin.runCatching {
            generator.resolveProxyForPackage(
                pkg = "io.github.kikin81.atproto.fake",
                nsids = listOf("chat.bsky.convo.listConvos", "app.bsky.feed.getTimeline"),
            )
        }.exceptionOrNull()
        assertTrue(failure is VerificationFailure, "expected VerificationFailure, got $failure")
        val message = failure.message!!
        assertTrue(message.contains("mixes proxy DIDs"), "expected 'mixes proxy DIDs' in: $message")
        assertTrue(message.contains("did:web:api.bsky.chat#bsky_chat"), "expected proxy DID in: $message")
        assertTrue(message.contains("<no proxy>"), "expected '<no proxy>' marker in: $message")
        assertTrue(message.contains("chat.bsky.convo.listConvos"), "expected NSID in: $message")
        assertTrue(message.contains("app.bsky.feed.getTimeline"), "expected NSID in: $message")
    }
}
