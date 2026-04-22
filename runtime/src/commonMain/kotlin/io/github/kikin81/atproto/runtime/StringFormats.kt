package io.github.kikin81.atproto.runtime

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Typed wrappers around the AT Protocol string formats defined by the lexicon
 * spec (https://atproto.com/specs/lexicon#primitive-types). Each is an inline
 * value class over [String]: zero runtime cost, but the compiler refuses to let
 * you pass a [Handle] where a [Did] is expected.
 *
 * The `@Serializable` annotation on each value class causes kotlinx-serialization
 * to encode/decode it transparently as the underlying string on the wire.
 */

/** A decentralized identifier (e.g. `did:plc:abc123`). Format: `did`. */
@JvmInline
@Serializable
public value class Did(public val raw: String)

/** A user-facing handle (e.g. `alice.bsky.social`). Format: `handle`. */
@JvmInline
@Serializable
public value class Handle(public val raw: String)

/** A [Did] or a [Handle]. Format: `at-identifier`. */
@JvmInline
@Serializable
public value class AtIdentifier(public val raw: String)

/** An AT Protocol URI (e.g. `at://did:plc:abc/app.bsky.feed.post/tid`). Format: `at-uri`. */
@JvmInline
@Serializable
public value class AtUri(public val raw: String)

/** A content identifier (IPLD CID). Format: `cid`. */
@JvmInline
@Serializable
public value class Cid(public val raw: String)

/** A namespaced identifier (e.g. `app.bsky.feed.post`). Format: `nsid`. */
@JvmInline
@Serializable
public value class Nsid(public val raw: String)

/** A record key component of an AT URI. Format: `record-key`. */
@JvmInline
@Serializable
public value class RecordKey(public val raw: String)

/** A timestamp identifier used as a record key. Format: `tid`. */
@JvmInline
@Serializable
public value class Tid(public val raw: String)

/** An RFC3339 datetime with required timezone. Format: `datetime`. */
@JvmInline
@Serializable
public value class Datetime(public val raw: String)

/** A BCP-47 language tag. Format: `language`. */
@JvmInline
@Serializable
public value class Language(public val raw: String)

/** A generic URI. Format: `uri`. */
@JvmInline
@Serializable
public value class Uri(public val raw: String)
