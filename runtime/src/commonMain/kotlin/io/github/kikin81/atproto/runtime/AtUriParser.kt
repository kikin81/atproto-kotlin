package io.github.kikin81.atproto.runtime

/**
 * The structural decomposition of an [AtUri] into its constituent parts.
 *
 * Per the AT URI spec (https://atproto.com/specs/at-uri-scheme):
 * - [repo] is required (a DID or handle)
 * - [collection] is optional (an NSID)
 * - [rkey] is only meaningful when [collection] is also present
 * - [fragment] is optional and selects a sub-record
 *
 * Validation here is purely structural — the parser does not check that
 * [repo] is a syntactically-valid DID or handle, [collection] is a valid
 * NSID, etc. Semantic validity belongs to the wrapper types themselves.
 */
public data class AtUriParts(
    public val repo: AtIdentifier,
    public val collection: Nsid?,
    public val rkey: RecordKey?,
    public val fragment: String?,
)

/**
 * Parses this [AtUri] into its [AtUriParts].
 *
 * Validation is structural only: the URI must start with `at://`, the repo
 * segment must be non-empty, and any path segment between repo and rkey
 * must be non-empty. A single trailing slash after the repo or collection
 * is tolerated and normalized away. Semantic validity of the segments
 * (DID syntax, NSID syntax, etc.) is the responsibility of the wrapper
 * types and is intentionally not checked here.
 *
 * The returned [AtUriParts.fragment] is the bare suffix with the leading
 * `#` stripped, or `null` when no fragment is present — matching
 * `java.net.URI.getFragment()` conventions.
 *
 * Spec: https://atproto.com/specs/at-uri-scheme
 *
 * @throws IllegalArgumentException if the URI is structurally invalid.
 */
public fun AtUri.parse(): AtUriParts = parseOrNull() ?: throw IllegalArgumentException("Not a valid AT URI: $raw")

/**
 * Like [parse], but returns `null` instead of throwing on structural failure.
 *
 * Spec: https://atproto.com/specs/at-uri-scheme
 */
public fun AtUri.parseOrNull(): AtUriParts? {
    if (!raw.startsWith("at://")) return null
    val body = raw.substring("at://".length)

    val hashIdx = body.indexOf('#')
    val pathPart = if (hashIdx >= 0) body.substring(0, hashIdx) else body
    val fragment = if (hashIdx >= 0) body.substring(hashIdx + 1) else null

    val rawSegments = pathPart.split('/')
    // Tolerate one trailing slash only for `at://repo/` and `at://repo/collection/`.
    // A trailing slash after rkey (size 4) is rejected as structurally invalid.
    val segments = if (rawSegments.size in 2..3 && rawSegments.last().isEmpty()) {
        rawSegments.dropLast(1)
    } else {
        rawSegments
    }
    if (segments.isEmpty() || segments.size > 3) return null
    if (segments.any { it.isEmpty() }) return null

    return AtUriParts(
        repo = AtIdentifier(segments[0]),
        collection = segments.getOrNull(1)?.let(::Nsid),
        rkey = segments.getOrNull(2)?.let(::RecordKey),
        fragment = fragment,
    )
}
