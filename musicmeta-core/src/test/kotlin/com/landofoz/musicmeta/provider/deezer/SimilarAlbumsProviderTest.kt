package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
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
        // Given - Deezer returns matching artist, 3 related artists, and albums for each
        httpClient.givenJsonResponse("search/artist", ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/1001/albums", MUSE_ALBUMS)
        httpClient.givenJsonResponse("artist/1002/albums", PORTISHEAD_ALBUMS)
        httpClient.givenJsonResponse("artist/1003/albums", SIGUR_ROS_ALBUMS)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then - success with 3 albums (2 from Muse + 1 from Portishead, Sigur Ros is empty)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarAlbums
        assertEquals(3, data.albums.size)
    }

    @Test
    fun `enrich returns albums sorted by score descending with deezerId in identifiers`() = runTest {
        // Given - Deezer returns matching artist and related artists with albums
        httpClient.givenJsonResponse("search/artist", ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/1001/albums", MUSE_ALBUMS)
        httpClient.givenJsonResponse("artist/1002/albums", PORTISHEAD_ALBUMS)
        httpClient.givenJsonResponse("artist/1003/albums", SIGUR_ROS_ALBUMS)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS) as EnrichmentResult.Success
        val albums = (result.data as EnrichmentData.SimilarAlbums).albums

        // Then - Muse (index 0) albums rank above Portishead (index 1), all have deezerId
        assertEquals("Muse", albums[0].artist)
        assertEquals("Portishead", albums.last().artist)
        assertTrue(albums[0].artistMatchScore > albums.last().artistMatchScore)
        assertTrue(albums.all { it.identifiers.extra["deezerId"] != null })
        assertEquals("2001", albums.first { it.artist == "Muse" && it.title == "Origin of Symmetry" }.identifiers.extra["deezerId"])
    }

    @Test
    fun `enrich returns NotFound for ForArtist request`() = runTest {
        // Given - a ForArtist request (SIMILAR_ALBUMS only supports ForAlbum)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then - NotFound immediately, no HTTP calls made
        assertTrue(result is EnrichmentResult.NotFound)
        assertTrue(httpClient.requestedUrls.isEmpty())
    }

    @Test
    fun `enrich returns NotFound for ForTrack request`() = runTest {
        // Given - a ForTrack request
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When - enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then - NotFound immediately, no HTTP calls made
        assertTrue(result is EnrichmentResult.NotFound)
        assertTrue(httpClient.requestedUrls.isEmpty())
    }

    @Test
    fun `enrich returns NotFound when artist search returns no results`() = runTest {
        // Given - Deezer returns no artist search results
        httpClient.givenJsonResponse("search/artist", """{"data":[]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Nonexistent Artist")

        // When - enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then - NotFound because no artist matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when artist name does not match`() = runTest {
        // Given - Deezer returns a completely different artist, whose own related artists and
        // albums are all present, so the name is the only thing that can reject this
        httpClient.givenJsonResponse("search/artist", """{"data":[{"id":999,"name":"Completely Different Band"}]}""")
        httpClient.givenJsonResponse("artist/999/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/1001/albums", MUSE_ALBUMS)
        httpClient.givenJsonResponse("artist/1002/albums", PORTISHEAD_ALBUMS)
        httpClient.givenJsonResponse("artist/1003/albums", SIGUR_ROS_ALBUMS)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then - NotFound because no name form of the requested artist matches the seed
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when related artists endpoint returns empty list`() = runTest {
        // Given - Deezer returns matching artist but no related artists
        httpClient.givenJsonResponse("search/artist", ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/399/related", """{"data":[]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then - NotFound because no related artists were returned
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when all related artists return empty album lists`() = runTest {
        // Given - related artists exist but all their album lists are empty
        httpClient.givenJsonResponse("search/artist", ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/1001/albums", """{"data":[]}""")
        httpClient.givenJsonResponse("artist/1002/albums", """{"data":[]}""")
        httpClient.givenJsonResponse("artist/1003/albums", """{"data":[]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then - NotFound because all related artist album lists are empty
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns albums from artists that have albums even when others are empty`() = runTest {
        // Given - only Muse has albums, Portishead and Sigur Ros are empty
        httpClient.givenJsonResponse("search/artist", ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/1001/albums", MUSE_ALBUMS)
        httpClient.givenJsonResponse("artist/1002/albums", """{"data":[]}""")
        httpClient.givenJsonResponse("artist/1003/albums", SIGUR_ROS_ALBUMS)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then - success with only Muse albums (partial success still returns results)
        assertTrue(result is EnrichmentResult.Success)
        val albums = ((result as EnrichmentResult.Success).data as EnrichmentData.SimilarAlbums).albums
        assertEquals(2, albums.size)
        assertTrue(albums.all { it.artist == "Muse" })
    }

    @Test
    fun `era proximity causes album from lower-ranked artist to outscore album from higher-ranked artist`() = runTest {
        // Given - 5 related artists so position scores are:
        //   index 0 (Muse): 1.0 - (0/5)*0.9 = 1.0
        //   index 1 (Portishead): 1.0 - (1/5)*0.9 = 0.82
        // Seed year 1990; Muse album 1975 (diff=15, era 0.8x) → finalScore 0.80
        //   Portishead album 1993 (diff=3, era 1.2x) → finalScore ~0.984
        // So Portishead album should rank above Muse album despite lower base artist score
        httpClient.givenJsonResponse("search/artist", ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_5)
        httpClient.givenJsonResponse("artist/1001/albums", ERA_MUSE_OLD_ALBUM) // 1975 album
        httpClient.givenJsonResponse("artist/1002/albums", ERA_PORTISHEAD_CLOSE_ALBUM) // 1993 album
        httpClient.givenJsonResponse("artist/1003/albums", """{"data":[]}""")
        httpClient.givenJsonResponse("artist/1004/albums", """{"data":[]}""")
        httpClient.givenJsonResponse("artist/1005/albums", """{"data":[]}""")
        val request = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(), "Dummy", "Radiohead", year = 1990)

        // When - enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS) as EnrichmentResult.Success
        val albums = (result.data as EnrichmentData.SimilarAlbums).albums

        // Then - Portishead's close-era album ranks above Muse's far-era album
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
        // Given - request already has deezerId cached; only related and album endpoints are needed
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/1001/albums", MUSE_ALBUMS)
        httpClient.givenJsonResponse("artist/1002/albums", PORTISHEAD_ALBUMS)
        httpClient.givenJsonResponse("artist/1003/albums", SIGUR_ROS_ALBUMS)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead").copy(
            identifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, "399"),
        )

        // When - enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then - success without any search/artist call
        assertTrue(result is EnrichmentResult.Success)
        assertTrue(httpClient.requestedUrls.none { it.contains("search/artist") })
    }

    @Test
    fun `enrich seeds from the album search hit rather than the most popular homonym`() = runTest {
        // Given - four Deezer artists are named Trouble, and the album the request names belongs to
        // the doom band, which has a twenty-fifth of the rapper's fans
        httpClient.givenJsonResponse("search/album", TROUBLE_ALBUM_SEARCH)
        httpClient.givenJsonResponse("search/artist", TROUBLE_ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/10443928/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/166426/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/1001/albums", MUSE_ALBUMS)
        httpClient.givenJsonResponse("artist/1002/albums", PORTISHEAD_ALBUMS)
        httpClient.givenJsonResponse("artist/1003/albums", SIGUR_ROS_ALBUMS)
        val request = EnrichmentRequest.forAlbum("Psalm 9", "Trouble")

        // When - enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then - the seed is the album's own artist, and no name search is made at all
        assertTrue(result is EnrichmentResult.Success)
        val identifiers = (result as EnrichmentResult.Success).resolvedIdentifiers
        assertEquals("10443928", identifiers?.get(IdentifierNamespace.DEEZER))
        assertTrue(httpClient.requestedUrls.none { it.contains("artist/166426/") })
        assertTrue(httpClient.requestedUrls.none { it.contains("search/artist") })
    }

    @Test
    fun `enrich refuses a same-titled album by a different act as the seed`() = runTest {
        // Given - the album pool ranks a same-titled album by Trouble Andrew above the remastered
        // one by Trouble, because an exact title outranks an edition title before artist quality
        httpClient.givenJsonResponse("search/album", TROUBLE_ALBUM_SEARCH_WRONG_ACT_FIRST)
        httpClient.givenJsonResponse("search/artist", TROUBLE_ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/166426/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/215177/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/1001/albums", MUSE_ALBUMS)
        httpClient.givenJsonResponse("artist/1002/albums", PORTISHEAD_ALBUMS)
        httpClient.givenJsonResponse("artist/1003/albums", SIGUR_ROS_ALBUMS)
        val request = EnrichmentRequest.forAlbum("Psalm 9", "Trouble")

        // When - enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then - the plausibly-named act is not the seed, and the name search decides instead
        assertTrue(httpClient.requestedUrls.none { it.contains("artist/215177/") })
        assertTrue(httpClient.requestedUrls.any { it.contains("search/artist") })
    }

    @Test
    fun `enrich returns NotFound when no album hit resolves a name two comparable acts share`() = runTest {
        // Given - Deezer has no album under this title, and the two Sungazers are within a fifth of
        // each other's audience, so only the ambiguity of the name itself can reject the seed
        httpClient.givenJsonResponse("search/album", """{"data":[]}""")
        httpClient.givenJsonResponse("search/artist", SUNGAZER_ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/15283019/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/152886542/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/1001/albums", MUSE_ALBUMS)
        httpClient.givenJsonResponse("artist/1002/albums", PORTISHEAD_ALBUMS)
        httpClient.givenJsonResponse("artist/1003/albums", SIGUR_ROS_ALBUMS)
        val request = EnrichmentRequest.forAlbum("A Title Deezer Does Not Carry", "Sungazer")

        // When - enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then - NotFound rather than one act's list reported as the other's
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich seeds by name when the only same-name rival is a fraction of the artist's size`() = runTest {
        // Given - Radiohead's own pool, which carries "Radio Head" (436 fans) beside the real
        // artist (4,085,364), and no album hit to resolve the name with
        httpClient.givenJsonResponse("search/album", """{"data":[]}""")
        httpClient.givenJsonResponse("search/artist", RADIOHEAD_ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_3)
        httpClient.givenJsonResponse("artist/1001/albums", MUSE_ALBUMS)
        httpClient.givenJsonResponse("artist/1002/albums", PORTISHEAD_ALBUMS)
        httpClient.givenJsonResponse("artist/1003/albums", SIGUR_ROS_ALBUMS)
        val request = EnrichmentRequest.forAlbum("Kid A Mnesia", "Radiohead")

        // When - enriching for similar albums
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ALBUMS)

        // Then - the list is served, because a mis-spaced entry that size is not a second act
        assertTrue(result is EnrichmentResult.Success)
        val identifiers = (result as EnrichmentResult.Success).resolvedIdentifiers
        assertEquals("399", identifiers?.get(IdentifierNamespace.DEEZER))
    }

    companion object {
        val ARTIST_SEARCH = """{"data":[{"id":399,"name":"Radiohead"}]}"""

        // Live `/search/artist?q=Trouble&limit=10`, 2026-09-06, trimmed to the fields the parser
        // reads: four artists are exactly "Trouble" and the fan tiebreak hands the name to 166426,
        // the Atlanta rapper. 10443928 is the doom band that recorded Psalm 9.
        const val TROUBLE_ARTIST_SEARCH = """{"data":[
            {"id":72483672,"name":"Trouble","nb_album":2,"nb_fan":69},
            {"id":281886551,"name":"Trouble","nb_album":2,"nb_fan":13},
            {"id":1072454,"name":"Trouble & Strife","nb_album":1,"nb_fan":16},
            {"id":166426,"name":"Trouble","nb_album":38,"nb_fan":61592},
            {"id":10443928,"name":"Trouble","nb_album":21,"nb_fan":2458},
            {"id":6082420,"name":"Trouble & Daughter","nb_album":1,"nb_fan":126},
            {"id":1096354,"name":"Trouble Maker","nb_album":3,"nb_fan":12221},
            {"id":215177,"name":"Trouble Andrew","nb_album":25,"nb_fan":1629},
            {"id":509537,"name":"Double Trouble","nb_album":120,"nb_fan":28015},
            {"id":1052500,"name":"VINTAGE TROUBLE","nb_album":32,"nb_fan":20729}
        ]}"""

        // Live `/search/album?q=Trouble Psalm 9&limit=5`, 2026-09-06. The only hit carries a
        // remaster suffix, so `deezerAlbumTitleTier`'s EDITION tolerance is what accepts it.
        const val TROUBLE_ALBUM_SEARCH = """{"data":[
            {"id":284590542,"title":"Psalm 9 (Remastered 2020)","nb_tracks":9,"record_type":"album",
             "artist":{"id":10443928,"name":"Trouble"}}
        ]}"""

        // The live Psalm 9 hit above, preceded by a same-titled album attributed to Trouble Andrew
        // — an artist from the live `q=Trouble` pool, but a row Deezer does not carry. The pool is
        // built to exercise the ranking: an exact title beats an edition title before artist
        // quality is read at all, so the loosely-named act wins the selection.
        const val TROUBLE_ALBUM_SEARCH_WRONG_ACT_FIRST = """{"data":[
            {"id":900001,"title":"Psalm 9","nb_tracks":9,"record_type":"album",
             "artist":{"id":215177,"name":"Trouble Andrew"}},
            {"id":284590542,"title":"Psalm 9 (Remastered 2020)","nb_tracks":9,"record_type":"album",
             "artist":{"id":10443928,"name":"Trouble"}}
        ]}"""

        // Live `/search/artist?q=Sungazer&limit=10`, 2026-09-06: two artists are exactly
        // "Sungazer", 2441 fans against 1856, and they are two genuinely different acts.
        const val SUNGAZER_ARTIST_SEARCH = """{"data":[
            {"id":15283019,"name":"Sungazer","nb_album":18,"nb_fan":2441},
            {"id":152886542,"name":"Sungazer","nb_album":8,"nb_fan":1856},
            {"id":7680650,"name":"Sungazers","nb_album":9,"nb_fan":19},
            {"id":144188302,"name":"Sundazer","nb_album":9,"nb_fan":386},
            {"id":9262792,"name":"Sungaze","nb_album":27,"nb_fan":233}
        ]}"""

        // Live `/search/artist?q=Radiohead&limit=10`, 2026-09-06: "Radio Head" normalizes to the
        // requested name once spacing is dropped, and "Radiodread" carries no albums at all.
        // Neither is a second Radiohead.
        const val RADIOHEAD_ARTIST_SEARCH = """{"data":[
            {"id":399,"name":"Radiohead","nb_album":45,"nb_fan":4085364},
            {"id":12189436,"name":"Radio Head","nb_album":7,"nb_fan":436},
            {"id":53477202,"name":"DJ Radiohead","nb_album":30,"nb_fan":63},
            {"id":14009761,"name":"Radiohead Tribute Band","nb_album":1,"nb_fan":414},
            {"id":4674537,"name":"Radiodread","nb_album":0,"nb_fan":24}
        ]}"""

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
