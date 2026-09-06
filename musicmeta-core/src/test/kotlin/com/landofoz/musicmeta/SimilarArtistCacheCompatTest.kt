package com.landofoz.musicmeta

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A `SimilarArtist` payload written before `disambiguation` existed still reads.
 *
 * The round-trip tests encode and decode with the same tree, so they cannot see a persisted payload
 * becoming unreadable — the failure mode `CLAUDE.md` names and v0.4.0 shipped. The bodies below are
 * therefore written out as literal strings, as an already-cached Room entry holds them, rather than
 * produced by encoding the current class.
 *
 * The rule that makes this hold: a constructor parameter with a default value is optional on decode,
 * so a missing key takes the default. Only a parameter without one turns a missing key into a
 * `MissingFieldException`, which is what deleting `= null` from the field would do to every entry
 * already on a user's phone.
 */
class SimilarArtistCacheCompatTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `a payload cached before the field existed decodes with no disambiguation`() {
        // Given - a SimilarArtist as an earlier build wrote it, with no disambiguation key at all
        val cached = """{"name":"Loathe","identifiers":{},"matchScore":1.0,"sources":["lastfm"]}"""

        // When - the entry is decoded by this build
        val artist = json.decodeFromString<SimilarArtist>(cached)

        // Then - it reads, and the absent field is unknown rather than a decode failure
        assertEquals("Loathe", artist.name)
        assertEquals(listOf("lastfm"), artist.sources)
        assertNull(artist.disambiguation)
    }

    @Test
    fun `a whole cached list from before the field decodes entry by entry`() {
        // Given - the payload shape the cache holds for SIMILAR_ARTISTS, written by an earlier build
        val cached = """
            {"artists":[
              {"name":"Loathe","identifiers":{},"matchScore":1.0,"sources":["lastfm"]},
              {"name":"Spiritbox","identifiers":{},"matchScore":0.5,"sources":["deezer"]}
            ]}
        """.trimIndent()

        // When - the list is decoded
        val artists = json.decodeFromString<EnrichmentData.SimilarArtists>(cached).artists

        // Then - every entry survives with an unknown disambiguation, so no migration is owed
        assertEquals(2, artists.size)
        assertNull(artists[0].disambiguation)
        assertNull(artists[1].disambiguation)
    }

    @Test
    fun `an entry written by this build carries the field and still names its sources`() {
        // Given - an entry this build describes
        val artist = SimilarArtist(
            name = "Spiritbox",
            identifiers = EnrichmentIdentifiers(musicBrainzId = "9c935736-7530-41e4-b776-1dbcf534c061"),
            matchScore = 1.0f,
            sources = listOf("listenbrainz"),
            disambiguation = "Canadian metalcore",
        )

        // When - it is encoded and read back
        val decoded = json.decodeFromString<SimilarArtist>(json.encodeToString(artist))

        // Then - the text survives the round trip and sources is untouched by it
        assertEquals("Canadian metalcore", decoded.disambiguation)
        assertEquals(listOf("listenbrainz"), decoded.sources)
    }
}
