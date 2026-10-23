package io.github.kikin81.atproto.generator.tools

/**
 * A GitHub repository that publishes canonical lexicons under `lexicons/`.
 *
 * Since bluesky-social/atproto#5591 (2026-10-15) the canonical source is split:
 * `com.atproto` / `tools.ozone` stay in `atproto`, while `app.bsky`, `chat.bsky`
 * and `network.bsky` moved to `bluesky-social/bsky`. Detectors must consult every
 * repo here, or namespaces that moved read as "removed upstream".
 */
internal data class UpstreamRepo(
    val slug: String,
    val branch: String = "main",
    /** NSID prefixes this repo is the canonical home of. */
    val nsidPrefixes: List<String>,
) {
    val treeUrl: String get() = "https://api.github.com/repos/$slug/git/trees/$branch?recursive=1"
    val rawBase: String get() = "https://raw.githubusercontent.com/$slug/$branch/lexicons"
    val browseUrl: String get() = "https://github.com/$slug/tree/$branch/lexicons"
    val label: String get() = "$slug@$branch"
}

internal val UPSTREAM_REPOS: List<UpstreamRepo> = listOf(
    UpstreamRepo("bluesky-social/atproto", nsidPrefixes = listOf("com.atproto.", "tools.ozone.")),
    UpstreamRepo("bluesky-social/bsky", nsidPrefixes = listOf("app.bsky.", "chat.bsky.", "network.bsky.")),
)

/**
 * Repos to try for [nsid], canonical home first, the rest as fallback (a
 * namespace can move again; a fallback hit beats a false "removed" report).
 */
internal fun reposFor(nsid: String, repos: List<UpstreamRepo> = UPSTREAM_REPOS): List<UpstreamRepo> {
    val (home, others) = repos.partition { repo -> repo.nsidPrefixes.any(nsid::startsWith) }
    return home + others
}
