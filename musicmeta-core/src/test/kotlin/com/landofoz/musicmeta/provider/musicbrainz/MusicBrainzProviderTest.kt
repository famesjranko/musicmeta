package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MusicBrainzProviderTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: MusicBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = MusicBrainzProvider(httpClient, RateLimiter(0))
    }

    @Test
    fun `enrich album returns metadata for high-confidence match`() = runTest {
        // Given — MusicBrainz returns a release with score 98
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_HIGH_SCORE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for genre (triggers identity resolution)
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — success with MBID, release-group ID, and high confidence
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("musicbrainz", success.provider)
        assertEquals(0.98f, success.confidence, 0.01f)

        assertTrue(success.data is EnrichmentData.Metadata)
        assertEquals("abc123", success.resolvedIdentifiers?.musicBrainzId)
        assertEquals("group123", success.resolvedIdentifiers?.musicBrainzReleaseGroupId)
    }

    @Test
    fun `enrich album returns NotFound when score below threshold`() = runTest {
        // Given — MusicBrainz returns a low-score result (40)
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_LOW_SCORE)
        val request = EnrichmentRequest.forAlbum("Obscure Album", "Unknown")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — NotFound because score is below minimum threshold
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich artist extracts Wikidata ID`() = runTest {
        // Given — MusicBrainz artist has a Wikidata relation
        httpClient.givenJsonResponse("artist?query", ARTIST_SEARCH_WITH_WIKIDATA)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for genre (triggers identity resolution)
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — Wikidata ID extracted from the relation URL
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertTrue(success.data is EnrichmentData.Metadata)
        assertEquals("Q188451", success.resolvedIdentifiers?.wikidataId)
    }

    @Test
    fun `enrich artist extracts Wikipedia title`() = runTest {
        // Given — MusicBrainz artist has both Wikidata and Wikipedia relations
        httpClient.givenJsonResponse("artist?query", ARTIST_SEARCH_WITH_WIKIPEDIA)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — Wikipedia title extracted from the en.wikipedia.org URL
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertTrue(success.data is EnrichmentData.Metadata)
        assertEquals("Radiohead", success.resolvedIdentifiers?.wikipediaTitle)
    }

    @Test
    fun `enrich returns NotFound on null response`() = runTest {
        // Given — no canned response configured (API returns null -> emptyList)
        val request = EnrichmentRequest.forAlbum("Test", "Test")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — NotFound because empty results mean no match
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `empty album search results return NotFound`() = runTest {
        // Given — MusicBrainz returns an empty releases array
        httpClient.givenJsonResponse("release?query", """{"releases":[]}""")
        val request = EnrichmentRequest.forAlbum("Nothing", "Nobody")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — NotFound because no releases matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `empty artist search results return NotFound`() = runTest {
        // Given — MusicBrainz returns an empty artists array
        httpClient.givenJsonResponse("artist?query", """{"artists":[]}""")
        val request = EnrichmentRequest.forArtist("Nobody")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — NotFound because no artists matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `empty recording search results return NotFound`() = runTest {
        // Given — MusicBrainz returns an empty recordings array
        httpClient.givenJsonResponse("recording?query", """{"recordings":[]}""")
        val request = EnrichmentRequest.forTrack("Nothing", "Nobody")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — NotFound because no recordings matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Error on malformed JSON`() = runTest {
        // Given — response has a release object missing the required 'id' field
        httpClient.givenJsonResponse(
            "release?query",
            """{"releases":[{"title":"NoId"}]}""",
        )
        val request = EnrichmentRequest.forAlbum("Test", "Test")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — Error with a descriptive message about the parse failure
        assertTrue(result is EnrichmentResult.Error)
        assertNotNull((result as EnrichmentResult.Error).message)
    }

    @Test
    fun `enrich track returns identifier for high-confidence match`() = runTest {
        // Given — MusicBrainz returns a recording match with score 95
        httpClient.givenJsonResponse("recording?query", RECORDING_SEARCH)
        val request = EnrichmentRequest.forTrack("Paranoid Android", "Radiohead")

        // When — enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — recording MBID resolved successfully
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertTrue(success.data is EnrichmentData.Metadata)
        assertEquals("rec1", success.resolvedIdentifiers?.musicBrainzId)
    }

    @Test
    fun `enrich returns BandMembers for artist with members`() = runTest {
        // Given -- artist lookup with artist-rels returns band member relations
        httpClient.givenJsonResponse("artist/art1?fmt=json&inc=tags+url-rels+artist-rels", ARTIST_LOOKUP_WITH_MEMBERS)
        val request = EnrichmentRequest.forArtist("Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "art1"))

        // When -- enriching for BAND_MEMBERS
        val result = provider.enrich(request, EnrichmentType.BAND_MEMBERS)

        // Then -- Success with BandMembers data containing member names and roles
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val data = success.data as EnrichmentData.BandMembers
        assertEquals(2, data.members.size)
        assertEquals("Thom Yorke", data.members[0].name)
        assertEquals("lead vocals", data.members[0].role)
    }

    @Test
    fun `enrich returns NotFound for BandMembers when no members in relations`() = runTest {
        // Given -- artist lookup returns no member-of-band relations
        httpClient.givenJsonResponse("artist/art1?fmt=json&inc=tags+url-rels+artist-rels", ARTIST_LOOKUP_NO_MEMBERS)
        val request = EnrichmentRequest.forArtist("Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "art1"))

        // When -- enriching for BAND_MEMBERS
        val result = provider.enrich(request, EnrichmentType.BAND_MEMBERS)

        // Then -- NotFound because no band members in relations
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Discography for artist`() = runTest {
        // Given -- artist search followed by release-group browse
        httpClient.givenJsonResponse("artist?query", ARTIST_SEARCH_WITH_WIKIDATA)
        httpClient.givenJsonResponse("release-group?artist=art1", RELEASE_GROUP_BROWSE)
        val request = EnrichmentRequest.forArtist("Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "art1"))

        // When -- enriching for ARTIST_DISCOGRAPHY
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then -- Success with Discography data
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val data = success.data as EnrichmentData.Discography
        assertEquals(2, data.albums.size)
        assertEquals("OK Computer", data.albums[0].title)
        assertEquals("1997", data.albums[0].year)
        assertEquals("Album", data.albums[0].type)
    }

    @Test
    fun `enrich returns Tracklist for album`() = runTest {
        // Given -- release lookup with media array
        httpClient.givenJsonResponse("release/rel1?fmt=json", RELEASE_LOOKUP_WITH_TRACKS)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "rel1"))

        // When -- enriching for ALBUM_TRACKS
        val result = provider.enrich(request, EnrichmentType.ALBUM_TRACKS)

        // Then -- Success with Tracklist data
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val data = success.data as EnrichmentData.Tracklist
        assertEquals(2, data.tracks.size)
        assertEquals("Airbag", data.tracks[0].title)
        assertEquals(1, data.tracks[0].position)
        assertEquals(284000L, data.tracks[0].durationMs)
    }

    @Test
    fun `enrich returns ArtistLinks for artist`() = runTest {
        // Given -- artist lookup with URL relations
        httpClient.givenJsonResponse("artist/art1?fmt=json&inc=tags+url-rels", ARTIST_LOOKUP_WITH_LINKS)
        val request = EnrichmentRequest.forArtist("Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "art1"))

        // When -- enriching for ARTIST_LINKS
        val result = provider.enrich(request, EnrichmentType.ARTIST_LINKS)

        // Then -- Success with ArtistLinks data, wikidata/wikipedia excluded
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val data = success.data as EnrichmentData.ArtistLinks
        assertEquals(2, data.links.size)
        assertEquals("official homepage", data.links[0].type)
        assertEquals("https://radiohead.com", data.links[0].url)
    }

    @Test
    fun `enrich returns Error with NETWORK ErrorKind when API throws IOException`() = runTest {
        // Given — fetchJsonResult throws IOException simulating a network failure
        httpClient.givenIoException("musicbrainz.org")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for genre (triggers searchReleases which throws)
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then — Error with NETWORK ErrorKind
        assertTrue("Expected Error but got $result", result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    companion object {
        private val RELEASE_SEARCH_HIGH_SCORE = """
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

        private val RELEASE_SEARCH_LOW_SCORE = """
            {
              "releases": [{
                "id": "low1",
                "score": 40,
                "title": "Something Else",
                "artist-credit": [{"artist": {"name": "Someone"}}]
              }]
            }
        """.trimIndent()

        private val ARTIST_SEARCH_WITH_WIKIDATA = """
            {
              "artists": [{
                "id": "art1",
                "name": "Radiohead",
                "score": 100,
                "type": "Group",
                "country": "GB",
                "tags": [{"name": "alternative rock", "count": 10}],
                "relations": [{
                  "type": "wikidata",
                  "url": {"resource": "https://www.wikidata.org/wiki/Q188451"}
                }]
              }]
            }
        """.trimIndent()

        private val ARTIST_SEARCH_WITH_WIKIPEDIA = """
            {
              "artists": [{
                "id": "art1",
                "name": "Radiohead",
                "score": 100,
                "type": "Group",
                "tags": [{"name": "rock", "count": 5}],
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

        private val ARTIST_LOOKUP_WITH_MEMBERS = """
            {
              "id": "art1",
              "name": "Radiohead",
              "type": "Group",
              "tags": [{"name": "rock", "count": 5}],
              "relations": [
                {
                  "type": "member of band",
                  "artist": {"id": "m1", "name": "Thom Yorke"},
                  "attributes": ["lead vocals"],
                  "begin": "1985",
                  "ended": false
                },
                {
                  "type": "member of band",
                  "artist": {"id": "m2", "name": "Jonny Greenwood"},
                  "attributes": ["guitar"],
                  "begin": "1985",
                  "ended": false
                }
              ]
            }
        """.trimIndent()

        private val ARTIST_LOOKUP_NO_MEMBERS = """
            {
              "id": "art1",
              "name": "Radiohead",
              "type": "Group",
              "tags": [{"name": "rock", "count": 5}],
              "relations": [
                {
                  "type": "wikidata",
                  "target-type": "url",
                  "url": {"resource": "https://www.wikidata.org/wiki/Q188451"}
                }
              ]
            }
        """.trimIndent()

        private val RELEASE_GROUP_BROWSE = """
            {
              "release-groups": [
                {
                  "id": "rg1",
                  "title": "OK Computer",
                  "primary-type": "Album",
                  "first-release-date": "1997-06-16"
                },
                {
                  "id": "rg2",
                  "title": "The Bends",
                  "primary-type": "Album",
                  "first-release-date": "1995-03-13"
                }
              ]
            }
        """.trimIndent()

        private val RELEASE_LOOKUP_WITH_TRACKS = """
            {
              "id": "rel1",
              "title": "OK Computer",
              "artist-credit": [{"artist": {"name": "Radiohead"}}],
              "release-group": {"id": "rg1", "primary-type": "Album"},
              "media": [{
                "tracks": [
                  {
                    "title": "Airbag",
                    "position": 1,
                    "length": 284000,
                    "recording": {"id": "rec-1"}
                  },
                  {
                    "title": "Paranoid Android",
                    "position": 2,
                    "length": 383000,
                    "recording": {"id": "rec-2"}
                  }
                ]
              }]
            }
        """.trimIndent()

        private val ARTIST_LOOKUP_WITH_LINKS = """
            {
              "id": "art1",
              "name": "Radiohead",
              "type": "Group",
              "tags": [{"name": "rock", "count": 5}],
              "relations": [
                {
                  "type": "official homepage",
                  "target-type": "url",
                  "url": {"resource": "https://radiohead.com"}
                },
                {
                  "type": "bandcamp",
                  "target-type": "url",
                  "url": {"resource": "https://radiohead.bandcamp.com"}
                },
                {
                  "type": "wikidata",
                  "target-type": "url",
                  "url": {"resource": "https://www.wikidata.org/wiki/Q188451"}
                },
                {
                  "type": "wikipedia",
                  "target-type": "url",
                  "url": {"resource": "https://en.wikipedia.org/wiki/Radiohead"}
                }
              ]
            }
        """.trimIndent()

        private val RECORDING_SEARCH = """
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
    }
}
