package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarAlbumsProviderTest {

    private val httpClient = FakeHttpClient()
    private val api = DeezerApi(httpClient, RateLimiter(0))
    private val provider = SimilarAlbumsProvider(api)

    @Test
    fun `enrich returns SimilarAlbums for ForAlbum request`() = runTest {
        // Given — Deezer returns matching artist, 3 related artists, and albums for each
        httpClient.givenJsonResponse("search/artist", ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/1001/albums", MUSE_ALBUMS)
        httpClient.givenJsonResponse("artist/1002/albums", PORTISHEAD_ALBUMS)
        httpClient.givenJsonResponse("artist/1003/albums", SIGUR_ROS_ALBUMS)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then — success with 3 albums (2 from Muse + 1 from Portishead, Sigur Ros is empty)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarAlbums
        assertEquals(3, data.albums.size)
    }

    @Test
    fun `enrich returns albums sorted by score descending with deezerId in identifiers`() = runTest {
        // Given — Deezer returns matching artist and related artists with albums
        httpClient.givenJsonResponse("search/artist", ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/1001/albums", MUSE_ALBUMS)
        httpClient.givenJsonResponse("artist/1002/albums", PORTISHEAD_ALBUMS)
        httpClient.givenJsonResponse("artist/1003/albums", SIGUR_ROS_ALBUMS)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS) as EnrichmentResult.Success
        val albums = (result.data as EnrichmentData.SimilarAlbums).albums

        // Then — Muse (index 0) albums rank above Portishead (index 1), all have deezerId
        assertEquals("Muse", albums[0].artist)
        assertEquals("Portishead", albums.last().artist)
        assertTrue(albums[0].artistMatchScore > albums.last().artistMatchScore)
        assertTrue(albums.all { it.identifiers.extra["deezerId"] != null })
        assertEquals("2001", albums.first { it.artist == "Muse" && it.title == "Origin of Symmetry" }.identifiers.extra["deezerId"])
    }

    @Test
    fun `enrich returns NotFound for ForArtist request`() = runTest {
        // Given — a ForArtist request (SIMILAR_ALBUMS only supports ForAlbum)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When — enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then — NotFound immediately, no HTTP calls made
        assertTrue(result is EnrichmentResult.NotFound)
        assertTrue(httpClient.requestedUrls.isEmpty())
    }

    @Test
    fun `enrich returns NotFound for ForTrack request`() = runTest {
        // Given — a ForTrack request
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When — enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then — NotFound immediately, no HTTP calls made
        assertTrue(result is EnrichmentResult.NotFound)
        assertTrue(httpClient.requestedUrls.isEmpty())
    }

    @Test
    fun `enrich returns NotFound when artist search returns no results`() = runTest {
        // Given — Deezer returns no artist search results
        httpClient.givenJsonResponse("search/artist", """{"data":[]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Nonexistent Artist")

        // When — enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then — NotFound because no artist matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when artist name does not match`() = runTest {
        // Given — Deezer returns a completely different artist
        httpClient.givenJsonResponse("search/artist", """{"data":[{"id":999,"name":"Completely Different Band"}]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then — NotFound because ArtistMatcher.isMatch() rejects the result
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when related artists endpoint returns empty list`() = runTest {
        // Given — Deezer returns matching artist but no related artists
        httpClient.givenJsonResponse("search/artist", ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/399/related", """{"data":[]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then — NotFound because no related artists were returned
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when all related artists return empty album lists`() = runTest {
        // Given — related artists exist but all their album lists are empty
        httpClient.givenJsonResponse("search/artist", ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/1001/albums", """{"data":[]}""")
        httpClient.givenJsonResponse("artist/1002/albums", """{"data":[]}""")
        httpClient.givenJsonResponse("artist/1003/albums", """{"data":[]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then — NotFound because all related artist album lists are empty
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns albums from artists that have albums even when others are empty`() = runTest {
        // Given — only Muse has albums, Portishead and Sigur Ros are empty
        httpClient.givenJsonResponse("search/artist", ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/1001/albums", MUSE_ALBUMS)
        httpClient.givenJsonResponse("artist/1002/albums", """{"data":[]}""")
        httpClient.givenJsonResponse("artist/1003/albums", SIGUR_ROS_ALBUMS)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When — enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then — success with only Muse albums (partial success still returns results)
        assertTrue(result is EnrichmentResult.Success)
        val albums = ((result as EnrichmentResult.Success).data as EnrichmentData.SimilarAlbums).albums
        assertEquals(2, albums.size)
        assertTrue(albums.all { it.artist == "Muse" })
    }

    @Test
    fun `era proximity causes album from lower-ranked artist to outscore album from higher-ranked artist`() = runTest {
        // Given — 5 related artists so position scores are:
        //   index 0 (Muse): 1.0 - (0/5)*0.9 = 1.0
        //   index 1 (Portishead): 1.0 - (1/5)*0.9 = 0.82
        // Seed year 1990; Muse album 1975 (diff=15, era 0.8x) → finalScore 0.80
        //   Portishead album 1993 (diff=3, era 1.2x) → finalScore ~0.984
        // So Portishead album should rank above Muse album despite lower base artist score
        httpClient.givenJsonResponse("search/artist", ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_5)
        httpClient.givenJsonResponse("artist/1001/albums", ERA_MUSE_OLD_ALBUM)   // 1975 album
        httpClient.givenJsonResponse("artist/1002/albums", ERA_PORTISHEAD_CLOSE_ALBUM) // 1993 album
        httpClient.givenJsonResponse("artist/1003/albums", """{"data":[]}""")
        httpClient.givenJsonResponse("artist/1004/albums", """{"data":[]}""")
        httpClient.givenJsonResponse("artist/1005/albums", """{"data":[]}""")
        val request = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(), "Dummy", "Radiohead", year = 1990)

        // When — enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS) as EnrichmentResult.Success
        val albums = (result.data as EnrichmentData.SimilarAlbums).albums

        // Then — Portishead's close-era album ranks above Muse's far-era album
        assertTrue(albums.size >= 2)
        val museAlbum = albums.first { it.artist == "Muse" }
        val portisheadAlbum = albums.first { it.artist == "Portishead" }
        assertTrue(
            "Expected portishead (${portisheadAlbum.artistMatchScore}) > muse (${museAlbum.artistMatchScore})",
            portisheadAlbum.artistMatchScore > museAlbum.artistMatchScore,
        )
    }

    @Test
    fun `enrich skips artist search when deezerId is present in request identifiers`() = runTest {
        // Given — request already has deezerId cached; only related and album endpoints are needed
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/1001/albums", MUSE_ALBUMS)
        httpClient.givenJsonResponse("artist/1002/albums", PORTISHEAD_ALBUMS)
        httpClient.givenJsonResponse("artist/1003/albums", SIGUR_ROS_ALBUMS)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead").copy(
            identifiers = EnrichmentIdentifiers().withExtra("deezerId", "399"),
        )

        // When — enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then — success without any search/artist call
        assertTrue(result is EnrichmentResult.Success)
        assertTrue(httpClient.requestedUrls.none { it.contains("search/artist") })
    }

    companion object {
        val ARTIST_SEARCH = """{"data":[{"id":399,"name":"Radiohead"}]}"""

        val RELATED_ARTISTS_3 = """{"data":[
            {"id":1001,"name":"Muse"},
            {"id":1002,"name":"Portishead"},
            {"id":1003,"name":"Sigur Ros"}
        ]}"""

        val RELATED_ARTISTS_5 = """{"data":[
            {"id":1001,"name":"Muse"},
            {"id":1002,"name":"Portishead"},
            {"id":1003,"name":"Sigur Ros"},
            {"id":1004,"name":"Massive Attack"},
            {"id":1005,"name":"Bjork"}
        ]}"""

        val MUSE_ALBUMS = """{"data":[
            {"id":2001,"title":"Origin of Symmetry","release_date":"2001-07-17","record_type":"album","cover_small":null,"cover_medium":"https://img.dz/muse_oos.jpg"},
            {"id":2002,"title":"Absolution","release_date":"2003-09-15","record_type":"album","cover_small":null,"cover_medium":"https://img.dz/muse_abs.jpg"}
        ]}"""

        val PORTISHEAD_ALBUMS = """{"data":[
            {"id":3001,"title":"Dummy","release_date":"1994-08-22","record_type":"album","cover_small":null,"cover_medium":null}
        ]}"""

        val SIGUR_ROS_ALBUMS = """{"data":[]}"""

        // Era test fixtures: Muse album from 1975 (far from 1990 seed), Portishead album from 1993 (close to 1990 seed)
        val ERA_MUSE_OLD_ALBUM = """{"data":[
            {"id":4001,"title":"Old Muse Album","release_date":"1975-01-01","record_type":"album","cover_small":null,"cover_medium":null}
        ]}"""

        val ERA_PORTISHEAD_CLOSE_ALBUM = """{"data":[
            {"id":4002,"title":"Close Era Album","release_date":"1993-01-01","record_type":"album","cover_small":null,"cover_medium":null}
        ]}"""
    }
}
