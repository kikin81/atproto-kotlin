package io.github.kikin81.atproto.generator.emit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProxyMappingTest {

    @Test
    fun chat_namespace_maps_to_bsky_chat_appview() {
        assertEquals(
            "did:web:api.bsky.chat#bsky_chat",
            ProxyMapping.proxyFor("chat.bsky.convo.listConvos"),
        )
    }

    @Test
    fun any_chat_bsky_subnamespace_matches() {
        assertEquals(
            "did:web:api.bsky.chat#bsky_chat",
            ProxyMapping.proxyFor("chat.bsky.actor.deleteAccount"),
        )
    }

    @Test
    fun unrelated_namespace_returns_null() {
        assertNull(ProxyMapping.proxyFor("app.bsky.feed.getTimeline"))
    }

    @Test
    fun com_atproto_namespace_returns_null() {
        assertNull(ProxyMapping.proxyFor("com.atproto.server.createSession"))
    }
}
