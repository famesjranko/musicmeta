package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.PopularitySignal
import com.landofoz.musicmeta.PopularitySignalKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `inc=ratings` rides on lookups already made, so the rating costs no request of its own. These pin
 * the parse and the mapping onto the `PopularitySignal` that carries it into the public payload.
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

    @Test
    fun `a parsed rating becomes a sourced popularity signal`() {
        // Given - the artist lookup MusicBrainz answers with a 4.05 from 49 votes
        val artist = MusicBrainzParser.parseLookupArtist(JSONObject(ARTIST_LOOKUP_RATED))!!

        // When - mapping it to the public popularity payload
        val popularity = MusicBrainzMapper.toPopularity(artist.rating)

        // Then - one RATING signal labelled musicbrainz, normalised against the 1-5 scale
        val signal = popularity.signals.single()
        assertEquals("musicbrainz", signal.source)
        assertEquals(PopularitySignalKind.RATING, signal.kind)
        assertEquals(4.05, signal.value, 0.001)
        assertEquals(0.81f, signal.normalized!!, 0.001f)
        assertEquals(49, signal.sampleSize)
    }

    @Test
    fun `a rating never fills a listen count`() {
        // Given - the same rated artist, whose rating is a score and not a count of anything
        val artist = MusicBrainzParser.parseLookupArtist(JSONObject(ARTIST_LOOKUP_RATED))!!

        // When - mapping it
        val popularity = MusicBrainzMapper.toPopularity(artist.rating)

        // Then - the flat count fields stay empty, so no merger can blend 4.05 into a play count
        assertNull(popularity.listenCount)
        assertNull(popularity.listenerCount)
        assertNull(popularity.rank)
    }

    @Test
    fun `an unrated entity yields no signal at all`() {
        // Given - the recording nobody has voted on
        val recording = MusicBrainzParser.parseLookupRecording(JSONObject(RECORDING_LOOKUP_UNRATED))!!

        // When - mapping its absent rating
        val popularity = MusicBrainzMapper.toPopularity(recording.rating)

        // Then - an empty list, which the provider reports as NotFound rather than a zero score
        assertEquals(emptyList<PopularitySignal>(), popularity.signals)
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
