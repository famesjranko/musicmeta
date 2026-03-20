package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MusicBrainzSearchTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: MusicBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = MusicBrainzProvider(httpClient, RateLimiter(0))
    }

    @Test
    fun `searchCandidates returns album candidates with correct fields`() = runTest {
        // Given — MusicBrainz returns two releases for "OK Computer"
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_MULTIPLE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — searching for candidates
        val candidates = provider.searchCandidates(request, 10)

        // Then — both candidates returned with all metadata fields populated
        assertEquals(2, candidates.size)

        val first = candidates[0]
        assertEquals("OK Computer", first.title)
        assertEquals("Radiohead", first.artist)
        assertEquals("1997-06-16", first.year)
        assertEquals("GB", first.country)
        assertEquals("Album", first.releaseType)
        assertEquals(98, first.score)
        assertEquals("abc123", first.identifiers.musicBrainzId)
        assertEquals("group123", first.identifiers.musicBrainzReleaseGroupId)
        assertEquals("musicbrainz", first.provider)

        val second = candidates[1]
        assertEquals("OK Computer OKNOTOK 1997 2017", second.title)
        assertEquals(85, second.score)
    }

    @Test
    fun `searchCandidates returns artist candidates`() = runTest {
        // Given — MusicBrainz returns two artist matches
        httpClient.givenJsonResponse("artist?query", ARTIST_SEARCH_MULTIPLE)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — searching for artist candidates
        val candidates = provider.searchCandidates(request, 10)

        // Then — artist fields populated correctly (no "artist" field, title=name)
        assertEquals(2, candidates.size)

        val first = candidates[0]
        assertEquals("Radiohead", first.title)
        assertNull(first.artist)
        assertEquals("GB", first.country)
        assertEquals("Group", first.releaseType)
        assertEquals(100, first.score)
        assertEquals("art1", first.identifiers.musicBrainzId)
        assertEquals("musicbrainz", first.provider)
    }

    @Test
    fun `searchCandidates returns empty list for track requests`() = runTest {
        // Given — a track-level request (search only supports albums/artists)
        val request = EnrichmentRequest.forTrack("Paranoid Android", "Radiohead")

        // When — searching for candidates
        val candidates = provider.searchCandidates(request, 10)

        // Then — empty because track search is not supported
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `searchCandidates includes thumbnail URL when front cover exists`() = runTest {
        // Given — first release has front cover, second does not
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_MULTIPLE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — searching for candidates
        val candidates = provider.searchCandidates(request, 10)

        // Then — thumbnail URL derived from CAA for release with cover art
        val withCover = candidates[0]
        assertNotNull(withCover.thumbnailUrl)
        assertEquals(
            "https://coverartarchive.org/release/abc123/front-250",
            withCover.thumbnailUrl,
        )

        val withoutCover = candidates[1]
        assertNull(withoutCover.thumbnailUrl)
    }

    companion object {
        private val RELEASE_SEARCH_MULTIPLE = """
            {
              "releases": [
                {
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
                },
                {
                  "id": "xyz789",
                  "score": 85,
                  "title": "OK Computer OKNOTOK 1997 2017",
                  "artist-credit": [{"artist": {"id": "def456", "name": "Radiohead"}}],
                  "date": "2017-06-23",
                  "country": "XW",
                  "release-group": {
                    "id": "group456",
                    "primary-type": "Album"
                  }
                }
              ]
            }
        """.trimIndent()

        private val ARTIST_SEARCH_MULTIPLE = """
            {
              "artists": [
                {
                  "id": "art1",
                  "name": "Radiohead",
                  "score": 100,
                  "type": "Group",
                  "country": "GB",
                  "life-span": {"begin": "1985"},
                  "tags": [{"name": "alternative rock", "count": 10}]
                },
                {
                  "id": "art2",
                  "name": "Radiohead Tribute Band",
                  "score": 60,
                  "type": "Group",
                  "country": "US"
                }
              ]
            }
        """.trimIndent()
    }
}
