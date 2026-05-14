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
 * Compares NSIDs declared in this repo's manifest against the
 * `bluesky-social/atproto` upstream lexicon corpus at main, and reports:
 *   - new upstream NSIDs not yet in our manifest (coverage gap)
 *   - manifest NSIDs no longer present upstream (deprecations / renames)
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

@Serializable
internal data class Manifest(val lexicons: List<String> = emptyList())

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
        .filterNot { it.endsWith(".defs") }
        .toSet()
}

internal fun parseManifestNsids(manifestJson: String): Set<String> = json.decodeFromString<Manifest>(manifestJson).lexicons.toSet()

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

    val upstream = parseUpstreamNsids(fetchUpstreamTree(System.getenv("GITHUB_TOKEN")))
    val manifest = parseManifestNsids(Files.readString(manifestPath))
    val newNsids = upstream - manifest
    val removedNsids = manifest - upstream
    val hasDrift = newNsids.isNotEmpty() || removedNsids.isNotEmpty()
    val body =
        renderReport(newNsids, removedNsids, OffsetDateTime.now(ZoneOffset.UTC))

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
