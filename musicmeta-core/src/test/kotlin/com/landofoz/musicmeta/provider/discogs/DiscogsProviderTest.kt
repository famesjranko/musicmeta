package com.landofoz.musicmeta.provider.discogs

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiscogsProviderTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: DiscogsProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = DiscogsProvider(
            personalToken = "test-token",
            httpClient = httpClient,
            rateLimiter = RateLimiter(0L),
        )
    }

    @Test
    fun `enrich returns album art from search`() = runTest {
        // Given
        httpClient.givenJsonResponse("discogs.com", SEARCH_RESULTS_JSON)
        val request = EnrichmentRequest.forAlbum(
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val data = success.data
        assertTrue(data is EnrichmentData.Artwork)
        assertEquals(
            "https://img.discogs.com/cover.jpg",
            (data as EnrichmentData.Artwork).url,
        )
        assertEquals(0.6f, success.confidence)
    }

    @Test
    fun `enrich returns label from search`() = runTest {
        // Given
        httpClient.givenJsonResponse("discogs.com", SEARCH_RESULTS_JSON)
        val request = EnrichmentRequest.forAlbum(
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.LABEL)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        assertTrue(data is EnrichmentData.Metadata)
        assertEquals("Parlophone", (data as EnrichmentData.Metadata).label)
    }

    @Test
    fun `enrich returns NotFound when no results`() = runTest {
        // Given
        httpClient.givenJsonResponse("discogs.com", EMPTY_RESULTS_JSON)
        val request = EnrichmentRequest.forAlbum(
            title = "Nonexistent Album",
            artist = "Unknown",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for artist requests`() = runTest {
        // Given
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when API key is blank`() = runTest {
        // Given
        val blankProvider = DiscogsProvider(
            personalToken = "",
            httpClient = httpClient,
            rateLimiter = RateLimiter(0L),
        )
        val request = EnrichmentRequest.forAlbum(
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When
        val result = blankProvider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when results field is missing from JSON`() = runTest {
        // Given — Discogs API returns JSON without a "results" array
        httpClient.givenJsonResponse("discogs.com", """{"pagination":{"pages":0}}""")
        val request = EnrichmentRequest.forAlbum(
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because optJSONArray("results") returns null
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when first result has no cover_image field`() = runTest {
        // Given — Discogs result object is missing the cover_image field
        httpClient.givenJsonResponse("discogs.com", """{
            "results": [{
                "title": "Radiohead - OK Computer",
                "label": ["Parlophone"],
                "year": "1997"
            }]
        }""")
        val request = EnrichmentRequest.forAlbum(
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because coverImage is null after takeIf check
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for label when label array is empty`() = runTest {
        // Given — Discogs result has an empty label array
        httpClient.givenJsonResponse("discogs.com", """{
            "results": [{
                "title": "Radiohead - OK Computer",
                "label": [],
                "year": "1997",
                "cover_image": "https://img.discogs.com/cover.jpg"
            }]
        }""")
        val request = EnrichmentRequest.forAlbum(
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When — enriching for label metadata
        val result = provider.enrich(request, EnrichmentType.LABEL)

        // Then — NotFound because label is null when array is empty
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns BandMembers for artist`() = runTest {
        // Given — Discogs returns artist search result and artist detail with members
        httpClient.givenJsonResponse("search?type=artist", ARTIST_SEARCH_JSON)
        httpClient.givenJsonResponse("artists/12345", ARTIST_DETAIL_JSON)
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When — enriching for band members
        val result = provider.enrich(request, EnrichmentType.BAND_MEMBERS)

        // Then — success with 2 band members
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.BandMembers
        assertEquals(2, data.members.size)
        assertEquals("Thom Yorke", data.members[0].name)
        assertEquals("Jonny Greenwood", data.members[1].name)
    }

    @Test
    fun `enrich returns NotFound for BandMembers when artist has no members`() = runTest {
        // Given — Discogs returns artist with empty members list
        httpClient.givenJsonResponse("search?type=artist", ARTIST_SEARCH_JSON)
        httpClient.givenJsonResponse("artists/12345", ARTIST_NO_MEMBERS_JSON)
        val request = EnrichmentRequest.forArtist(name = "Solo Artist")

        // When — enriching for band members
        val result = provider.enrich(request, EnrichmentType.BAND_MEMBERS)

        // Then — NotFound because artist has no members
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for BandMembers when artist search fails`() = runTest {
        // Given — Discogs artist search returns no results
        httpClient.givenJsonResponse("search?type=artist", """{"results":[]}""")
        val request = EnrichmentRequest.forArtist(name = "Unknown Band")

        // When — enriching for band members
        val result = provider.enrich(request, EnrichmentType.BAND_MEMBERS)

        // Then — NotFound because artist was not found
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns album metadata with catalog number, genres, styles`() = runTest {
        // Given — Discogs returns search results with extra metadata fields
        httpClient.givenJsonResponse("discogs.com", METADATA_SEARCH_JSON)
        val request = EnrichmentRequest.forAlbum(
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When — enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then — success with Metadata containing catalogNumber, genres, label, country
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertEquals("NODATA 02", data.catalogNumber)
        assertEquals("Parlophone", data.label)
        assertEquals("UK", data.country)
        assertEquals("1997", data.releaseDate)
        assertTrue(data.genres!!.contains("Electronic"))
        assertTrue(data.genres!!.contains("Rock"))
        assertTrue(data.genres!!.contains("Art Rock"))
    }

    @Test
    fun `enrich returns NotFound for album metadata when no results`() = runTest {
        // Given — Discogs returns empty results
        httpClient.givenJsonResponse("discogs.com", EMPTY_RESULTS_JSON)
        val request = EnrichmentRequest.forAlbum(
            title = "Nonexistent",
            artist = "Nobody",
        )

        // When — enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then — NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Error with ErrorKind NETWORK when network fails`() = runTest {
        // Given — Discogs API throws an IOException
        httpClient.givenIoException("discogs.com")
        val request = EnrichmentRequest.forAlbum(
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — Error with NETWORK kind
        assertTrue(result is EnrichmentResult.Error)
        val error = result as EnrichmentResult.Error
        assertEquals(ErrorKind.NETWORK, error.errorKind)
    }

    @Test
    fun `enrich stores Discogs release and master IDs`() = runTest {
        // Given — search result with id and master_id fields
        httpClient.givenJsonResponse("discogs.com", """{
            "results": [{
                "id": 99001,
                "master_id": 55002,
                "title": "Radiohead - OK Computer",
                "label": ["Parlophone"],
                "year": "1997",
                "country": "UK",
                "cover_image": "https://img.discogs.com/cover.jpg"
            }]
        }""")
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertNotNull(success.resolvedIdentifiers)
        assertEquals("99001", success.resolvedIdentifiers!!.get("discogsReleaseId"))
        assertEquals("55002", success.resolvedIdentifiers!!.get("discogsMasterId"))
    }

    @Test
    fun `enrich stores only release ID when master_id is 0`() = runTest {
        // Given — search result with master_id of 0 (no master)
        httpClient.givenJsonResponse("discogs.com", """{
            "results": [{
                "id": 99001,
                "master_id": 0,
                "title": "Radiohead - OK Computer",
                "label": ["Parlophone"],
                "year": "1997",
                "cover_image": "https://img.discogs.com/cover.jpg"
            }]
        }""")
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("99001", success.resolvedIdentifiers!!.get("discogsReleaseId"))
        assertNull(success.resolvedIdentifiers!!.get("discogsMasterId"))
    }

    @Test
    fun `enrich sets null resolvedIdentifiers when search result has no id`() = runTest {
        // Given — search result without id fields
        httpClient.givenJsonResponse("discogs.com", SEARCH_RESULTS_JSON)
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertNull(success.resolvedIdentifiers)
    }

    // CREDITS capability tests

    @Test
    fun `provider has CREDITS capability at priority 50`() {
        // When
        val capability = provider.capabilities.find { it.type == EnrichmentType.CREDITS }

        // Then
        assertNotNull(capability)
        assertEquals(50, capability!!.priority)
    }

    @Test
    fun `enrich CREDITS returns track-level credits when track title matches`() = runTest {
        // Given — ForTrack with discogsReleaseId, release has track-specific extraartists
        val identifiers = EnrichmentIdentifiers().withExtra("discogsReleaseId", "999")
        val request = EnrichmentRequest.ForTrack(
            identifiers = identifiers,
            title = "Paranoid Android",
            artist = "Radiohead",
        )
        httpClient.givenJsonResponse("releases/999", RELEASE_DETAIL_WITH_TRACK_CREDITS_JSON)

        // When
        val result = provider.enrich(request, EnrichmentType.CREDITS)

        // Then — success with track-level credit (Jane Doe, Vocals)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Credits
        assertEquals(1, data.credits.size)
        assertEquals("Jane Doe", data.credits[0].name)
        assertEquals("Vocals", data.credits[0].role)
        assertEquals("performance", data.credits[0].roleCategory)
    }

    @Test
    fun `enrich CREDITS falls back to release-level credits when no track match`() = runTest {
        // Given — ForTrack title does not match any track in the release
        val identifiers = EnrichmentIdentifiers().withExtra("discogsReleaseId", "999")
        val request = EnrichmentRequest.ForTrack(
            identifiers = identifiers,
            title = "Unknown Track",
            artist = "Radiohead",
        )
        httpClient.givenJsonResponse("releases/999", RELEASE_DETAIL_NO_TRACK_MATCH_JSON)

        // When
        val result = provider.enrich(request, EnrichmentType.CREDITS)

        // Then — success with release-level credit (John Smith, Mixed By)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Credits
        assertEquals(1, data.credits.size)
        assertEquals("John Smith", data.credits[0].name)
        assertEquals("Mixed By", data.credits[0].role)
        assertEquals("production", data.credits[0].roleCategory)
    }

    @Test
    fun `enrich CREDITS returns NotFound when no discogsReleaseId in identifiers`() = runTest {
        // Given — ForTrack with no extra identifiers
        val request = EnrichmentRequest.forTrack(title = "Paranoid Android", artist = "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.CREDITS)

        // Then — NotFound because no discogsReleaseId
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich CREDITS returns Error with NETWORK ErrorKind when IOException thrown`() = runTest {
        // Given — IOException thrown for releases endpoint
        val identifiers = EnrichmentIdentifiers().withExtra("discogsReleaseId", "999")
        val request = EnrichmentRequest.ForTrack(
            identifiers = identifiers,
            title = "Paranoid Android",
            artist = "Radiohead",
        )
        httpClient.givenIoException("releases/")

        // When
        val result = provider.enrich(request, EnrichmentType.CREDITS)

        // Then — Error with NETWORK kind
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    @Test
    fun `enrich CREDITS returns NotFound when release has no credits`() = runTest {
        // Given — release with empty extraartists and tracklist
        val identifiers = EnrichmentIdentifiers().withExtra("discogsReleaseId", "999")
        val request = EnrichmentRequest.ForTrack(
            identifiers = identifiers,
            title = "Paranoid Android",
            artist = "Radiohead",
        )
        httpClient.givenJsonResponse("releases/999", RELEASE_DETAIL_NO_CREDITS_JSON)

        // When
        val result = provider.enrich(request, EnrichmentType.CREDITS)

        // Then — NotFound because credits list is empty
        assertTrue(result is EnrichmentResult.NotFound)
    }

    // RELEASE_EDITIONS capability tests

    @Test
    fun `provider has RELEASE_EDITIONS capability at priority 50`() {
        // When
        val capability = provider.capabilities.find { it.type == EnrichmentType.RELEASE_EDITIONS }

        // Then
        assertNotNull(capability)
        assertEquals(50, capability!!.priority)
    }

    @Test
    fun `enrich RELEASE_EDITIONS returns Success when discogsMasterId is present and has versions`() = runTest {
        // Given — ForAlbum with discogsMasterId, master has versions
        val identifiers = EnrichmentIdentifiers().withExtra("discogsMasterId", "55002")
        val request = EnrichmentRequest.ForAlbum(
            identifiers = identifiers,
            title = "OK Computer",
            artist = "Radiohead",
        )
        httpClient.givenJsonResponse("masters/55002/versions", MASTER_VERSIONS_JSON)

        // When
        val result = provider.enrich(request, EnrichmentType.RELEASE_EDITIONS)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.ReleaseEditions
        assertEquals(2, data.editions.size)
        assertEquals("OK Computer", data.editions[0].title)
        assertEquals("Vinyl, LP", data.editions[0].format)
        assertEquals("UK", data.editions[0].country)
        assertEquals(1997, data.editions[0].year)
        assertEquals("Parlophone", data.editions[0].label)
        assertEquals("NODATA 01", data.editions[0].catalogNumber)
    }

    @Test
    fun `enrich RELEASE_EDITIONS returns NotFound when discogsMasterId is absent`() = runTest {
        // Given — ForAlbum with no discogsMasterId in identifiers
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.RELEASE_EDITIONS)

        // Then — NotFound because no discogsMasterId
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich RELEASE_EDITIONS returns NotFound when getMasterVersions returns empty list`() = runTest {
        // Given — ForAlbum with discogsMasterId, but master has no versions
        val identifiers = EnrichmentIdentifiers().withExtra("discogsMasterId", "55002")
        val request = EnrichmentRequest.ForAlbum(
            identifiers = identifiers,
            title = "OK Computer",
            artist = "Radiohead",
        )
        httpClient.givenJsonResponse("masters/55002/versions", """{"versions":[]}""")

        // When
        val result = provider.enrich(request, EnrichmentType.RELEASE_EDITIONS)

        // Then — NotFound because versions list is empty
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich RELEASE_EDITIONS returns Error with NETWORK ErrorKind on IOException`() = runTest {
        // Given — IOException thrown for masters endpoint
        val identifiers = EnrichmentIdentifiers().withExtra("discogsMasterId", "55002")
        val request = EnrichmentRequest.ForAlbum(
            identifiers = identifiers,
            title = "OK Computer",
            artist = "Radiohead",
        )
        httpClient.givenIoException("masters/")

        // When
        val result = provider.enrich(request, EnrichmentType.RELEASE_EDITIONS)

        // Then — Error with NETWORK kind
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    @Test
    fun `enrich ALBUM_METADATA includes community rating when discogsReleaseId is present`() = runTest {
        // Given — ForAlbum with discogsReleaseId; search result plus release detail with community data
        httpClient.givenJsonResponse("database/search", METADATA_SEARCH_WITH_ID_JSON)
        httpClient.givenJsonResponse("releases/99001", RELEASE_DETAIL_WITH_COMMUNITY_JSON)
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When — enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then — Success with communityRating from release details
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertEquals(4.2f, data.communityRating)
    }

    @Test
    fun `enrich ALBUM_METADATA has null communityRating when no discogsReleaseId`() = runTest {
        // Given — ForAlbum with no release ID in search result
        httpClient.givenJsonResponse("discogs.com", METADATA_SEARCH_JSON)
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When — enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then — Success but communityRating is null since no release detail was fetched
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertNull(data.communityRating)
    }

    @Test
    fun `enrich RELEASE_EDITIONS returns NotFound for non-ForAlbum request`() = runTest {
        // Given — ForTrack request instead of ForAlbum
        val request = EnrichmentRequest.forTrack(title = "Paranoid Android", artist = "Radiohead")

        // When
        val result = provider.enrich(request, EnrichmentType.RELEASE_EDITIONS)

        // Then — NotFound because RELEASE_EDITIONS only handles ForAlbum
        assertTrue(result is EnrichmentResult.NotFound)
    }

    private companion object {
        val METADATA_SEARCH_JSON = """
            {
              "results": [
                {
                  "title": "Radiohead - OK Computer",
                  "label": ["Parlophone"],
                  "year": "1997",
                  "country": "UK",
                  "cover_image": "https://img.discogs.com/cover.jpg",
                  "type": "release",
                  "catno": "NODATA 02",
                  "genre": ["Electronic", "Rock"],
                  "style": ["Art Rock"]
                }
              ]
            }
        """.trimIndent()

        val ARTIST_SEARCH_JSON = """
            {"results":[{"id":12345,"name":"Radiohead"}]}
        """.trimIndent()

        val ARTIST_DETAIL_JSON = """
            {
              "id": 12345,
              "name": "Radiohead",
              "members": [
                {"id": 100, "name": "Thom Yorke", "active": true},
                {"id": 101, "name": "Jonny Greenwood", "active": true}
              ]
            }
        """.trimIndent()

        val ARTIST_NO_MEMBERS_JSON = """
            {"id": 12345, "name": "Solo Artist", "members": []}
        """.trimIndent()

        val SEARCH_RESULTS_JSON = """
            {
              "results": [
                {
                  "title": "Radiohead - OK Computer",
                  "label": ["Parlophone"],
                  "year": "1997",
                  "country": "UK",
                  "cover_image": "https://img.discogs.com/cover.jpg"
                }
              ]
            }
        """.trimIndent()

        val EMPTY_RESULTS_JSON = """
            {
              "results": []
            }
        """.trimIndent()

        val RELEASE_DETAIL_WITH_TRACK_CREDITS_JSON = """
            {
              "id": 999,
              "title": "OK Computer",
              "extraartists": [
                {"name": "John Smith", "role": "Producer", "id": 12345}
              ],
              "tracklist": [
                {
                  "title": "Paranoid Android",
                  "position": "1",
                  "extraartists": [
                    {"name": "Jane Doe", "role": "Vocals", "id": 67890}
                  ]
                },
                {
                  "title": "Subterranean Homesick Alien",
                  "position": "2",
                  "extraartists": []
                }
              ]
            }
        """.trimIndent()

        val RELEASE_DETAIL_NO_TRACK_MATCH_JSON = """
            {
              "id": 999,
              "title": "OK Computer",
              "extraartists": [
                {"name": "John Smith", "role": "Mixed By", "id": 12345}
              ],
              "tracklist": [
                {
                  "title": "Airbag",
                  "position": "1",
                  "extraartists": []
                }
              ]
            }
        """.trimIndent()

        val RELEASE_DETAIL_NO_CREDITS_JSON = """
            {
              "id": 999,
              "title": "OK Computer",
              "extraartists": [],
              "tracklist": []
            }
        """.trimIndent()

        val METADATA_SEARCH_WITH_ID_JSON = """
            {
              "results": [
                {
                  "id": 99001,
                  "master_id": 55002,
                  "title": "Radiohead - OK Computer",
                  "label": ["Parlophone"],
                  "year": "1997",
                  "country": "UK",
                  "cover_image": "https://img.discogs.com/cover.jpg",
                  "type": "release",
                  "catno": "NODATA 02",
                  "genre": ["Electronic", "Rock"],
                  "style": ["Art Rock"]
                }
              ]
            }
        """.trimIndent()

        val RELEASE_DETAIL_WITH_COMMUNITY_JSON = """
            {
              "id": 99001,
              "title": "OK Computer",
              "extraartists": [],
              "tracklist": [],
              "community": {
                "rating": {
                  "average": 4.2,
                  "count": 150
                },
                "have": 5000,
                "want": 1200
              }
            }
        """.trimIndent()

        val MASTER_VERSIONS_JSON = """
            {
              "versions": [
                {
                  "id": 99001,
                  "title": "OK Computer",
                  "format": "Vinyl, LP",
                  "label": "Parlophone",
                  "country": "UK",
                  "year": 1997,
                  "catno": "NODATA 01"
                },
                {
                  "id": 99002,
                  "title": "OK Computer",
                  "format": "CD",
                  "label": "Capitol",
                  "country": "US",
                  "year": 1997,
                  "catno": "7243 8 55229 2 4"
                }
              ]
            }
        """.trimIndent()
    }
}
