package com.landofoz.musicmeta.provider.musicbrainz

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzParserTest {

    @Test
    fun `parseReleases extracts all fields from search response`() {
        // Given — full MusicBrainz release search JSON with all fields populated
        val json = JSONObject(RELEASE_SEARCH_RESPONSE)

        // When — parsing releases
        val releases = MusicBrainzParser.parseReleases(json)

        // Then — all fields extracted: id, title, artist, date, country, label, group, type, score, cover, tags
        assertEquals(1, releases.size)
        val release = releases[0]
        assertEquals("abc123", release.id)
        assertEquals("OK Computer", release.title)
        assertEquals("Radiohead", release.artistCredit)
        assertEquals("1997-06-16", release.date)
        assertEquals("GB", release.country)
        assertEquals("Parlophone", release.label)
        assertEquals("group123", release.releaseGroupId)
        assertEquals("Album", release.releaseType)
        assertEquals(98, release.score)
        assertTrue(release.hasFrontCover)
        assertEquals(listOf("alternative rock"), release.tags)
    }

    @Test
    fun `parseReleases handles missing optional fields`() {
        // Given — minimal release JSON with only id, score, and title
        val json = JSONObject(
            """{"releases":[{"id":"min1","score":70,"title":"Minimal"}]}""",
        )

        // When — parsing releases
        val releases = MusicBrainzParser.parseReleases(json)

        // Then — required fields populated, all optional fields are null/empty
        assertEquals(1, releases.size)
        val release = releases[0]
        assertEquals("min1", release.id)
        assertEquals("Minimal", release.title)
        assertEquals(70, release.score)
        assertNull(release.artistCredit)
        assertNull(release.date)
        assertNull(release.country)
        assertNull(release.label)
        assertNull(release.releaseGroupId)
        assertNull(release.releaseType)
        assertTrue(release.tags.isEmpty())
    }

    @Test
    fun `parseReleases falls back to release-group tags`() {
        // Given — release has no top-level tags, but release-group has tags
        val json = JSONObject(RELEASE_WITH_GROUP_TAGS)

        // When — parsing releases
        val releases = MusicBrainzParser.parseReleases(json)

        // Then — tags sourced from release-group
        assertEquals(1, releases.size)
        assertEquals(listOf("electronic", "ambient"), releases[0].tags)
    }

    @Test
    fun `parseArtists extracts Wikidata ID from URL relations`() {
        // Given — artist JSON with a Wikidata URL relation
        val json = JSONObject(ARTIST_SEARCH_WITH_RELATIONS)

        // When — parsing artists
        val artists = MusicBrainzParser.parseArtists(json)

        // Then — Wikidata ID extracted from the URL path
        assertEquals(1, artists.size)
        assertEquals("Q188451", artists[0].wikidataId)
    }

    @Test
    fun `parseArtists extracts Wikipedia title from URL relations`() {
        // Given — artist JSON with both Wikidata and Wikipedia relations
        val json = JSONObject(ARTIST_WITH_WIKIPEDIA)

        // When — parsing artists
        val artists = MusicBrainzParser.parseArtists(json)

        // Then — Wikipedia title extracted from en.wikipedia.org URL
        assertEquals(1, artists.size)
        assertEquals("Radiohead", artists[0].wikipediaTitle)
    }

    @Test
    fun `parseRecording extracts ISRCs`() {
        // Given — recording JSON with ISRCs array
        val json = JSONObject(RECORDING_SEARCH_RESPONSE)

        // When — parsing recordings
        val recordings = MusicBrainzParser.parseRecordings(json)

        // Then — recording fields and ISRCs extracted
        assertEquals(1, recordings.size)
        assertEquals("rec1", recordings[0].id)
        assertEquals("Paranoid Android", recordings[0].title)
        assertEquals(listOf("GBAYE9700100"), recordings[0].isrcs)
        assertEquals(95, recordings[0].score)
    }

    @Test
    fun `parseReleases handles empty search results`() {
        // Given — valid JSON with an empty releases array
        val json = JSONObject("""{"releases":[]}""")

        // When — parsing releases
        val releases = MusicBrainzParser.parseReleases(json)

        // Then — returns empty list, no errors
        assertTrue(releases.isEmpty())
    }

    @Test
    fun `parseReleases handles missing releases key`() {
        // Given — JSON object with no "releases" key at all
        val json = JSONObject("""{}""")

        // When — parsing releases
        val releases = MusicBrainzParser.parseReleases(json)

        // Then — returns empty list gracefully
        assertTrue(releases.isEmpty())
    }

    @Test
    fun `parseLookupRelease parses top-level entity`() {
        // Given — a direct lookup response (not wrapped in a search array)
        val json = JSONObject(LOOKUP_RELEASE)

        // When — parsing as a lookup release
        val release = MusicBrainzParser.parseLookupRelease(json)

        // Then — id, title, and implicit score=100 extracted
        assertEquals("look1", release?.id)
        assertEquals("The Bends", release?.title)
        assertEquals(100, release?.score)
    }

    @Test
    fun `parseLookupArtist parses top-level entity with relations`() {
        // Given — a direct artist lookup response with Wikidata and Wikipedia relations
        val json = JSONObject(LOOKUP_ARTIST)

        // When — parsing as a lookup artist
        val artist = MusicBrainzParser.parseLookupArtist(json)

        // Then — all fields including external IDs extracted
        assertEquals("art1", artist?.id)
        assertEquals("Radiohead", artist?.name)
        assertEquals("Q188451", artist?.wikidataId)
        assertEquals("Radiohead", artist?.wikipediaTitle)
        assertEquals(100, artist?.score)
    }

    @Test
    fun `extractArtistCredit concatenates names with join phrases`() {
        // Given — artist-credit array with two artists and a "feat." join phrase
        val obj = JSONObject(
            """{"artist-credit":[
                {"artist":{"name":"Massive Attack"},"joinphrase":" feat. "},
                {"artist":{"name":"Tricky"},"joinphrase":""}
            ]}""",
        )

        // When — extracting artist credit
        val credit = MusicBrainzParser.extractArtistCredit(obj)

        // Then — names concatenated with join phrase
        assertEquals("Massive Attack feat. Tricky", credit)
    }

    companion object {
        private val RELEASE_SEARCH_RESPONSE = """
            {
              "releases": [{
                "id": "abc123",
                "score": 98,
                "title": "OK Computer",
                "artist-credit": [{"artist": {"id": "def456", "name": "Radiohead"}}],
                "date": "1997-06-16",
                "country": "GB",
                "label-info": [{"label": {"name": "Parlophone"}}],
                "release-group": {
                  "id": "group123",
                  "primary-type": "Album",
                  "tags": [{"name": "alternative rock", "count": 5}]
                },
                "cover-art-archive": {"front": true}
              }]
            }
        """.trimIndent()

        private val RELEASE_WITH_GROUP_TAGS = """
            {
              "releases": [{
                "id": "rg1",
                "score": 90,
                "title": "Selected Ambient Works",
                "release-group": {
                  "id": "grp1",
                  "primary-type": "Album",
                  "tags": [
                    {"name": "electronic", "count": 10},
                    {"name": "ambient", "count": 8}
                  ]
                }
              }]
            }
        """.trimIndent()

        private val ARTIST_SEARCH_WITH_RELATIONS = """
            {
              "artists": [{
                "id": "art1",
                "name": "Radiohead",
                "score": 100,
                "type": "Group",
                "country": "GB",
                "life-span": {"begin": "1985", "end": null},
                "tags": [{"name": "alternative rock", "count": 10}],
                "relations": [{
                  "type": "wikidata",
                  "url": {"resource": "https://www.wikidata.org/wiki/Q188451"}
                }]
              }]
            }
        """.trimIndent()

        private val ARTIST_WITH_WIKIPEDIA = """
            {
              "artists": [{
                "id": "art1",
                "name": "Radiohead",
                "score": 100,
                "relations": [
                  {
                    "type": "wikidata",
                    "url": {"resource": "https://www.wikidata.org/wiki/Q188451"}
                  },
                  {
                    "type": "wikipedia",
                    "url": {"resource": "https://en.wikipedia.org/wiki/Radiohead"}
                  }
                ]
              }]
            }
        """.trimIndent()

        private val RECORDING_SEARCH_RESPONSE = """
            {
              "recordings": [{
                "id": "rec1",
                "score": 95,
                "title": "Paranoid Android",
                "isrcs": ["GBAYE9700100"],
                "tags": [{"name": "alternative rock", "count": 3}]
              }]
            }
        """.trimIndent()

        private val LOOKUP_RELEASE = """
            {
              "id": "look1",
              "title": "The Bends",
              "artist-credit": [{"artist": {"name": "Radiohead"}}],
              "date": "1995-03-13",
              "country": "GB",
              "release-group": {"id": "rg1", "primary-type": "Album"}
            }
        """.trimIndent()

        private val LOOKUP_ARTIST = """
            {
              "id": "art1",
              "name": "Radiohead",
              "type": "Group",
              "country": "GB",
              "tags": [{"name": "alternative rock", "count": 10}],
              "relations": [
                {
                  "type": "wikidata",
                  "url": {"resource": "https://www.wikidata.org/wiki/Q188451"}
                },
                {
                  "type": "wikipedia",
                  "url": {"resource": "https://en.wikipedia.org/wiki/Radiohead"}
                }
              ]
            }
        """.trimIndent()
    }
}
