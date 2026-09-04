package com.landofoz.musicmeta.provider.discogs

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.engine.TitleMatcher
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.CancellingOnceHttpClient
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// synthetic: fixtures for /database/search, /artists/{id}, and /releases/{id} — no field-level
// ground truth on file; invented (except where a constant or comment cites a live capture)
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
        // Given - search results containing a cover_image
        httpClient.givenJsonResponse("discogs.com", SEARCH_RESULTS_JSON)
        val request = EnrichmentRequest.forAlbum(
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - success with the Artwork url from cover_image
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val data = success.data
        assertTrue(data is EnrichmentData.Artwork)
        assertEquals(
            "https://img.discogs.com/cover.jpg",
            (data as EnrichmentData.Artwork).url,
        )
        assertEquals(0.8f, success.confidence)
        // A name search leaves its own route unreported: the engine has the canonical-status
        // evidence to tell an exact name match from an unverified guess, not this provider.
        assertEquals(null, success.provenance)
    }

    @Test
    fun `enrich returns album art for a track request naming its album`() = runTest {
        // Given - the same search results, but the request is track-scoped with an album name
        httpClient.givenJsonResponse("discogs.com", SEARCH_RESULTS_JSON)
        val forTrack = EnrichmentRequest.forTrack("Karma Police", "Radiohead", album = "OK Computer")
        val forAlbum = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When - enriching for album art from each request shape
        val trackResult = provider.enrich(forTrack, EnrichmentType.ALBUM_ART) as EnrichmentResult.Success
        val albumResult = provider.enrich(forAlbum, EnrichmentType.ALBUM_ART) as EnrichmentResult.Success

        // Then - same art and the same confidence semantics as the ForAlbum path
        val trackData = trackResult.data as EnrichmentData.Artwork
        val albumData = albumResult.data as EnrichmentData.Artwork
        assertEquals(albumData.url, trackData.url)
        assertEquals(albumResult.confidence, trackResult.confidence)
    }

    @Test
    fun `enrich returns NotFound for a track request with no album`() = runTest {
        // Given - a track request naming no album at all
        httpClient.givenJsonResponse("discogs.com", SEARCH_RESULTS_JSON)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead", album = null)

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because there is no album title to search with
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for label from a track request naming its album`() = runTest {
        // Given - the same search results, but the request is track-scoped with an album name
        httpClient.givenJsonResponse("discogs.com", SEARCH_RESULTS_JSON)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead", album = "OK Computer")

        // When - enriching for label, which stays ForAlbum-only
        val result = provider.enrich(request, EnrichmentType.LABEL)

        // Then - NotFound because only ALBUM_ART widens to accept a track request
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns label from search`() = runTest {
        // Given - search results containing a label
        httpClient.givenJsonResponse("discogs.com", SEARCH_RESULTS_JSON)
        val request = EnrichmentRequest.forAlbum(
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - enriching for label
        val result = provider.enrich(request, EnrichmentType.LABEL)

        // Then - success with the Metadata label
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        assertTrue(data is EnrichmentData.Metadata)
        assertEquals("Parlophone", (data as EnrichmentData.Metadata).label)
    }

    @Test
    fun `enrich returns NotFound when no results`() = runTest {
        // Given - search returns an empty results array
        httpClient.givenJsonResponse("discogs.com", EMPTY_RESULTS_JSON)
        val request = EnrichmentRequest.forAlbum(
            title = "Nonexistent Album",
            artist = "Unknown",
        )

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because there are no results
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for artist requests`() = runTest {
        // Given - a ForArtist-shaped request
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When - enriching for album art, which only ForAlbum supports
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because ALBUM_ART cannot handle an artist request
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when API key is blank`() = runTest {
        // Given - a provider constructed with a blank personal token
        val blankProvider = DiscogsProvider(
            personalToken = "",
            httpClient = httpClient,
            rateLimiter = RateLimiter(0L),
        )
        val request = EnrichmentRequest.forAlbum(
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - enriching for album art
        val result = blankProvider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because the token is blank
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when no search result matches the requested artist`() = runTest {
        // Given - every result is by a different artist
        httpClient.givenJsonResponse("discogs.com", """{
            "results": [{
                "title": "Weird Al Yankovic - OK Computer",
                "label": ["Parlophone"],
                "year": "1997",
                "cover_image": "https://img.discogs.com/wrong.jpg"
            }]
        }""")
        val request = EnrichmentRequest.forAlbum(
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - no fall-through to Discogs' own ranking
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when artist search resolves a different artist`() = runTest {
        // Given - Discogs' per_page=1 artist search returns someone else
        httpClient.givenJsonResponse("search?type=artist", ARTIST_SEARCH_JSON)
        httpClient.givenJsonResponse("artists/12345", ARTIST_DETAIL_JSON)
        val request = EnrichmentRequest.forArtist(name = "Portishead")

        // When - enriching for band members
        val result = provider.enrich(request, EnrichmentType.BAND_MEMBERS)

        // Then - the returned artist's name is verified before use
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when results field is missing from JSON`() = runTest {
        // Given - Discogs API returns JSON without a "results" array
        httpClient.givenJsonResponse("discogs.com", """{"pagination":{"pages":0}}""")
        val request = EnrichmentRequest.forAlbum(
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because optJSONArray("results") returns null
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when first result has no cover_image field`() = runTest {
        // Given - Discogs result object is missing the cover_image field
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

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because coverImage is null after takeIf check
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for label when label array is empty`() = runTest {
        // Given - Discogs result has an empty label array
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

        // When - enriching for label metadata
        val result = provider.enrich(request, EnrichmentType.LABEL)

        // Then - NotFound because label is null when array is empty
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns BandMembers for artist`() = runTest {
        // Given - Discogs returns artist search result and artist detail with members
        httpClient.givenJsonResponse("search?type=artist", ARTIST_SEARCH_JSON)
        httpClient.givenJsonResponse("artists/12345", ARTIST_DETAIL_JSON)
        val request = EnrichmentRequest.forArtist(name = "Radiohead")

        // When - enriching for band members
        val result = provider.enrich(request, EnrichmentType.BAND_MEMBERS)

        // Then - success with 2 band members
        assertTrue(result is EnrichmentResult.Success)
        // and this is not the album search, so success() keeps its fuzzyMatch(false) default
        assertEquals(0.6f, (result as EnrichmentResult.Success).confidence)
        val data = result.data as EnrichmentData.BandMembers
        assertEquals(2, data.members.size)
        assertEquals("Thom Yorke", data.members[0].name)
        assertEquals("Jonny Greenwood", data.members[1].name)
    }

    @Test
    fun `enrich returns NotFound for BandMembers when artist has no members`() = runTest {
        // Given - Discogs returns artist with empty members list
        httpClient.givenJsonResponse("search?type=artist", ARTIST_SEARCH_JSON)
        httpClient.givenJsonResponse("artists/12345", ARTIST_NO_MEMBERS_JSON)
        val request = EnrichmentRequest.forArtist(name = "Solo Artist")

        // When - enriching for band members
        val result = provider.enrich(request, EnrichmentType.BAND_MEMBERS)

        // Then - NotFound because artist has no members
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for BandMembers when artist search fails`() = runTest {
        // Given - Discogs artist search returns no results
        httpClient.givenJsonResponse("search?type=artist", """{"results":[]}""")
        val request = EnrichmentRequest.forArtist(name = "Unknown Band")

        // When - enriching for band members
        val result = provider.enrich(request, EnrichmentType.BAND_MEMBERS)

        // Then - NotFound because artist was not found
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns album metadata with catalog number, genres, styles`() = runTest {
        // Given - Discogs returns search results with extra metadata fields
        httpClient.givenJsonResponse("discogs.com", METADATA_SEARCH_JSON)
        val request = EnrichmentRequest.forAlbum(
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - success with Metadata containing catalogNumber, genres, label, country
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertEquals("NODATA 02", data.catalogNumber)
        assertEquals("Parlophone", data.label)
        // Discogs writes "UK", which is not an ISO code
        assertEquals("GB", data.country)
        assertEquals("1997", data.releaseDate)
        assertTrue(data.genres!!.contains("Electronic"))
        assertTrue(data.genres!!.contains("Rock"))
        assertTrue(data.genres!!.contains("Art Rock"))
    }

    @Test
    fun `enrich returns NotFound for album metadata when no results`() = runTest {
        // Given - Discogs returns empty results
        httpClient.givenJsonResponse("discogs.com", EMPTY_RESULTS_JSON)
        val request = EnrichmentRequest.forAlbum(
            title = "Nonexistent",
            artist = "Nobody",
        )

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Error with ErrorKind NETWORK when network fails`() = runTest {
        // Given - Discogs API throws an IOException
        httpClient.givenIoException("discogs.com")
        val request = EnrichmentRequest.forAlbum(
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - Error with NETWORK kind
        assertTrue(result is EnrichmentResult.Error)
        val error = result as EnrichmentResult.Error
        assertEquals(ErrorKind.NETWORK, error.errorKind)
    }

    @Test
    fun `enrich stores Discogs release and master IDs`() = runTest {
        // Given - search result with id and master_id fields
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

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - success with both discogsReleaseId and discogsMasterId resolved
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertNotNull(success.resolvedIdentifiers)
        assertEquals("99001", success.resolvedIdentifiers!!.get("discogsReleaseId"))
        assertEquals("55002", success.resolvedIdentifiers!!.get("discogsMasterId"))
    }

    @Test
    fun `enrich stores only release ID when master_id is 0`() = runTest {
        // Given - search result with master_id of 0 (no master)
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

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - success with discogsReleaseId set but no discogsMasterId
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("99001", success.resolvedIdentifiers!!.get("discogsReleaseId"))
        assertNull(success.resolvedIdentifiers!!.get("discogsMasterId"))
    }

    @Test
    fun `enrich sets null resolvedIdentifiers when search result has no id`() = runTest {
        // Given - search result without id fields
        httpClient.givenJsonResponse("discogs.com", SEARCH_RESULTS_JSON)
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - resolvedIdentifiers is null since the search result has no id
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertNull(success.resolvedIdentifiers)
    }

    // Album title-acceptance tests

    @Test
    fun `enrich rejects an exact-artist candidate whose title is unrelated, for every album type`() = runTest {
        // Given - the only Discogs hit for "Song" by David Bowie is the live Alabama Song containment control
        httpClient.givenJsonResponse(
            "discogs.com",
            """{
                "results": [{
                    "id": 28234792,
                    "title": "David Bowie - Alabama Song",
                    "label": ["RCA"],
                    "year": "1980",
                    "cover_image": "https://img.discogs.com/alabama.jpg"
                }]
            }""",
        )
        val request = EnrichmentRequest.forAlbum(title = "Song", artist = "David Bowie")

        // When - every shared album type is requested together
        val results = withContext(ProviderCallScope()) {
            listOf(
                provider.enrich(request, EnrichmentType.ALBUM_ART),
                provider.enrich(request, EnrichmentType.LABEL),
                provider.enrich(request, EnrichmentType.RELEASE_TYPE),
                provider.enrich(request, EnrichmentType.ALBUM_METADATA),
            )
        }

        // Then - NotFound for every type, one shared search, and no side fetch for the rejected release
        assertTrue(results.all { it is EnrichmentResult.NotFound })
        assertEquals(1, httpClient.requestedUrls.count { it.contains("database/search") })
        assertTrue(httpClient.requestedUrls.none { it.contains("releases/28234792") })
    }

    @Test
    fun `enrich parses a dash-bearing album title without truncating it`() = runTest {
        // Given - the requested album title itself contains " - ", which must survive the artist/title split intact
        httpClient.givenJsonResponse(
            "discogs.com",
            """{
                "results": [{
                    "id": 1,
                    "title": "Radiohead - Kid A - Special Edition",
                    "label": ["Parlophone"],
                    "year": "2000",
                    "cover_image": "https://img.discogs.com/kid-a.jpg"
                }]
            }""",
        )
        val request = EnrichmentRequest.forAlbum(title = "Kid A - Special Edition", artist = "Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - success, because the parser kept "Kid A - Special Edition" whole rather than splitting at the first dash only
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test
    fun `enrich parses a dash-bearing artist without truncating it to the first plausible prefix`() = runTest {
        // Given - the requested artist itself contains " - ", so the first boundary's short artist-side
        // still passes the loose artist floor by partial match, well short of the real split
        httpClient.givenJsonResponse(
            "discogs.com",
            """{
                "results": [{
                    "id": 4,
                    "title": "Artist - Name - Album",
                    "label": ["A"],
                    "year": "1999",
                    "cover_image": "https://img.discogs.com/artist-name.jpg"
                }]
            }""",
        )
        val request = EnrichmentRequest.forAlbum(title = "Album", artist = "Artist - Name")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - success, because the complete artist/title boundary wins over the first partial one
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test
    fun `enrich does not admit a candidate qualifier the bare request never asked for`() = runTest {
        // Given - the only Discogs hit for a bare "Master Of Puppets" request is a live edition
        httpClient.givenJsonResponse(
            "discogs.com",
            """{
                "results": [{
                    "id": 2,
                    "title": "Metallica - Master Of Puppets (Live)",
                    "label": ["Elektra"],
                    "year": "1986",
                    "cover_image": "https://img.discogs.com/live.jpg"
                }]
            }""",
        )
        val request = EnrichmentRequest.forAlbum(title = "Master Of Puppets", artist = "Metallica")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound, because Discogs has no measured provider-decoration tier for a bare request to tolerate
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich strips a homonym disambiguator from the artist prefix before matching`() = runTest {
        // Given - Discogs' catalogued artist carries a trailing " (2)" homonym counter
        httpClient.givenJsonResponse(
            "discogs.com",
            """{
                "results": [{
                    "id": 3,
                    "title": "Nirvana (2) - Nevermind",
                    "label": ["DGC"],
                    "year": "1991",
                    "cover_image": "https://img.discogs.com/nevermind.jpg"
                }]
            }""",
        )
        val request = EnrichmentRequest.forAlbum(title = "Nevermind", artist = "Nirvana")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - success, because the disambiguator is stripped before the artist and title are compared
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test
    fun `enrich ranks accepted candidates by artist quality instead of taking the first hit`() = runTest {
        // Given - both candidates title-match exactly; the loose "Bad Bunny" match is listed before the exact "Bad Company" match
        httpClient.givenJsonResponse(
            "discogs.com",
            """{
                "results": [
                    {"id": 111, "title": "Bad Bunny - Run With the Pack", "label": ["A"], "cover_image": "https://img.discogs.com/bunny.jpg"},
                    {"id": 222, "title": "Bad Company - Run With the Pack", "label": ["Swan Song"], "cover_image": "https://img.discogs.com/company.jpg"}
                ]
            }""",
        )
        val request = EnrichmentRequest.forAlbum(title = "Run With the Pack", artist = "Bad Company")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - the exact-artist candidate (222) wins, not the first hit (111)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue(data.url.contains("company"))
    }

    @Test
    fun `enrich prefers the release whose year matches the request over an undated tie`() = runTest {
        // Given - two identically-titled, identically-artist-matched releases; only one's year matches the request
        httpClient.givenJsonResponse(
            "discogs.com",
            """{
                "results": [
                    {"id": 1, "title": "Artist - Album", "label": ["A"], "year": "2009", "cover_image": "https://img.discogs.com/2009.jpg"},
                    {"id": 2, "title": "Artist - Album", "label": ["A"], "year": "2015", "cover_image": "https://img.discogs.com/2015.jpg"}
                ]
            }""",
        )
        val request = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(), "Album", "Artist", year = 2015)

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - the release whose year matches the request wins over the first-listed one
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue(data.url.contains("2015"))
    }

    @Test
    fun `enrich searches again when only year differs between two requests in one call`() = runTest {
        // Given - two releases under the same title/artist, distinguished only by year
        httpClient.givenJsonResponse(
            "discogs.com",
            """{
                "results": [
                    {"id": 1, "title": "Artist - Album", "label": ["A"], "year": "2009", "cover_image": "https://img.discogs.com/2009.jpg"},
                    {"id": 2, "title": "Artist - Album", "label": ["A"], "year": "2015", "cover_image": "https://img.discogs.com/2015.jpg"}
                ]
            }""",
        )
        val identifiers = EnrichmentIdentifiers()
        val request2009 = EnrichmentRequest.ForAlbum(identifiers, "Album", "Artist", year = 2009)
        val request2015 = EnrichmentRequest.ForAlbum(identifiers, "Album", "Artist", year = 2015)

        // When - both requests are made together
        val (result2009, result2015) = withContext(ProviderCallScope()) {
            provider.enrich(request2009, EnrichmentType.ALBUM_ART) to
                provider.enrich(request2015, EnrichmentType.ALBUM_ART)
        }

        // Then - each request's own year picks its own release
        val url2009 = ((result2009 as EnrichmentResult.Success).data as EnrichmentData.Artwork).url
        val url2015 = ((result2015 as EnrichmentResult.Success).data as EnrichmentData.Artwork).url
        assertTrue(url2009.contains("2009"))
        assertTrue(url2015.contains("2015"))
    }

    @Test
    fun `enrich retries the release search after a transient failure instead of memoizing it as NotFound`() = runTest {
        // Given - the release search fails transiently on every attempt
        httpClient.givenIoException("discogs.com")
        val request = EnrichmentRequest.forAlbum(title = "Album", artist = "Artist")

        // When - the same request is enriched twice in immediate succession
        val (first, second) = withContext(ProviderCallScope()) {
            provider.enrich(request, EnrichmentType.ALBUM_ART) to
                provider.enrich(request, EnrichmentType.ALBUM_ART)
        }

        // Then - both calls surface the transient failure as Error, so the first failure was never
        // memoized as a false NotFound that the second call could reuse without searching again
        assertTrue(first is EnrichmentResult.Error)
        assertTrue(second is EnrichmentResult.Error)
        assertEquals(2, httpClient.requestedUrls.count { it.contains("database/search") })
    }

    @Test
    fun `enrich shares one release search and selection across ALBUM_ART LABEL RELEASE_TYPE and ALBUM_METADATA`() = runTest {
        // Given - one search hit that every shared album type resolves from, including a "type" field for RELEASE_TYPE
        httpClient.givenJsonResponse("database/search", METADATA_SEARCH_JSON)
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When - all four shared album types are requested together
        val results = withContext(ProviderCallScope()) {
            listOf(
                provider.enrich(request, EnrichmentType.ALBUM_ART),
                provider.enrich(request, EnrichmentType.LABEL),
                provider.enrich(request, EnrichmentType.RELEASE_TYPE),
                provider.enrich(request, EnrichmentType.ALBUM_METADATA),
            )
        }

        // Then - all four succeed, and only one search request was made for the shared selection
        assertTrue(results.all { it is EnrichmentResult.Success })
        assertEquals(1, httpClient.requestedUrls.count { it.contains("database/search") })
    }

    @Test
    fun `enrich resolves the release fresh in each new call`() = runTest {
        // Given - the same search hit stubbed once, for two separate requests to the same release
        httpClient.givenJsonResponse("database/search", SEARCH_RESULTS_JSON)
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When - ALBUM_ART and LABEL resolve as two separate requests, one after the other
        withContext(ProviderCallScope()) {
            provider.enrich(request, EnrichmentType.ALBUM_ART)
            provider.enrich(request, EnrichmentType.LABEL)
        }
        withContext(ProviderCallScope()) {
            provider.enrich(request, EnrichmentType.ALBUM_ART)
            provider.enrich(request, EnrichmentType.LABEL)
        }

        // Then - each of the two requests pays its own search
        assertEquals(2, httpClient.requestedUrls.count { it.contains("database/search") })
    }

    // CREDITS capability tests

    @Test
    fun `provider has CREDITS capability at priority 50`() {
        // Given - the provider's declared capabilities list
        // When - looking up the CREDITS capability
        val capability = provider.capabilities.find { it.type == EnrichmentType.CREDITS }

        // Then - capability exists at priority 50
        assertNotNull(capability)
        assertEquals(50, capability!!.priority)
    }

    @Test
    fun `enrich CREDITS returns track-level credits when track title matches`() = runTest {
        // Given - ForTrack with discogsReleaseId, release has track-specific extraartists
        val identifiers = EnrichmentIdentifiers().withExtra("discogsReleaseId", "999")
        val request = EnrichmentRequest.ForTrack(
            identifiers = identifiers,
            title = "Paranoid Android",
            artist = "Radiohead",
        )
        httpClient.givenJsonResponse("releases/999", RELEASE_DETAIL_WITH_TRACK_CREDITS_JSON)

        // When - enriching for credits
        val result = provider.enrich(request, EnrichmentType.CREDITS)

        // Then - success with track-level credit (Jane Doe, Vocals)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Credits
        assertEquals(1, data.credits.size)
        assertEquals("Jane Doe", data.credits[0].name)
        assertEquals("Vocals", data.credits[0].role)
        assertEquals("performance", data.credits[0].roleCategory)
        // CREDITS has no name-search fallback at all: every Success came from discogsReleaseId.
        assertEquals(LookupProvenance.PROVIDER_NATIVE_ID, (result as EnrichmentResult.Success).provenance)
    }

    @Test
    fun `enrich CREDITS falls back to release-level credits when no track match`() = runTest {
        // Given - ForTrack title does not match any track in the release
        val identifiers = EnrichmentIdentifiers().withExtra("discogsReleaseId", "999")
        val request = EnrichmentRequest.ForTrack(
            identifiers = identifiers,
            title = "Unknown Track",
            artist = "Radiohead",
        )
        httpClient.givenJsonResponse("releases/999", RELEASE_DETAIL_NO_TRACK_MATCH_JSON)

        // When - enriching for credits
        val result = provider.enrich(request, EnrichmentType.CREDITS)

        // Then - success with release-level credit (John Smith, Mixed By)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Credits
        assertEquals(1, data.credits.size)
        assertEquals("John Smith", data.credits[0].name)
        assertEquals("Mixed By", data.credits[0].role)
        assertEquals("production", data.credits[0].roleCategory)
    }

    @Test
    fun `enrich CREDITS returns NotFound when no discogsReleaseId in identifiers`() = runTest {
        // Given - ForTrack with no extra identifiers
        val request = EnrichmentRequest.forTrack(title = "Paranoid Android", artist = "Radiohead")

        // When - enriching for credits
        val result = provider.enrich(request, EnrichmentType.CREDITS)

        // Then - NotFound because no discogsReleaseId
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich CREDITS returns Error with NETWORK ErrorKind when IOException thrown`() = runTest {
        // Given - IOException thrown for releases endpoint
        val identifiers = EnrichmentIdentifiers().withExtra("discogsReleaseId", "999")
        val request = EnrichmentRequest.ForTrack(
            identifiers = identifiers,
            title = "Paranoid Android",
            artist = "Radiohead",
        )
        httpClient.givenIoException("releases/")

        // When - enriching for credits
        val result = provider.enrich(request, EnrichmentType.CREDITS)

        // Then - Error with NETWORK kind
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    @Test
    fun `enrich CREDITS returns NotFound when release has no credits`() = runTest {
        // Given - release with empty extraartists and tracklist
        val identifiers = EnrichmentIdentifiers().withExtra("discogsReleaseId", "999")
        val request = EnrichmentRequest.ForTrack(
            identifiers = identifiers,
            title = "Paranoid Android",
            artist = "Radiohead",
        )
        httpClient.givenJsonResponse("releases/999", RELEASE_DETAIL_NO_CREDITS_JSON)

        // When - enriching for credits
        val result = provider.enrich(request, EnrichmentType.CREDITS)

        // Then - NotFound because credits list is empty
        assertTrue(result is EnrichmentResult.NotFound)
    }

    // RELEASE_EDITIONS capability tests

    @Test
    fun `provider has RELEASE_EDITIONS capability at priority 50`() {
        // Given - the provider's declared capabilities list
        // When - looking up the RELEASE_EDITIONS capability
        val capability = provider.capabilities.find { it.type == EnrichmentType.RELEASE_EDITIONS }

        // Then - capability exists at priority 50
        assertNotNull(capability)
        assertEquals(50, capability!!.priority)
    }

    @Test
    fun `enrich RELEASE_EDITIONS returns Success when discogsMasterId is present and has versions`() = runTest {
        // Given - ForAlbum with discogsMasterId, master has versions
        val identifiers = EnrichmentIdentifiers().withExtra("discogsMasterId", "55002")
        val request = EnrichmentRequest.ForAlbum(
            identifiers = identifiers,
            title = "OK Computer",
            artist = "Radiohead",
        )
        httpClient.givenJsonResponse("masters/55002/versions", MASTER_VERSIONS_JSON)

        // When - enriching for release editions
        val result = provider.enrich(request, EnrichmentType.RELEASE_EDITIONS)

        // Then - success with both versions mapped to editions
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.ReleaseEditions
        assertEquals(2, data.editions.size)
        assertEquals("OK Computer", data.editions[0].title)
        assertEquals("Vinyl, LP", data.editions[0].format)
        assertEquals("GB", data.editions[0].country)
        assertEquals(1997, data.editions[0].year)
        assertEquals("Parlophone", data.editions[0].label)
        assertEquals("NODATA 01", data.editions[0].catalogNumber)
        // RELEASE_EDITIONS has no name-search fallback at all: every Success came from discogsMasterId.
        assertEquals(LookupProvenance.PROVIDER_NATIVE_ID, (result as EnrichmentResult.Success).provenance)
    }

    @Test
    fun `enrich RELEASE_EDITIONS returns NotFound when discogsMasterId is absent`() = runTest {
        // Given - ForAlbum with no discogsMasterId in identifiers
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When - enriching for release editions
        val result = provider.enrich(request, EnrichmentType.RELEASE_EDITIONS)

        // Then - NotFound because no discogsMasterId
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich RELEASE_EDITIONS returns NotFound when getMasterVersions returns empty list`() = runTest {
        // Given - ForAlbum with discogsMasterId, but master has no versions
        val identifiers = EnrichmentIdentifiers().withExtra("discogsMasterId", "55002")
        val request = EnrichmentRequest.ForAlbum(
            identifiers = identifiers,
            title = "OK Computer",
            artist = "Radiohead",
        )
        httpClient.givenJsonResponse("masters/55002/versions", """{"versions":[]}""")

        // When - enriching for release editions
        val result = provider.enrich(request, EnrichmentType.RELEASE_EDITIONS)

        // Then - NotFound because versions list is empty
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich RELEASE_EDITIONS returns Error with NETWORK ErrorKind on IOException`() = runTest {
        // Given - IOException thrown for masters endpoint
        val identifiers = EnrichmentIdentifiers().withExtra("discogsMasterId", "55002")
        val request = EnrichmentRequest.ForAlbum(
            identifiers = identifiers,
            title = "OK Computer",
            artist = "Radiohead",
        )
        httpClient.givenIoException("masters/")

        // When - enriching for release editions
        val result = provider.enrich(request, EnrichmentType.RELEASE_EDITIONS)

        // Then - Error with NETWORK kind
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    @Test
    fun `enrich ALBUM_METADATA includes community rating when discogsReleaseId is present`() = runTest {
        // Given - ForAlbum with discogsReleaseId; search result plus release detail with community data
        httpClient.givenJsonResponse("database/search", METADATA_SEARCH_WITH_ID_JSON)
        httpClient.givenJsonResponse("releases/99001", RELEASE_DETAIL_WITH_COMMUNITY_JSON)
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - Success with communityRating from release details
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertEquals(4.2f, data.communityRating)
    }

    @Test
    fun `enrich ALBUM_METADATA has null communityRating when no discogsReleaseId`() = runTest {
        // Given - ForAlbum with no release ID in search result
        httpClient.givenJsonResponse("discogs.com", METADATA_SEARCH_JSON)
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - Success but communityRating is null since no release detail was fetched
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertNull(data.communityRating)
    }

    /**
     * Pins `degradeSideFetch`'s contract: an optional side fetch degrades to `null` on any
     * transient, so this stays `Success` with `communityRating` absent whichever transient the
     * side fetch hits — which is why swapping the stubbed failure for a rate limit leaves it
     * green. That collapse is `degradeSideFetch`'s own, not this fake's: the exception never
     * reaches `EnrichmentProvider.mapError`, which is where Discogs separates
     * `ErrorKind.RATE_LIMIT` from `NETWORK` on its primary paths — a distinction
     * `ProviderTransientFailureTest` pins for Discogs directly. Separately, and invisible here
     * either way, is how many attempts each kind gets before it surfaces: that's
     * `BudgetedTransientRetry`'s, composed by `DefaultHttpClient`, not this test's bare
     * `FakeHttpClient`.
     */
    @Test
    fun `enrich ALBUM_METADATA degrades to null communityRating when release detail fetch is transient`() = runTest {
        // Given - search succeeds with a discogsReleaseId, but the community-rating side fetch
        // (getReleaseDetails) hits a transient failure after baseMetadata is already built:
        // this must degrade the optional field, not throw away the already-resolved ALBUM_METADATA.
        httpClient.givenJsonResponse("database/search", METADATA_SEARCH_WITH_ID_JSON)
        httpClient.givenError("releases/99001")
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - Success (not Error) with the base metadata intact and communityRating absent
        assertTrue("Expected Success but got $result", result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertNull(data.communityRating)
    }

    @Test
    fun `enrich ALBUM_METADATA still returns Error when the primary search is transient`() = runTest {
        // Given - the primary search call itself fails with a transient. The degrade guard covers
        // only the optional side fetch; the primary lookup must keep surfacing as Error.
        httpClient.givenIoException("database/search")
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - Error with NETWORK kind, not a degraded Success
        assertTrue("Expected Error but got $result", result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    @Test
    fun `enrich RELEASE_EDITIONS returns NotFound for non-ForAlbum request`() = runTest {
        // Given - ForTrack request instead of ForAlbum
        val request = EnrichmentRequest.forTrack(title = "Paranoid Android", artist = "Radiohead")

        // When - enriching for release editions
        val result = provider.enrich(request, EnrichmentType.RELEASE_EDITIONS)

        // Then - NotFound because RELEASE_EDITIONS only handles ForAlbum
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `every request carries the token as a header and none carries it in the URL`() = runTest {
        // Given - responses for all five endpoints the API client calls
        httpClient.givenJsonResponse("search?type=release", SEARCH_RESULTS_JSON)
        httpClient.givenJsonResponse("search?type=artist", ARTIST_SEARCH_JSON)
        httpClient.givenJsonResponse("artists/12345", ARTIST_DETAIL_JSON)
        httpClient.givenJsonResponse("releases/999", RELEASE_DETAIL_WITH_TRACK_CREDITS_JSON)
        httpClient.givenJsonResponse("masters/55002/versions", MASTER_VERSIONS_JSON)

        // When - one request per endpoint
        provider.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead"),
            EnrichmentType.ALBUM_ART,
        )
        provider.enrich(EnrichmentRequest.forArtist("Radiohead"), EnrichmentType.BAND_MEMBERS)
        provider.enrich(
            EnrichmentRequest.ForTrack(
                identifiers = EnrichmentIdentifiers().withExtra("discogsReleaseId", "999"),
                title = "Paranoid Android",
                artist = "Radiohead",
            ),
            EnrichmentType.CREDITS,
        )
        provider.enrich(
            EnrichmentRequest.ForAlbum(
                identifiers = EnrichmentIdentifiers().withExtra("discogsMasterId", "55002"),
                title = "OK Computer",
                artist = "Radiohead",
            ),
            EnrichmentType.RELEASE_EDITIONS,
        )

        // Then - all five endpoints were hit, token in the header and never in the URL
        assertEquals(5, httpClient.requestedUrls.size)
        assertTrue(httpClient.requestedUrls.none { it.contains("token") })
        assertTrue(
            httpClient.requestedHeaders.all { it["Authorization"] == "Discogs token=test-token" },
        )
    }

    @Test
    fun `enrich gives two distinct requests in one call their own album selection`() = runTest {
        // Given - two different album requests, each with its own single-candidate search result
        httpClient.givenJsonResponsesInTurn(
            "database/search",
            """{"results":[{"title":"A|B - C","label":["Label"],"year":"2000","cover_image":"https://example.com/first.jpg"}]}""",
            """{"results":[{"title":"A - B|C","label":["Label"],"year":"2000","cover_image":"https://example.com/second.jpg"}]}""",
        )
        val firstRequest = EnrichmentRequest.forAlbum(title = "C", artist = "A|B")
        val secondRequest = EnrichmentRequest.forAlbum(title = "B|C", artist = "A")

        // When - both requests are resolved together in one enrichment call
        val (firstResult, secondResult) = withContext(ProviderCallScope()) {
            provider.enrich(firstRequest, EnrichmentType.ALBUM_ART) to
                provider.enrich(secondRequest, EnrichmentType.ALBUM_ART)
        }

        // Then - each request searches and selects its own release rather than reusing the other's memoized selection
        val firstUrl = ((firstResult as EnrichmentResult.Success).data as EnrichmentData.Artwork).url
        val secondUrl = ((secondResult as EnrichmentResult.Success).data as EnrichmentData.Artwork).url
        assertEquals("https://example.com/first.jpg", firstUrl)
        assertEquals("https://example.com/second.jpg", secondUrl)
        assertEquals(2, httpClient.requestedUrls.count { it.contains("database/search") })
    }

    @Test
    fun `enrich carries no title or year across two requests that differ only in year`() = runTest {
        // Given - two releases under the same combined title, one per requested year
        httpClient.givenJsonResponsesInTurn(
            "database/search",
            """{"results":[{"title":"Artist - Album","label":["Label"],"year":"2009","cover_image":"https://example.com/2009.jpg"}]}""",
            """{"results":[{"title":"Artist - Album","label":["Label"],"year":"2015","cover_image":"https://example.com/2015.jpg"}]}""",
        )
        val request2009 = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(), "Album", "Artist", year = 2009)
        val request2015 = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(), "Album", "Artist", year = 2015)

        // When - both requests are resolved together in one enrichment call
        val (result2009, result2015) = withContext(ProviderCallScope()) {
            provider.enrich(request2009, EnrichmentType.ALBUM_ART) to
                provider.enrich(request2015, EnrichmentType.ALBUM_ART)
        }

        // Then - each request's own year searches afresh rather than reusing the other's selection
        val url2009 = ((result2009 as EnrichmentResult.Success).data as EnrichmentData.Artwork).url
        val url2015 = ((result2015 as EnrichmentResult.Success).data as EnrichmentData.Artwork).url
        assertEquals("https://example.com/2009.jpg", url2009)
        assertEquals("https://example.com/2015.jpg", url2015)
        assertEquals(2, httpClient.requestedUrls.count { it.contains("database/search") })
    }

    @Test
    fun `selectRelease carries the parsed artist, title and named evidence`() = runTest {
        // Given - one release pool with a single accepted candidate, whose year matches the request
        val releases = listOf(
            DiscogsRelease(
                title = "Radiohead - OK Computer",
                label = "Parlophone",
                year = "1997",
                country = "UK",
                coverImage = "https://example.com/cover.jpg",
            ),
        )
        val request = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(), "OK Computer", "Radiohead", year = 1997)

        // When - selecting directly against the release pool
        val choice = releases.selectRelease(request)

        // Then - the parsed artist/title, artist quality, tier, and named year evidence are all observable, not dropped
        assertNotNull(choice)
        assertEquals("Radiohead", choice!!.artist)
        assertEquals("OK Computer", choice.title)
        assertEquals(ArtistMatcher.matchQuality(request.artist, "Radiohead"), choice.artistQuality)
        assertEquals(TitleMatcher.TitleTier.EXACT, choice.tier)
        assertEquals(true, choice.tieBreaks["year"])
    }

    @Test
    fun `selectRelease picks one identified release from a competing pool with decoy dash boundaries`() = runTest {
        // Given - a pool where one combined title carries a decoy " - " inside the album half, one is a wrong album by the right artist, and one is the requested album
        val releases = listOf(
            DiscogsRelease(
                title = "Radiohead - OK - Not This Computer",
                label = "Bootleg",
                year = "1997",
                country = "UK",
                coverImage = "https://example.com/decoy.jpg",
                releaseId = 101L,
            ),
            DiscogsRelease(
                title = "Radiohead - The Bends",
                label = "Parlophone",
                year = "1995",
                country = "UK",
                coverImage = "https://example.com/bends.jpg",
                releaseId = 102L,
            ),
            DiscogsRelease(
                title = "Radiohead - OK Computer",
                label = "Parlophone",
                year = "1997",
                country = "UK",
                coverImage = "https://example.com/ok.jpg",
                releaseId = 103L,
            ),
        )
        val request = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(), "OK Computer", "Radiohead", year = 1997)

        // When - selecting against the competing pool
        val choice = releases.selectRelease(request)

        // Then - the requested release wins by identity, and its parsed artist/title, artist quality, tier, and year evidence all describe that same release
        assertNotNull(choice)
        assertEquals(103L, choice!!.release.releaseId)
        assertEquals("https://example.com/ok.jpg", choice.release.coverImage)
        assertEquals("Radiohead", choice.artist)
        assertEquals("OK Computer", choice.title)
        assertEquals(ArtistMatcher.matchQuality(request.artist, "Radiohead"), choice.artistQuality)
        assertEquals(TitleMatcher.TitleTier.EXACT, choice.tier)
        assertEquals(true, choice.tieBreaks["year"])
    }

    @Test
    fun `parseDiscogsRelease returns no boundary for a title with no dash at all`() = runTest {
        // Given - a combined title carrying no " - " boundary anywhere
        val combined = "SelfTitledAlbumWithNoSeparator"

        // When - parsing against any requested artist and title
        val parsed = parseDiscogsRelease(combined, requestedArtist = "Artist", requestedTitle = "Album")

        // Then - no boundary exists, so parsing reports no match rather than guessing one
        assertNull(parsed)
    }

    @Test
    fun `a cancelled release search is not memoized as a miss`() = runTest {
        // Given - the first release search is cancelled mid-flight and the retry reuses that call's memo; the underlying data would be found on a retry
        val cancelling = CancellingOnceHttpClient(httpClient)
        val cancellingProvider = DiscogsProvider(
            personalToken = "test-token",
            httpClient = cancelling,
            rateLimiter = RateLimiter(0L),
        )
        httpClient.givenJsonResponse("database/search", SEARCH_RESULTS_JSON)
        val request = EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead")
        val scope = ProviderCallScope()

        // When - the first lookup is cancelled, on a job separate from this test's own but sharing this test's scope, then the retry runs in that same scope
        val cancelled = CoroutineScope(Job() + scope).async { cancellingProvider.enrich(request, EnrichmentType.ALBUM_ART) }
        try {
            cancelled.await()
            fail("expected the cancellation to propagate")
        } catch (_: CancellationException) {
            // expected
        }
        val result = withContext(scope) { cancellingProvider.enrich(request, EnrichmentType.ALBUM_ART) }

        // Then - the retry performs a fresh search rather than reading the cancelled attempt's memoized miss
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test
    fun `enrich does not select a materially different release when year is unknown`() = runTest {
        // Given - the request supplies no year, and the pool has two equally-artist-matched, equally-titled releases
        httpClient.givenJsonResponse(
            "discogs.com",
            """{
                "results": [
                    {"id": 1, "title": "Artist - Album", "label": ["A"], "year": "2009", "cover_image": "https://img.discogs.com/2009.jpg"},
                    {"id": 2, "title": "Artist - Album", "label": ["A"], "year": "2015", "cover_image": "https://img.discogs.com/2015.jpg"}
                ]
            }""",
        )
        val request = EnrichmentRequest.forAlbum(title = "Album", artist = "Artist")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - the year tie-break is equally unknown for both, so provider order settles the tie: the first-listed release wins
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue(data.url.contains("2009"))
    }

    @Test
    fun `release selection is unaffected by the engine's canonical status`() = runTest {
        // Given - one album request, run through the engine under identity resolution disabled, enabled-but-unresolved, resolved, and ambiguous with suggestions
        val suggestions = listOf(
            SearchCandidate(
                "OK Computer", "Radiohead", "1997", "GB", "Album", 80, null,
                EnrichmentIdentifiers(musicBrainzId = "mbid-suggestion"), "mb",
            ),
        )
        val configs = listOf(
            CanonicalStatus.NOT_ATTEMPTED_DISABLED to EnrichmentConfig(enableIdentityResolution = false) to null,
            CanonicalStatus.UNRESOLVED to EnrichmentConfig(enableIdentityResolution = true) to
                EnrichmentResult.NotFound(EnrichmentType.GENRE, "mb"),
            CanonicalStatus.RESOLVED to EnrichmentConfig(enableIdentityResolution = true) to
                EnrichmentResult.Success(
                    EnrichmentType.GENRE, EnrichmentData.Metadata(genres = listOf("rock")), "mb", 0.95f,
                    resolvedIdentifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-resolved"),
                ),
            CanonicalStatus.AMBIGUOUS to EnrichmentConfig(enableIdentityResolution = true) to
                EnrichmentResult.NotFound(EnrichmentType.GENRE, "mb", suggestions = suggestions),
        )

        // When - each configuration enriches the same request through its own engine instance
        val urls = configs.map { (statusAndConfig, identityResult) ->
            val (expectedStatus, config) = statusAndConfig
            val albumHttpClient = FakeHttpClient().apply { givenJsonResponse("database/search", SEARCH_RESULTS_JSON) }
            val discogs = DiscogsProvider(personalToken = "test-token", httpClient = albumHttpClient, rateLimiter = RateLimiter(0L))
            val mb = FakeProvider(
                id = "mb", isIdentityProvider = true,
                capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
            ).also { identityResult?.let(it::givenIdentityResult) }
            val engine = DefaultEnrichmentEngine(ProviderRegistry(listOf(discogs, mb)), FakeEnrichmentCache(), config)
            val results = engine.enrich(EnrichmentRequest.forAlbum(title = "OK Computer", artist = "Radiohead"), setOf(EnrichmentType.ALBUM_ART))
            assertEquals(expectedStatus, results.identity.status)
            ((results.raw.getValue(EnrichmentType.ALBUM_ART) as EnrichmentResult.Success).data as EnrichmentData.Artwork).url
        }

        // Then - every canonical-status configuration selects the same release
        assertEquals(1, urls.toSet().size)
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

        // A Discogs artist search result names the artist in `title`, not `name` — verified
        // against a live api.discogs.com/database/search?type=artist response. This fixture said
        // `name` until the searchArtist name filter was added and started reading the field.
        val ARTIST_SEARCH_JSON = """
            {"results":[{"id":12345,"type":"artist","title":"Radiohead"}]}
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
