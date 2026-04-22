package io.github.kikin81.atproto.runtime

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A CID link as encoded by the AT Protocol data model.
 *
 * On the wire, a CID link is always a single-key JSON object with the reserved
 * `$link` key holding the CID string:
 *
 * ```json
 * { "$link": "bafkreig..." }
 * ```
 *
 * The lexicon spec uses `cid-link` for this shape. It intentionally differs
 * from a bare `cid` string so that CBOR/DAG-CBOR round trips preserve the
 * "this is a content-addressed link" signal distinct from an opaque identifier.
 * v1 of this runtime is JSON-only but we keep the distinction so a future
 * CBOR emitter can map `CidLink` to the CBOR tag-42 link type without
 * touching call sites.
 */
@Serializable
public data class CidLink(
    @SerialName("\$link") public val link: String,
)

/**
 * A reference to an uploaded blob as embedded in a record body.
 *
 * AT Protocol blobs are stored out-of-band (uploaded via `com.atproto.repo.uploadBlob`,
 * addressed by CID) and *referenced* inline from the records that use them. The
 * inline reference shape is:
 *
 * ```json
 * {
 *   "$type": "blob",
 *   "ref":    { "$link": "bafkreig..." },
 *   "mimeType": "image/jpeg",
 *   "size":     123456
 * }
 * ```
 *
 * [type] is a constant `"blob"` discriminator required by the spec. It's
 * marked [EncodeDefault.Mode.ALWAYS] so that construction doesn't require
 * repeating the literal at every call site but the wire form still carries it.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class Blob(
    @SerialName("ref") public val ref: CidLink,
    @SerialName("mimeType") public val mimeType: String,
    @SerialName("size") public val size: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("\$type") public val type: String = "blob",
)
