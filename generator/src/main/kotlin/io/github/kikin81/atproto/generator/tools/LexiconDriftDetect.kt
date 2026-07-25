package io.github.kikin81.atproto.generator.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TreeMap
import kotlin.io.path.exists
import kotlin.system.exitProcess

/**
 * Detects upstream lexicon drift against `generator/lexicons.json`.
 *
 * Compares NSIDs covered by this repo's manifest against the
 * `bluesky-social/atproto` upstream lexicon corpus at main, and reports:
 *   - new upstream NSIDs we don't cover at all (coverage gap)
 *   - manifest NSIDs no longer present upstream (deprecations / renames)
 *
 * "Covered" spans both manifest sections: the `lexicons` opt-in array *and*
 * the `resolutions` lockfile, which pins the transitive closure `lex install`
 * walked to. A transitively-resolved NSID is installed and generated just like
 * an opted-in one, so it is not a gap.
 *
 * Runnable locally (prints a markdown report to stdout) and from
 * `.github/workflows/lexicon-drift-detect.yaml` (writes `has_drift`,
 * counts, and `body` to `$GITHUB_OUTPUT`).
 *
 * Honors `GITHUB_TOKEN` — auth bumps the GitHub API rate limit from 60
 * to 5000 req/hr. Unauthenticated also works (one call per run).
 */

private const val UPSTREAM_TREE_URL =
    "https://api.github.com/repos/bluesky-social/atproto/git/trees/main?recursive=1"

private val json = Json { ignoreUnknownKeys = true }

@Serializable
internal data class TreeResponse(
    val tree: List<TreeEntry> = emptyList(),
    val truncated: Boolean = false,
)

@Serializable
internal data class TreeEntry(val path: String, val type: String = "")

/**
 * The `generator/lexicons.json` manifest.
 *
 * @property lexicons NSIDs explicitly opted into.
 * @property resolutions Pinned NSID -> `at://` URI + CID lockfile, a superset
 *   of [lexicons]: `lex install` walks refs and pins every document it
 *   resolves, so transitive dependencies (`app.bsky.embed.images` via
 *   `app.bsky.feed.post`, `com.atproto.repo.strongRef`, the `*.defs`
 *   documents) land here without ever being opted into. They are installed,
 *   generated, and published — reporting one as a coverage gap is a false
 *   positive, so the "new upstream" diff subtracts these too.
 */
@Serializable
internal data class Manifest(
    val lexicons: List<String> = emptyList(),
    val resolutions: Map<String, ResolutionRef> = emptyMap(),
)

@Serializable
internal data class ResolutionRef(val uri: String = "", val cid: String = "")

internal fun parseManifest(manifestJson: String): Manifest = json.decodeFromString<Manifest>(manifestJson)

/**
 * Every NSID upstream publishes, including `*.defs` documents.
 *
 * This is the set to diff *manifest* NSIDs against — the manifest may name a
 * `*.defs` document explicitly (nothing opted-in refs it), and stripping defs
 * from only one side of that difference would report it missing forever.
 */
internal fun parseUpstreamNsids(treeJson: String): Set<String> {
    val parsed = json.decodeFromString<TreeResponse>(treeJson)
    if (parsed.truncated) {
        System.err.println(
            "WARNING: upstream tree response truncated; " +
                "drift report may miss NSIDs",
        )
    }
    return parsed.tree.asSequence()
        .filter { it.path.startsWith("lexicons/") && it.path.endsWith(".json") }
        .map {
            it.path.removePrefix("lexicons/").removeSuffix(".json").replace('/', '.')
        }
        .toSet()
}

/**
 * Upstream NSIDs that are plausible opt-in targets.
 *
 * `*.defs` documents are never opted into directly — they arrive transitively
 * as refs of whatever names them — so listing one as a coverage gap is always
 * noise. Applies to the "new upstream" side of the diff only.
 */
internal fun optInCandidates(upstream: Set<String>): Set<String> = upstream.filterNot { it.endsWith(".defs") }.toSet()

@Serializable
internal data class OverlayManifestRef(val overlays: List<OverlayRef> = emptyList())

@Serializable
internal data class OverlayRef(val nsid: String)

/**
 * NSIDs handled by vendored overlays (`generator/overlay-lexicons.json`).
 *
 * These are intentionally absent from `lexicons.json` — they're served by the
 * appview but not yet resolvable on-network — so the drift report subtracts
 * them from the "new upstream" set to avoid flagging them forever. Their
 * lifecycle is tracked by the overlay-staleness job instead. Missing file ->
 * empty set.
 */
internal fun parseOverlayNsids(overlayManifestJson: String): Set<String> = json.decodeFromString<OverlayManifestRef>(overlayManifestJson).overlays.map { it.nsid }.toSet()

internal fun isSubscription(nsid: String): Boolean = nsid.substringAfterLast('.').startsWith("subscribe")

/**
 * Groups NSIDs by namespace.
 *
 * 4+ segment NSIDs (e.g. `app.bsky.feed.getTimeline`) bucket by their
 * first three segments. 3-segment NSIDs (e.g. `app.bsky.authFullApp`
 * OAuth scope tokens) bucket at the 2-segment level so they group
 * together instead of each producing a solo bucket.
 */
internal fun bucketByNamespace(nsids: Set<String>): Map<String, List<String>> {
    val grouped = nsids.groupBy { nsid ->
        val parts = nsid.split('.')
        when {
            parts.size >= 4 -> parts.take(3).joinToString(".")
            parts.size > 1 -> parts.dropLast(1).joinToString(".")
            else -> nsid
        }
    }
    return TreeMap<String, List<String>>().apply { putAll(grouped) }
}

private val timestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")

internal fun renderReport(
    newNsids: Set<String>,
    removedNsids: Set<String>,
    now: OffsetDateTime,
    overlayCount: Int = 0,
    transitiveCount: Int = 0,
): String = buildString {
    appendLine(
        "_Generated ${timestampFormatter.format(now)} by " +
            "`.github/workflows/lexicon-drift-detect.yaml`._",
    )
    appendLine()
    appendLine(
        "Compares `generator/lexicons.json` against " +
            "[`bluesky-social/atproto@main`]" +
            "(https://github.com/bluesky-social/atproto/tree/main/lexicons).",
    )
    appendLine()
    if (overlayCount > 0) {
        appendLine(
            "_($overlayCount NSID(s) handled by overlays — excluded here; " +
                "see the `overlay-stale` tracking issue.)_",
        )
        appendLine()
    }
    if (transitiveCount > 0) {
        appendLine(
            "_($transitiveCount NSID(s) covered transitively via `resolutions` " +
                "— installed and generated without being opted into, so " +
                "excluded here.)_",
        )
        appendLine()
    }

    appendLine("## New upstream NSIDs (${newNsids.size})")
    appendLine()
    if (newNsids.isEmpty()) {
        appendLine("_(none — manifest is current with upstream.)_")
        appendLine()
    } else {
        val (subs, regular) = newNsids.partition(::isSubscription)
        if (subs.isNotEmpty()) {
            appendLine(
                "### Subscription type (${subs.size}) — architectural gap",
            )
            appendLine()
            appendLine(
                "These are `type=subscription` lexicons. The generator " +
                    "currently skips them; adding requires runtime WebSocket " +
                    "transport (tracking: `kikinlex-0j8`).",
            )
            appendLine()
            for (nsid in subs.sorted()) appendLine("- `$nsid`")
            appendLine()
        }
        if (regular.isNotEmpty()) {
            for ((ns, members) in bucketByNamespace(regular.toSet())) {
                appendLine("### `$ns.*` (${members.size})")
                appendLine()
                for (nsid in members.sorted()) appendLine("- `$nsid`")
                appendLine()
            }
        }
    }

    appendLine("## Manifest NSIDs missing from upstream (${removedNsids.size})")
    appendLine()
    appendLine(
        "NSIDs we list that no longer exist in `bluesky-social/atproto`. " +
            "Possible deprecation, rename, or non-Bluesky publisher. " +
            "Needs human triage before removal from the manifest.",
    )
    appendLine()
    if (removedNsids.isEmpty()) {
        appendLine("_(none — every manifest NSID exists upstream.)_")
        appendLine()
    } else {
        for (nsid in removedNsids.sorted()) appendLine("- `$nsid`")
        appendLine()
    }

    appendLine("---")
    appendLine()
    append(
        "**Triage note.** Not every upstream NSID is a target for this SDK. " +
            "Namespaces typically intentionally out of scope: `tools.ozone.*` " +
            "(moderation tooling), `com.atproto.admin.*` (PDS-operator surface), " +
            "`com.atproto.temp.*` (deprecated/internal), `app.bsky.unspecced.*` " +
            "(experimental, churns). When you decide to opt into a new " +
            "namespace, append the NSIDs to `generator/lexicons.json` and run " +
            "the regen flow.",
    )
}

private fun fetchUpstreamTree(token: String?): String {
    val builder = HttpRequest.newBuilder()
        .uri(URI.create(UPSTREAM_TREE_URL))
        .timeout(Duration.ofSeconds(30))
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "kikinlex-lexicon-drift-detect")
        .GET()
    if (!token.isNullOrEmpty()) builder.header("Authorization", "Bearer $token")
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() == 200) {
        "GitHub API returned ${response.statusCode()}: ${response.body().take(200)}"
    }
    return response.body()
}

private fun emitActionsOutputs(
    outputFile: Path,
    body: String,
    hasDrift: Boolean,
    newCount: Int,
    removedCount: Int,
) {
    val delim = "EOF_drift_body_marker"
    val payload = buildString {
        append("has_drift=").append(if (hasDrift) "true" else "false").append('\n')
        append("new_count=").append(newCount).append('\n')
        append("removed_count=").append(removedCount).append('\n')
        append("body<<").append(delim).append('\n')
        append(body)
        append('\n').append(delim).append('\n')
    }
    Files.writeString(
        outputFile,
        payload,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
    )
}

/**
 * CLI entry point: `java -jar generator.jar <manifestPath?>`.
 *
 * If no manifest path is given, defaults to `generator/lexicons.json`
 * relative to the working directory (the layout when invoked via
 * `./gradlew :generator:detectLexiconDrift` from the repo root).
 */
public fun main(args: Array<String>) {
    val manifestPath =
        if (args.isNotEmpty()) Path.of(args[0]) else Path.of("generator/lexicons.json")
    if (!manifestPath.exists()) {
        System.err.println("manifest not found: $manifestPath")
        exitProcess(2)
    }

    // Overlay NSIDs are intentionally absent from lexicons.json (vendored
    // until resolvable on-network); exclude them so they don't read as drift
    // forever. Their lifecycle is tracked by the overlay-staleness job.
    val overlayManifestPath = manifestPath.resolveSibling("overlay-lexicons.json")
    val overlayNsids = if (overlayManifestPath.exists()) {
        parseOverlayNsids(Files.readString(overlayManifestPath))
    } else {
        emptySet()
    }

    val upstream = parseUpstreamNsids(fetchUpstreamTree(System.getenv("GITHUB_TOKEN")))
    val manifest = parseManifest(Files.readString(manifestPath))
    val declared = manifest.lexicons.toSet()
    // `resolutions` pins everything lex install walked to, so it already
    // contains `declared`; the difference is what we cover transitively.
    val resolved = manifest.resolutions.keys
    val transitive = resolved - declared

    val newNsids = optInCandidates(upstream) - declared - resolved - overlayNsids
    // Diffed against the unfiltered upstream set: the manifest names a few
    // `*.defs` documents directly, and those exist upstream.
    val removedNsids = declared - upstream

    val hasDrift = newNsids.isNotEmpty() || removedNsids.isNotEmpty()
    val body =
        renderReport(
            newNsids,
            removedNsids,
            OffsetDateTime.now(ZoneOffset.UTC),
            overlayNsids.size,
            transitive.size,
        )

    println(body)

    System.getenv("GITHUB_OUTPUT")?.takeIf { it.isNotEmpty() }?.let { outFilePath ->
        emitActionsOutputs(
            Path.of(outFilePath),
            body,
            hasDrift,
            newNsids.size,
            removedNsids.size,
        )
    }
}
