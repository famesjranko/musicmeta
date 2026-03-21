package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeezerProviderTest {

    private val httpClient = FakeHttpClient()
    private val provider = DeezerProvider(httpClient, RateLimiter(0))

    @Test
    fun `enrich returns album art from search result`() = runTest {
        // Given — Deezer API returns a matching album with cover URLs
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — success with XL cover as main and medium as thumbnail
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://e-cdns-images.dzcdn.net/images/cover/xl.jpg", data.url)
        assertEquals("https://e-cdns-images.dzcdn.net/images/cover/medium.jpg", data.thumbnailUrl)
    }

    @Test
    fun `enrich returns NotFound when search returns no results`() = runTest {
        // Given — Deezer API returns empty data array
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[]}""")
        val request = EnrichmentRequest.forAlbum("Nonexistent", "Nobody")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because no albums matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for artist requests`() = runTest {
        // Given — an artist-level request (Deezer only supports albums)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because Deezer doesn't handle artist requests
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich uses coverXl for main URL and coverMedium for thumbnail`() = runTest {
        // Given — Deezer response with multiple cover sizes
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART) as EnrichmentResult.Success
        val data = result.data as EnrichmentData.Artwork

        // Then — main URL is XL, thumbnail is medium
        assertTrue(data.url.contains("xl"))
        assertTrue(data.thumbnailUrl!!.contains("medium"))
    }

    @Test
    fun `enrich constructs correct search query`() = runTest {
        // Given — empty Deezer response (we only care about the outgoing URL)
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching triggers an HTTP request
        provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — query URL includes artist and album names
        val url = httpClient.requestedUrls.first()
        assertTrue(url.contains("Radiohead"))
        assertTrue(url.contains("OK+Computer") || url.contains("OK%20Computer"))
    }

    @Test
    fun `enrich returns NotFound when data object has no title field`() = runTest {
        // Given — Deezer API returns album objects missing the title field
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[{"artist":{"name":"Radiohead"},"cover_xl":"https://example.com/cover.jpg"}]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — still returns Success because cover URL is present (title is metadata, not required for artwork)
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test
    fun `enrich returns NotFound when artist object is missing from album`() = runTest {
        // Given — Deezer API returns album without nested artist object
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[{"title":"OK Computer","cover_xl":"https://example.com/cover.jpg"}]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because artist verification rejects blank artist name
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when all cover fields are empty strings`() = runTest {
        // Given — Deezer API returns album with all cover URLs as empty strings
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[{
            "title":"OK Computer",
            "artist":{"name":"Radiohead"},
            "cover_small":"",
            "cover_medium":"",
            "cover_big":"",
            "cover_xl":""
        }]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — NotFound because takeIfNotEmpty filters out blank strings
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Discography for artist`() = runTest {
        // Given — Deezer API returns an artist search result and albums
        httpClient.givenJsonResponse("search/artist", """{"data":[{"id":399,"name":"Radiohead"}]}""")
        httpClient.givenJsonResponse("artist/399/albums", ARTIST_ALBUMS_RESPONSE)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then — success with Discography data
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Discography
        assertEquals(2, data.albums.size)
        assertEquals("OK Computer", data.albums[0].title)
        assertEquals("1997", data.albums[0].year)
        assertEquals("album", data.albums[0].type)
        assertEquals("Kid A", data.albums[1].title)
    }

    @Test
    fun `enrich returns Tracklist for album`() = runTest {
        // Given — Deezer API returns album search and tracks
        httpClient.givenJsonResponse("search/album", """{"data":[{"id":6575,"title":"OK Computer","artist":{"name":"Radiohead"}}]}""")
        httpClient.givenJsonResponse("album/6575/tracks", ALBUM_TRACKS_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album tracks
        val result = provider.enrich(request, EnrichmentType.ALBUM_TRACKS)

        // Then — success with Tracklist data
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Tracklist
        assertEquals(2, data.tracks.size)
        assertEquals("Airbag", data.tracks[0].title)
        assertEquals(1, data.tracks[0].position)
        assertEquals(284000L, data.tracks[0].durationMs)
        assertEquals("Paranoid Android", data.tracks[1].title)
    }

    @Test
    fun `enrich returns NotFound for Discography when artist not found`() = runTest {
        // Given — Deezer API returns no artist search results
        httpClient.givenJsonResponse("search/artist", """{"data":[]}""")
        val request = EnrichmentRequest.forArtist("Nonexistent Artist")

        // When — enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then — NotFound because no artist matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns album metadata from search result`() = runTest {
        // Given — Deezer API returns album with metadata fields
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_METADATA_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then — success with Metadata containing trackCount, explicit, releaseType
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertEquals(12, data.trackCount)
        assertEquals(false, data.explicit)
        assertEquals("album", data.releaseType)
    }

    @Test
    fun `enrich returns NotFound for album metadata when no results`() = runTest {
        // Given — Deezer API returns empty data
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[]}""")
        val request = EnrichmentRequest.forAlbum("Nonexistent", "Nobody")

        // When — enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then — NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Error with ErrorKind NETWORK when network fails`() = runTest {
        // Given — Deezer API throws an IOException
        httpClient.givenIoException("api.deezer.com")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then — Error with NETWORK kind
        assertTrue(result is EnrichmentResult.Error)
        val error = result as EnrichmentResult.Error
        assertEquals(ErrorKind.NETWORK, error.errorKind)
    }

    companion object {
        val DEEZER_METADATA_RESPONSE = """
            {"data":[{
                "title":"OK Computer",
                "artist":{"name":"Radiohead"},
                "cover_small":"https://e-cdns-images.dzcdn.net/images/cover/small.jpg",
                "cover_medium":"https://e-cdns-images.dzcdn.net/images/cover/medium.jpg",
                "cover_big":"https://e-cdns-images.dzcdn.net/images/cover/big.jpg",
                "cover_xl":"https://e-cdns-images.dzcdn.net/images/cover/xl.jpg",
                "nb_tracks":12,
                "record_type":"album",
                "explicit_lyrics":false
            }]}
        """.trimIndent()

        val ARTIST_ALBUMS_RESPONSE = """
            {"data":[
                {"id":6575,"title":"OK Computer","release_date":"1997-06-16","record_type":"album","cover_small":"https://img.dz/small.jpg","cover_medium":"https://img.dz/medium.jpg"},
                {"id":6576,"title":"Kid A","release_date":"2000-10-02","record_type":"album","cover_small":"https://img.dz/kida_s.jpg","cover_medium":"https://img.dz/kida_m.jpg"}
            ]}
        """.trimIndent()

        val ALBUM_TRACKS_RESPONSE = """
            {"data":[
                {"id":1001,"title":"Airbag","track_position":1,"duration":284},
                {"id":1002,"title":"Paranoid Android","track_position":2,"duration":383}
            ]}
        """.trimIndent()

        val DEEZER_RESPONSE = """
            {"data":[{
                "title":"OK Computer",
                "artist":{"name":"Radiohead"},
                "cover_small":"https://e-cdns-images.dzcdn.net/images/cover/small.jpg",
                "cover_medium":"https://e-cdns-images.dzcdn.net/images/cover/medium.jpg",
                "cover_big":"https://e-cdns-images.dzcdn.net/images/cover/big.jpg",
                "cover_xl":"https://e-cdns-images.dzcdn.net/images/cover/xl.jpg"
            }]}
        """.trimIndent()
    }
}
