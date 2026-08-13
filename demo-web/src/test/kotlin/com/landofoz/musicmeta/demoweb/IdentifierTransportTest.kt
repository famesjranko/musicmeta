package com.landofoz.musicmeta.demoweb

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [decodeTrackIdentifiers] is what `/api/preview` and `/api/enrich` both call to turn a row's `ids`
 * query parameter back into [com.landofoz.musicmeta.EnrichmentIdentifiers] — the single decode path
 * behind both endpoints, so its contract is tested once here rather than per-endpoint.
 */
class IdentifierTransportTest {

    private val wireJson = Json { encodeDefaults = true }

    private fun encode(ids: WireIdentifiers): String = wireJson.encodeToString(ids)

    @Test fun `absent ids decodes to null, the name-only path`() {
        // Given - no ids parameter at all

        // When - decoding
        val result = decodeTrackIdentifiers(null)

        // Then - null, so the caller falls back to a name search
        assertNull(result)
    }

    @Test fun `a Deezer track id round-trips through encode and decode exactly`() {
        // Given - a wire payload for a track row carrying a Deezer id and a recording MBID
        val wire = WireIdentifiers(
            entityKind = "TRACK",
            musicBrainzId = "mbid-starman",
            extra = mapOf("deezerId" to "107471926"),
        )

        // When - decoding the encoded form, exactly as a query parameter would carry it
        val result = decodeTrackIdentifiers(encode(wire))

        // Then - both identifiers survive unchanged
        assertEquals("mbid-starman", result?.musicBrainzId)
        assertEquals("107471926", result?.get("deezerId"))
    }

    @Test fun `malformed JSON is rejected rather than silently downgraded`() {
        // Given - a value that is not valid JSON at all

        // When - decoding
        try {
            decodeTrackIdentifiers("{not json")
            fail("expected InvalidIdentifiers")
        } catch (e: InvalidIdentifiers) {
            // Then - the caller sees an explicit rejection, not a null (name-only) fallback
            assertTrue(e.message!!.contains("malformed"))
        }
    }

    @Test fun `a mismatched entityKind is rejected`() {
        // Given - a payload scoped to an artist, arriving on a track-only decode path
        val wire = WireIdentifiers(entityKind = "ARTIST", musicBrainzId = "mbid-x")

        // When - decoding
        try {
            decodeTrackIdentifiers(encode(wire))
            fail("expected InvalidIdentifiers")
        } catch (e: InvalidIdentifiers) {
            // Then - rejected for scope, not accepted as a track identity
            assertTrue(e.message!!.contains("entityKind"))
        }
    }

    @Test fun `an unknown extra namespace is rejected`() {
        // Given - an extra identifier outside the track allowlist (a Spotify artist id)
        val wire = WireIdentifiers(entityKind = "TRACK", extra = mapOf("spotifyArtistId" to "123"))

        // When - decoding
        try {
            decodeTrackIdentifiers(encode(wire))
            fail("expected InvalidIdentifiers")
        } catch (e: InvalidIdentifiers) {
            // Then - rejected rather than silently dropped or accepted
            assertTrue(e.message!!.contains("spotifyArtistId"))
        }
    }

    @Test fun `an oversized ids parameter is rejected`() {
        // Given - a deezerId value long enough to push the whole parameter past the size cap
        val wire = WireIdentifiers(entityKind = "TRACK", extra = mapOf("deezerId" to "9".repeat(600)))

        // When - decoding
        try {
            decodeTrackIdentifiers(encode(wire))
            fail("expected InvalidIdentifiers")
        } catch (e: InvalidIdentifiers) {
            // Then - rejected for size
            assertTrue(e.message!!.contains("size"))
        }
    }

    @Test fun `an oversized individual identifier value is rejected`() {
        // Given - a parameter under the overall size cap, but a single value over the per-value cap
        val wire = WireIdentifiers(entityKind = "TRACK", extra = mapOf("deezerId" to "1".repeat(200)))

        // When - decoding
        try {
            decodeTrackIdentifiers(encode(wire))
            fail("expected InvalidIdentifiers")
        } catch (e: InvalidIdentifiers) {
            // Then - rejected for that value, not truncated or accepted
            assertTrue(e.message!!.contains("deezerId"))
        }
    }
}
