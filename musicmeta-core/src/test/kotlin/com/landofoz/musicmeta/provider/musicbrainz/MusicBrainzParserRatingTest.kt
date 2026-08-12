package com.landofoz.musicmeta.provider.musicbrainz

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `inc=ratings` rides on lookups already made, so the rating is parsed now and stops at the DTO —
 * no `EnrichmentData` payload carries one yet. These pin the parse so the surface that eventually
 * reads it inherits a tested field rather than an unread one.
 */
class MusicBrainzParserRatingTest {

    @Test
    fun `an artist lookup carries its community rating`() {
        // Given - an artist lookup response with a rating block
        val json = JSONObject(ARTIST_LOOKUP_RATED)

        // When - parsing it
        val artist = MusicBrainzParser.parseLookupArtist(json)!!

        // Then - value and vote count both survive
        assertEquals(4.05f, artist.rating!!.value, 0.001f)
        assertEquals(49, artist.rating!!.votes)
    }

    @Test
    fun `a recording lookup carries its community rating`() {
        // Given - a recording lookup response with a rating block
        val json = JSONObject(RECORDING_LOOKUP_RATED)

        // When - parsing it
        val recording = MusicBrainzParser.parseLookupRecording(json)!!

        // Then - value and vote count both survive
        assertEquals(4.4f, recording.rating!!.value, 0.001f)
        assertEquals(41, recording.rating!!.votes)
    }

    @Test
    fun `an unrated entity has no rating rather than a rating of zero`() {
        // Given - the block MusicBrainz sends for a recording nobody has voted on
        val json = JSONObject(RECORDING_LOOKUP_UNRATED)

        // When - parsing it
        val recording = MusicBrainzParser.parseLookupRecording(json)!!

        // Then - null, because a null value is an absence and 0.0 would be a score
        assertNull(recording.rating)
    }

    @Test
    fun `a response with no rating block parses`() {
        // Given - a lookup that did not ask for ratings
        val json = JSONObject("""{ "id": "rec1", "title": "Karma Police" }""")

        // When - parsing it
        val recording = MusicBrainzParser.parseLookupRecording(json)!!

        // Then - absence is normal, not a parse failure
        assertNull(recording.rating)
    }

    private companion object {
        // captured 2026-08-12: GET /artist/cc197bad-dc9c-440d-a5b5-d52ba2e14234
        // ?inc=tags+genres+aliases+ratings+url-rels+artist-rels, trimmed to the rated fields
        const val ARTIST_LOOKUP_RATED = """
        {
          "id": "cc197bad-dc9c-440d-a5b5-d52ba2e14234",
          "name": "Coldplay",
          "type": "Group",
          "country": "GB",
          "rating": { "value": 4.05, "votes-count": 49 }
        }
        """

        // captured 2026-08-12: GET /recording/b1a9c0e9-d987-4042-ae91-78d6a3267d69
        // ?inc=...+tags+genres+ratings, trimmed to the rated fields
        const val RECORDING_LOOKUP_RATED = """
        {
          "id": "b1a9c0e9-d987-4042-ae91-78d6a3267d69",
          "title": "Bohemian Rhapsody",
          "rating": { "votes-count": 41, "value": 4.4 }
        }
        """

        // captured 2026-08-12: GET /recording/c79499c2-422d-419c-834e-383453c5c4e5
        // ?inc=tags+genres+ratings — an unrated recording, trimmed
        const val RECORDING_LOOKUP_UNRATED = """
        {
          "id": "c79499c2-422d-419c-834e-383453c5c4e5",
          "title": "Squarepusher Theme",
          "rating": { "votes-count": 0, "value": null }
        }
        """
    }
}
