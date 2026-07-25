package io.github.kikin81.atproto.generator.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.io.path.exists
import kotlin.system.exitProcess

/**
 * Detects when a vendored lexicon overlay should be RETIRED or RE-VENDORED.
 *
 * An overlay is a lexicon we ship from `generator/overlay-lexicons/` because
 * the real record isn't yet resolvable on-network via `npx lex install`
 * (so it can't go in `generator/lexicons.json`). Each overlay carries a
 * manifest entry in `generator/overlay-lexicons.json`. This tool flags three
 * conditions per overlay:
 *
 *   - **publishable** — the on-network record now exists (the workflow probed
 *     it with the real `npx lex install` resolver and passed the result in via
 *     `OVERLAY_PUBLISHABLE_JSON`). The overlay should be RETIRED: move the NSID
 *     into `generator/lexicons.json`, `npx lex install`, delete the vendored
 *     file + manifest entry, regen.
 *   - **drifted** — the vendored JSON no longer matches the upstream
 *     `bluesky-social/atproto@main` copy (or upstream removed the path). The
 *     overlay should be RE-VENDORED: re-copy the upstream JSON + bump the
 *     manifest commit.
 *   - **redundant** — the vendored JSON is byte-identical (after
 *     canonicalization) to the document `npx lex install` actually fetches
 *     from the network (passed in via `OVERLAY_REDUNDANT_JSON`). The overlay
 *     contributes nothing and should be RETIRED, regardless of
 *     `removeWhenPublished` — this is the only signal that catches a pinned
 *     shadow whose local additions upstream has since absorbed.
 *
 * An overlay's manifest entry may also set `expectDrift: true` for a
 * deliberate, permanent superset of upstream (see `chat.bsky.group.defs`).
 * That flag suppresses the ordinary DRIFTED nag for exactly as long as the
 * overlay is actually drifted — see `isStale` below for how it stays honest
 * once the situation changes.
 *
 * Runnable locally (prints a markdown report to stdout) and from
 * `.github/workflows/overlay-staleness.yaml` (writes `has_stale`,
 * `stale_count`, and `body` to `$GITHUB_OUTPUT`).
 *
 * Honors `GITHUB_TOKEN` for the raw.githubusercontent.com fetches (auth bumps
 * the rate limit). Unauthenticated also works.
 */

private const val UPSTREAM_RAW_BASE =
    "https://raw.githubusercontent.com/bluesky-social/atproto/main/lexicons"

private val overlayJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
}

@Serializable
internal data class OverlayEntry(
    val nsid: String,
    val source: String = "",
    val commit: String = "",
    val vendoredAt: String = "",
    val reason: String = "",
    val removeWhenPublished: Boolean = false,
    // The vendored copy is INTENTIONALLY not byte-equal to upstream@main (e.g. it
    // re-adds a def upstream deleted that is still published on-network). Suppresses
    // the RE-VENDOR nag; see the isStale inversion below for how it stays honest.
    val expectDrift: Boolean = false,
    val driftReason: String = "",
)

@Serializable
internal data class OverlayManifest(
    val version: Int = 1,
    val overlays: List<OverlayEntry> = emptyList(),
)

/** Drift outcome for a single overlay. */
internal sealed interface DriftStatus {
    /** Vendored copy matches upstream@main. */
    data object InSync : DriftStatus

    /** Vendored copy differs from upstream@main. */
    data object Drifted : DriftStatus

    /** Upstream path 404s — the lexicon was removed/renamed upstream. */
    data object UpstreamRemoved : DriftStatus
}

internal fun parseOverlayManifest(manifestJson: String): OverlayManifest = overlayJson.decodeFromString<OverlayManifest>(manifestJson)

/** Parses a workflow-supplied `{nsid: bool}` map. Empty/blank/non-object -> empty. */
internal fun parseNsidBoolMap(raw: String?): Map<String, Boolean> {
    if (raw.isNullOrBlank()) return emptyMap()
    val element = overlayJson.parseToJsonElement(raw)
    if (element !is JsonObject) return emptyMap()
    return element.mapNotNull { (key, value) ->
        val prim = value as? JsonPrimitive ?: return@mapNotNull null
        val bool = prim.content.toBooleanStrictOrNull() ?: return@mapNotNull null
        key to bool
    }.toMap()
}

private fun String.toBooleanStrictOrNull(): Boolean? = when (this) {
    "true" -> true
    "false" -> false
    else -> null
}

/** `chat.bsky.convo.getConvoMembers` -> `chat/bsky/convo/getConvoMembers`. */
internal fun nsidToPath(nsid: String): String = nsid.replace('.', '/')

/**
 * Canonical, whitespace-insensitive serialization of a lexicon JSON document.
 *
 * Object keys are sorted recursively so re-ordered-but-equivalent upstream
 * edits don't read as drift; arrays preserve order (lexicon array order is
 * semantically meaningful). Returns null if the input isn't parseable JSON.
 */
internal fun canonicalizeJson(raw: String): String? = runCatching {
    overlayJson.encodeToString(JsonElement.serializer(), sortKeys(overlayJson.parseToJsonElement(raw)))
}.getOrNull()

private fun sortKeys(element: JsonElement): JsonElement = when (element) {
    is JsonObject ->
        JsonObject(element.entries.sortedBy { it.key }.associate { it.key to sortKeys(it.value) })
    is JsonArray ->
        JsonArray(element.map(::sortKeys))
    else -> element
}

/** Drift verdict for one overlay: compare the vendored file to upstream@main. */
internal fun computeDrift(
    vendoredRaw: String,
    upstreamRaw: String?,
): DriftStatus {
    if (upstreamRaw == null) return DriftStatus.UpstreamRemoved
    val vendoredCanon = canonicalizeJson(vendoredRaw)
    val upstreamCanon = canonicalizeJson(upstreamRaw)
    // If either side won't canonicalize, fall back to a trimmed raw compare.
    val left = vendoredCanon ?: vendoredRaw.trim()
    val right = upstreamCanon ?: upstreamRaw.trim()
    return if (left == right) DriftStatus.InSync else DriftStatus.Drifted
}

private val timestampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")

/** Per-overlay result fed to the report renderer. */
internal data class OverlayStatus(
    val nsid: String,
    val publishable: Boolean,
    val drift: DriftStatus,
    // Honors the manifest opt-out: an overlay pinned with removeWhenPublished=false
    // is intentionally kept even after upstream publishes, so it must NOT be flagged
    // for retirement. Defaults true (the common case).
    val removeWhenPublished: Boolean = true,
    // The vendored copy is byte-identical to the document `lex install` fetches, so
    // the overlay contributes nothing and is safe to delete. Deliberately IGNORES
    // removeWhenPublished: a pinned shadow whose local additions upstream has since
    // absorbed is exactly the case the drift-only comparison could never see.
    val redundant: Boolean = false,
    // Drift against upstream@main is EXPECTED for this overlay, so it must not nag.
    val expectDrift: Boolean = false,
    val driftReason: String = "",
) {
    /**
     * Stale = actionable: redundant (delete it), retire (publishable AND opted in to
     * removal), or re-vendor (drifted/removed). A pinned overlay
     * (removeWhenPublished=false) still surfaces drift — we just never tell the
     * maintainer to retire it.
     *
     * An `expectDrift` overlay inverts this: it is quiet ONLY while it is actually
     * drifted (that is the point of the flag). It becomes stale again if it reads
     * in-sync (upstream absorbed the local addition) or upstream-removed — both mean
     * the drift the flag was suppressing is no longer the current situation.
     */
    val isStale: Boolean get() = when {
        redundant -> true
        publishable && removeWhenPublished -> true
        // An expected-drift superset is quiet ONLY while it is actually drifted.
        // If it reads in-sync, upstream absorbed the local addition and the flag
        // itself is now the stale thing — so this inverts rather than mutes.
        expectDrift -> drift != DriftStatus.Drifted
        else -> drift != DriftStatus.InSync
    }
}

internal fun renderReport(
    statuses: List<OverlayStatus>,
    now: OffsetDateTime,
): String = buildString {
    appendLine(
        "_Generated ${timestampFormatter.format(now)} by " +
            "`.github/workflows/overlay-staleness.yaml`._",
    )
    appendLine()
    appendLine(
        "Tracks vendored lexicon overlays in `generator/overlay-lexicons.json`. " +
            "An overlay should be **retired** once its record is resolvable " +
            "on-network (so it belongs in `generator/lexicons.json`), or " +
            "**re-vendored** when its source JSON drifts from " +
            "[`bluesky-social/atproto@main`]" +
            "(https://github.com/bluesky-social/atproto/tree/main/lexicons).",
    )
    appendLine()

    val stale = statuses.filter { it.isStale }
    appendLine("## Overlays needing attention (${stale.size})")
    appendLine()
    if (stale.isEmpty()) {
        appendLine(
            "_(none — see \"All overlays\" below; an expected-drift superset can " +
                "make this list empty even while drifted by design.)_",
        )
        appendLine()
    } else {
        for (status in stale.sortedBy { it.nsid }) {
            appendLine(renderStaleLine(status))
            appendLine()
        }
    }

    appendLine("## All overlays (${statuses.size})")
    appendLine()
    if (statuses.isEmpty()) {
        appendLine("_(no overlays vendored.)_")
        appendLine()
    } else {
        for (status in statuses.sortedBy { it.nsid }) {
            val pub = when {
                status.publishable && !status.removeWhenPublished -> "publishable (pinned: removeWhenPublished=false)"
                status.publishable -> "publishable"
                else -> "not-yet-publishable"
            }
            val drift = when {
                status.expectDrift && status.drift == DriftStatus.Drifted ->
                    "SUPERSET (expected drift): ${status.driftReason}"
                status.drift == DriftStatus.InSync -> "in-sync"
                status.drift == DriftStatus.Drifted -> "DRIFTED"
                else -> "UPSTREAM-REMOVED"
            }
            val redundant = if (status.redundant) ", REDUNDANT" else ""
            appendLine("- `${status.nsid}` — $pub, $drift$redundant")
        }
        appendLine()
    }

    appendLine("---")
    appendLine()
    append(
        "**Note.** The publishable probe runs the real `npx lex install` " +
            "resolver against a throwaway manifest; success means the " +
            "on-network record now exists. Drift compares the vendored file " +
            "to upstream `main` with a key-sorted, whitespace-insensitive " +
            "JSON canonicalization." +
            " A REDUNDANT verdict means the vendored file and the document " +
            "`lex install` fetches are identical after canonicalization, so " +
            "deleting the overlay is a no-op. The probe resolves the LATEST " +
            "on-network document while the build installs the CID pinned in " +
            "`generator/lexicons.json`, so redundancy can lead the pins — the " +
            "`models.api` diff is what authorizes an actual retirement.",
    )
}

private fun renderStaleLine(status: OverlayStatus): String {
    val nsid = status.nsid
    val path = nsidToPath(nsid)
    return when {
        status.redundant -> {
            "♻️ `$nsid` — vendored copy is byte-identical to the on-network " +
                "document; the overlay contributes nothing. **RETIRE:** add to " +
                "`generator/lexicons.json` + `npx lex install`, " +
                "`rm generator/overlay-lexicons/$path.json` + its manifest entry, " +
                "`./gradlew :generator:generateModels apiDump`, then confirm " +
                "`git diff --exit-code models/api/models.api` is empty."
        }
        status.expectDrift && status.drift == DriftStatus.InSync -> {
            "🔄 `$nsid` — upstream `main` now matches the vendored superset, so " +
                "the local addition is no longer needed. **ACTION:** drop " +
                "`expectDrift` from its `generator/overlay-lexicons.json` entry " +
                "and re-evaluate the overlay for retirement."
        }
        status.publishable && status.removeWhenPublished -> {
            "✅ `$nsid` — now resolvable on-network. **RETIRE:** add to " +
                "`generator/lexicons.json` + `npx lex install`, " +
                "`rm generator/overlay-lexicons/$path.json` + its manifest entry, " +
                "`./gradlew :generator:generateModels apiDump`."
        }
        status.drift == DriftStatus.UpstreamRemoved -> {
            "⚠️ `$nsid` — upstream removed `lexicons/$path.json` from " +
                "bluesky-social/atproto@main. **TRIAGE:** confirm rename/deprecation; " +
                "if gone for good, retire the overlay or pin to a tagged commit."
        }
        else -> {
            "⚠️ `$nsid` — overlay drifted from bluesky-social/atproto@main. " +
                "**RE-VENDOR:** re-copy the upstream JSON into " +
                "`generator/overlay-lexicons/$path.json` + bump the manifest `commit`."
        }
    }
}

/** GET a raw upstream lexicon. Returns null on 404, throws on other non-200. */
private fun fetchUpstreamLexicon(nsid: String, token: String?): String? {
    val url = "$UPSTREAM_RAW_BASE/${nsidToPath(nsid)}.json"
    val builder = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("Accept", "application/vnd.github.raw+json")
        .header("User-Agent", "kikinlex-overlay-staleness-detect")
        .GET()
    if (!token.isNullOrEmpty()) builder.header("Authorization", "Bearer $token")
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()
    val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    return when (response.statusCode()) {
        200 -> response.body()
        404 -> null
        else -> error(
            "GitHub raw returned ${response.statusCode()} for $url: " +
                response.body().take(200),
        )
    }
}

private fun emitActionsOutputs(
    outputFile: Path,
    body: String,
    hasStale: Boolean,
    staleCount: Int,
) {
    val delim = "EOF_overlay_body_marker"
    val payload = buildString {
        append("has_stale=").append(if (hasStale) "true" else "false").append('\n')
        append("stale_count=").append(staleCount).append('\n')
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
 * CLI entry point: `java -jar generator.jar <overlayManifestPath?>`.
 *
 * If no path is given, defaults to `generator/overlay-lexicons.json` relative
 * to the working directory (the layout when invoked via
 * `./gradlew :generator:detectStaleOverlays` from the repo root). Vendored
 * files are read from `generator/overlay-lexicons/<nsid-as-path>.json`
 * alongside the manifest.
 *
 * Publishability comes from `OVERLAY_PUBLISHABLE_JSON` and redundancy comes
 * from `OVERLAY_REDUNDANT_JSON`, both `{nsid: bool}` maps supplied by the
 * workflow's probe step. Either reads as "not publishable" / "not redundant"
 * for an NSID it omits (including when the env var itself is absent).
 */
public fun main(args: Array<String>) {
    val manifestPath =
        if (args.isNotEmpty()) {
            Path.of(args[0])
        } else {
            Path.of("generator/overlay-lexicons.json")
        }
    if (!manifestPath.exists()) {
        System.err.println("overlay manifest not found: $manifestPath")
        exitProcess(2)
    }
    val overlayDir = manifestPath.resolveSibling("overlay-lexicons")

    val manifest = parseOverlayManifest(Files.readString(manifestPath))
    val publishable = parseNsidBoolMap(System.getenv("OVERLAY_PUBLISHABLE_JSON"))
    val redundantMap = parseNsidBoolMap(System.getenv("OVERLAY_REDUNDANT_JSON"))
    val token = System.getenv("GITHUB_TOKEN")

    val statuses = manifest.overlays.map { overlay ->
        val vendoredFile = overlayDir.resolve("${nsidToPath(overlay.nsid)}.json")
        val drift = if (!vendoredFile.exists()) {
            System.err.println(
                "WARNING: manifest lists ${overlay.nsid} but " +
                    "$vendoredFile is missing; treating as drifted",
            )
            DriftStatus.Drifted
        } else {
            computeDrift(
                Files.readString(vendoredFile),
                fetchUpstreamLexicon(overlay.nsid, token),
            )
        }
        OverlayStatus(
            nsid = overlay.nsid,
            publishable = publishable[overlay.nsid] == true,
            redundant = redundantMap[overlay.nsid] == true,
            drift = drift,
            removeWhenPublished = overlay.removeWhenPublished,
            expectDrift = overlay.expectDrift,
            driftReason = overlay.driftReason,
        )
    }

    val staleCount = statuses.count { it.isStale }
    val hasStale = staleCount > 0
    val body = renderReport(statuses, OffsetDateTime.now(ZoneOffset.UTC))

    println(body)

    System.getenv("GITHUB_OUTPUT")?.takeIf { it.isNotEmpty() }?.let { outFilePath ->
        emitActionsOutputs(Path.of(outFilePath), body, hasStale, staleCount)
    }
}
