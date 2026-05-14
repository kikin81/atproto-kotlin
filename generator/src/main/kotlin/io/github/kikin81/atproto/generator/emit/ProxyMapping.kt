package io.github.kikin81.atproto.generator.emit

/**
 * Maps an NSID prefix to the AT Protocol service identifier that must be
 * sent in the `atproto-proxy` HTTP header for requests in that namespace
 * to reach the correct appview.
 *
 * Bluesky hosts certain namespaces (chat, future notifications, etc.) on
 * dedicated appviews behind the user's PDS. Without this header the PDS
 * rejects the call with a 403 ScopeMissingError because the default
 * audience is the main appview (`did:web:api.bsky.app`).
 *
 * The table is intentionally tiny and hardcoded — the lexicon JSON does
 * not carry routing metadata, so this is the canonical source of truth
 * in the SDK. Add new entries here as Bluesky publishes new
 * appview-routed namespaces.
 */
internal object ProxyMapping {
    private val rules: List<Pair<String, String>> = listOf(
        "chat.bsky." to "did:web:api.bsky.chat#bsky_chat",
    )

    fun proxyFor(nsid: String): String? = rules.firstOrNull { (prefix, _) -> nsid.startsWith(prefix) }?.second
}
