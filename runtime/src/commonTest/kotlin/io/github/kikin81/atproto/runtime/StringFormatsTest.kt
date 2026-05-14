package io.github.kikin81.atproto.runtime

import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StringFormatsTest {

    private val json = Json

    private fun <T> assertRoundTrip(serializer: KSerializer<T>, value: T, expectedJson: String) {
        val encoded = json.encodeToString(serializer, value)
        assertEquals(expectedJson, encoded, "wire encoding must be a plain JSON string")
        val decoded = json.decodeFromString(serializer, encoded)
        assertEquals(value, decoded, "round-trip must preserve value")
    }

    @Test fun did_roundTrip() = assertRoundTrip(Did.serializer(), Did("did:plc:abc123"), "\"did:plc:abc123\"")

    @Test fun handle_roundTrip() = assertRoundTrip(Handle.serializer(), Handle("alice.bsky.social"), "\"alice.bsky.social\"")

    @Test fun atIdentifier_roundTrip() = assertRoundTrip(AtIdentifier.serializer(), AtIdentifier("alice.bsky.social"), "\"alice.bsky.social\"")

    @Test fun atUri_roundTrip() = assertRoundTrip(
        AtUri.serializer(),
        AtUri("at://did:plc:abc/app.bsky.feed.post/3k2j"),
        "\"at://did:plc:abc/app.bsky.feed.post/3k2j\"",
    )

    @Test fun cid_roundTrip() = assertRoundTrip(Cid.serializer(), Cid("bafyreib2rxk..."), "\"bafyreib2rxk...\"")

    @Test fun nsid_roundTrip() = assertRoundTrip(Nsid.serializer(), Nsid("app.bsky.feed.post"), "\"app.bsky.feed.post\"")

    @Test fun recordKey_roundTrip() = assertRoundTrip(RecordKey.serializer(), RecordKey("self"), "\"self\"")

    @Test fun tid_roundTrip() = assertRoundTrip(Tid.serializer(), Tid("3k2jabc12345z"), "\"3k2jabc12345z\"")

    @Test fun datetime_roundTrip() = assertRoundTrip(
        Datetime.serializer(),
        Datetime("2026-04-13T12:34:56.000Z"),
        "\"2026-04-13T12:34:56.000Z\"",
    )

    @Test fun language_roundTrip() = assertRoundTrip(Language.serializer(), Language("en-US"), "\"en-US\"")

    @Test fun uri_roundTrip() = assertRoundTrip(Uri.serializer(), Uri("https://example.com/thing"), "\"https://example.com/thing\"")

    @Test fun datetime_toInstant_parsesZuluLiteral() {
        val parsed = Datetime("2026-04-13T12:34:56Z").toInstant()
        assertEquals(Instant.parse("2026-04-13T12:34:56Z"), parsed)
    }

    @Test fun datetime_toInstant_parsesFractionalSeconds() {
        val parsed = Datetime("2026-04-13T12:34:56.789Z").toInstant()
        assertEquals(Instant.parse("2026-04-13T12:34:56.789Z"), parsed)
    }

    @Test fun datetime_toInstant_parsesOffsetTimezone() {
        // RFC 3339 accepts a numeric offset in place of 'Z' — same instant,
        // different on-the-wire form.
        val parsed = Datetime("2026-04-13T05:34:56-07:00").toInstant()
        assertEquals(Instant.parse("2026-04-13T12:34:56Z"), parsed)
    }

    @Test fun datetime_toInstant_throwsOnMalformedInput() {
        assertFailsWith<IllegalArgumentException> {
            Datetime("not a timestamp").toInstant()
        }
    }

    @Test fun datetime_toInstantOrNull_returnsParsedOnValidInput() {
        val parsed = Datetime("2026-04-13T12:34:56.789Z").toInstantOrNull()
        assertEquals(Instant.parse("2026-04-13T12:34:56.789Z"), parsed)
    }

    @Test fun datetime_toInstantOrNull_returnsNullOnMalformedInput() {
        assertNull(Datetime("not a timestamp").toInstantOrNull())
        assertNull(Datetime("").toInstantOrNull())
        // Missing required timezone — RFC 3339 violation.
        assertNull(Datetime("2026-04-13T12:34:56").toInstantOrNull())
    }

    @Test fun datetime_toInstant_roundTripsViaInstantToString() {
        // Datetime → Instant → wire string → Datetime preserves the instant
        // (formatting may normalize, but the moment in time is the same).
        val original = Datetime("2026-04-13T12:34:56.000Z")
        val instant = original.toInstant()
        val reconstructed = Datetime(instant.toString()).toInstant()
        assertEquals(instant, reconstructed)
    }
}
