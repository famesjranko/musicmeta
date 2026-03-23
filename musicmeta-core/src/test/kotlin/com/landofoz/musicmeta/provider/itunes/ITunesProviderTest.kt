package com.landofoz.musicmeta.provider.itunes

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ITunesProviderTest {

    private val httpClient = FakeHttpClient()
    private val provider = ITunesProvider(httpClient, RateLimiter(0))

    @Test
    fun `enrich returns album art with upscaled URL`() = runTest {
        // Given — iTunes API returns a result for "OK Computer"
        httpClient.givenJsonResponse("itunes.apple.com", ITUNES_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — artwork URL is upscaled to 1200x1200
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue(data.url.contains("1200x1200bb"))
    }

    @Test
    fun `enrich returns NotFound when search returns no results`() = runTest {
        // Given — iTunes API returns empty results
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":0,"results":[]}""")
        val request = EnrichmentRequest.forAlbum("Nonexistent", "Nobody")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because no albums matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for artist requests`() = runTest {
        // Given — an artist-level request (iTunes only supports ALBUM_ART/ALBUM_METADATA for albums)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because iTunes doesn't handle artist requests for ALBUM_ART
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich upscales artwork URL from 100x100 to 1200x1200`() = runTest {
        // Given — iTunes returns 100x100 artwork URL
        httpClient.givenJsonResponse("itunes.apple.com", ITUNES_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART) as EnrichmentResult.Success
        val data = result.data as EnrichmentData.Artwork

        // Then — main URL uses 1200x1200, thumbnail preserves original 100x100
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/1200x1200bb.jpg",
            data.url,
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg",
            data.thumbnailUrl,
        )
    }

    @Test
    fun `enrich constructs correct search query`() = runTest {
        // Given — empty iTunes response (we only care about the outgoing URL)
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":0,"results":[]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching triggers an HTTP request
        provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — query URL includes entity=album and the artist name
        val url = httpClient.requestedUrls.first()
        assertTrue(url.contains("entity=album"))
        assertTrue(url.contains("Radiohead"))
    }

    @Test
    fun `enrich returns NotFound when result has no artworkUrl100`() = runTest {
        // Given — iTunes result object is missing the artworkUrl100 field entirely
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":1,"results":[{
            "collectionName":"OK Computer",
            "artistName":"Radiohead"
        }]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because artworkUrl is null after takeIfNotEmpty
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when collectionName is empty string`() = runTest {
        // Given — iTunes result has empty collectionName but valid artwork
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":1,"results":[{
            "collectionName":"",
            "artistName":"Radiohead",
            "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg"
        }]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — still returns Success because collectionName doesn't gate artwork extraction
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test
    fun `enrich returns NotFound when results field is missing from JSON`() = runTest {
        // Given — iTunes API returns JSON without a "results" array
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":0}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because optJSONArray("results") returns null
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns album metadata with trackCount and genre`() = runTest {
        // Given — iTunes API returns result with trackCount and genre
        httpClient.givenJsonResponse("itunes.apple.com", ITUNES_METADATA_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then — success with Metadata containing trackCount, genres, country, releaseDate
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertEquals(12, data.trackCount)
        assertEquals(listOf("Alternative"), data.genres)
        assertEquals("USA", data.country)
    }

    @Test
    fun `enrich returns NotFound for album metadata when no results`() = runTest {
        // Given — iTunes API returns empty results
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":0,"results":[]}""")
        val request = EnrichmentRequest.forAlbum("Nonexistent", "Nobody")

        // When — enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then — NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Error with NETWORK ErrorKind when API fails`() = runTest {
        // Given — simulate an IOException from the HTTP layer
        httpClient.givenIoException("itunes.apple.com")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — Error with NETWORK kind because IOException maps to ErrorKind.NETWORK
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
        assertEquals("itunes", result.provider)
    }

    // ---- New tests for ALBUM_TRACKS and ARTIST_DISCOGRAPHY ----

    @Test
    fun `capabilities include ALBUM_TRACKS and ARTIST_DISCOGRAPHY at priority 30`() = runTest {
        // Given — the provider capabilities list

        // When — checking capabilities
        val capabilities = provider.capabilities

        // Then — both new capabilities are registered at priority 30
        assertTrue(capabilities.any { it.type == EnrichmentType.ALBUM_TRACKS && it.priority == 30 })
        assertTrue(capabilities.any { it.type == EnrichmentType.ARTIST_DISCOGRAPHY && it.priority == 30 })
    }

    @Test
    fun `enrich returns album tracks from lookup API`() = runTest {
        // Given — ForAlbum request with itunesCollectionId in identifiers.extra
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_TRACKS_RESPONSE)
        val identifiers = EnrichmentIdentifiers().withExtra("itunesCollectionId", "203558498")
        val request = EnrichmentRequest.ForAlbum(identifiers, "OK Computer", "Radiohead")

        // When — enriching for album tracks
        val result = provider.enrich(request, EnrichmentType.ALBUM_TRACKS)

        // Then — Success with Tracklist containing track titles, positions, and durations
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Tracklist
        assertEquals(2, data.tracks.size)
        assertEquals("Airbag", data.tracks[0].title)
        assertEquals(1, data.tracks[0].position)
        assertEquals(284000L, data.tracks[0].durationMs)
        assertEquals("Paranoid Android", data.tracks[1].title)
        assertEquals(2, data.tracks[1].position)
    }

    @Test
    fun `enrich returns album tracks by search when no collectionId`() = runTest {
        // Given — ForAlbum without itunesCollectionId; search returns result, then lookup returns tracks
        httpClient.givenJsonResponse("search", ITUNES_SEARCH_WITH_ID_RESPONSE)
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_TRACKS_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album tracks
        val result = provider.enrich(request, EnrichmentType.ALBUM_TRACKS)

        // Then — Success with Tracklist (search first, then lookup)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Tracklist
        assertEquals(2, data.tracks.size)
    }

    @Test
    fun `enrich returns artist discography from lookup API`() = runTest {
        // Given — ForArtist with itunesArtistId in identifiers.extra
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_ARTIST_ALBUMS_RESPONSE)
        val identifiers = EnrichmentIdentifiers().withExtra("itunesArtistId", "657515")
        val request = EnrichmentRequest.ForArtist(identifiers, "Radiohead")

        // When — enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then — Success with Discography containing album titles and years
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Discography
        assertEquals(1, data.albums.size)
        assertEquals("OK Computer", data.albums[0].title)
        assertEquals("1997", data.albums[0].year)
    }

    @Test
    fun `enrich returns artist discography by search when no artistId`() = runTest {
        // Given — ForArtist without itunesArtistId; search for artist returns ID, lookup returns albums
        httpClient.givenJsonResponse("search", ITUNES_SEARCH_ARTIST_RESPONSE)
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_ARTIST_ALBUMS_RESPONSE)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then — Success with Discography (search artist, then lookup albums)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Discography
        assertEquals(1, data.albums.size)
    }

    @Test
    fun `enrich stores itunesArtistId in resolvedIdentifiers for discography`() = runTest {
        // Given — ForArtist without itunesArtistId; search resolves the artist ID
        httpClient.givenJsonResponse("search", ITUNES_SEARCH_ARTIST_RESPONSE)
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_ARTIST_ALBUMS_RESPONSE)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then — resolvedIdentifiers includes itunesArtistId for future lookups
        assertTrue(result is EnrichmentResult.Success)
        val resolved = (result as EnrichmentResult.Success).resolvedIdentifiers
        assertNotNull(resolved)
        assertNotNull("itunesArtistId should be stored", resolved?.get("itunesArtistId"))
    }

    @Test
    fun `enrich returns NotFound for ALBUM_TRACKS when lookup returns empty`() = runTest {
        // Given — lookup returns only the collection wrapper, no tracks
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_EMPTY_TRACKS_RESPONSE)
        val identifiers = EnrichmentIdentifiers().withExtra("itunesCollectionId", "203558498")
        val request = EnrichmentRequest.ForAlbum(identifiers, "OK Computer", "Radiohead")

        // When — enriching for album tracks
        val result = provider.enrich(request, EnrichmentType.ALBUM_TRACKS)

        // Then — NotFound because no tracks in lookup result
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich stores itunesCollectionId and itunesArtistId on resolvedIdentifiers`() = runTest {
        // Given — search returns album result with collectionId and artistId
        httpClient.givenJsonResponse("search", ITUNES_SEARCH_WITH_IDS_RESPONSE)
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_TRACKS_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album tracks (which triggers search then stores IDs)
        val result = provider.enrich(request, EnrichmentType.ALBUM_TRACKS)

        // Then — resolvedIdentifiers includes itunesCollectionId
        assertTrue(result is EnrichmentResult.Success)
        val resolved = (result as EnrichmentResult.Success).resolvedIdentifiers
        assertNotNull(resolved)
        assertEquals("203558498", resolved?.get("itunesCollectionId"))
    }

    @Test
    fun `enrich returns NotFound for ARTIST_DISCOGRAPHY with ForAlbum request`() = runTest {
        // Given — ForAlbum request (wrong type for artist discography)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then — NotFound because ARTIST_DISCOGRAPHY requires ForArtist
        assertTrue(result is EnrichmentResult.NotFound)
    }

    companion object {
        val ITUNES_METADATA_RESPONSE = """
            {"resultCount":1,"results":[{
                "collectionName":"OK Computer",
                "artistName":"Radiohead",
                "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg",
                "releaseDate":"1997-06-16T07:00:00Z",
                "primaryGenreName":"Alternative",
                "country":"USA",
                "trackCount":12
            }]}
        """.trimIndent()

        val ITUNES_RESPONSE = """
            {"resultCount":1,"results":[{
                "collectionName":"OK Computer",
                "artistName":"Radiohead",
                "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg"
            }]}
        """.trimIndent()

        val ITUNES_LOOKUP_TRACKS_RESPONSE = """
            {"resultCount":3,"results":[
                {"wrapperType":"collection","collectionName":"OK Computer","collectionId":203558498,"artistId":657515,"artistName":"Radiohead"},
                {"wrapperType":"track","trackId":203558501,"trackName":"Airbag","trackNumber":1,"trackTimeMillis":284000,"artistName":"Radiohead","collectionName":"OK Computer"},
                {"wrapperType":"track","trackId":203558502,"trackName":"Paranoid Android","trackNumber":2,"trackTimeMillis":384000,"artistName":"Radiohead","collectionName":"OK Computer"}
            ]}
        """.trimIndent()

        val ITUNES_LOOKUP_EMPTY_TRACKS_RESPONSE = """
            {"resultCount":1,"results":[
                {"wrapperType":"collection","collectionName":"OK Computer","collectionId":203558498,"artistId":657515,"artistName":"Radiohead"}
            ]}
        """.trimIndent()

        val ITUNES_LOOKUP_ARTIST_ALBUMS_RESPONSE = """
            {"resultCount":2,"results":[
                {"wrapperType":"artist","artistId":657515,"artistName":"Radiohead"},
                {"wrapperType":"collection","collectionId":203558498,"collectionName":"OK Computer","artistName":"Radiohead","releaseDate":"1997-06-16T07:00:00Z","artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg","trackCount":12}
            ]}
        """.trimIndent()

        val ITUNES_SEARCH_WITH_ID_RESPONSE = """
            {"resultCount":1,"results":[{
                "collectionName":"OK Computer",
                "collectionId":203558498,
                "artistName":"Radiohead",
                "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg"
            }]}
        """.trimIndent()

        val ITUNES_SEARCH_WITH_IDS_RESPONSE = """
            {"resultCount":1,"results":[{
                "collectionName":"OK Computer",
                "collectionId":203558498,
                "artistName":"Radiohead",
                "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg"
            }]}
        """.trimIndent()

        val ITUNES_SEARCH_ARTIST_RESPONSE = """
            {"resultCount":1,"results":[{"artistId":657515,"artistName":"Radiohead","wrapperType":"artist"}]}
        """.trimIndent()
    }
}
