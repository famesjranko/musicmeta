package com.landofoz.musicmeta.provider.deezer

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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeezerProviderTest {

    private val httpClient = FakeHttpClient()
    private val provider = DeezerProvider(httpClient, RateLimiter(0))

    @Test
    fun `enrich returns album art from search result`() = runTest {
        // Given - Deezer API returns a matching album with cover URLs
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - success with XL cover as main and medium as thumbnail
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://e-cdns-images.dzcdn.net/images/cover/xl.jpg", data.url)
        assertEquals("https://e-cdns-images.dzcdn.net/images/cover/medium.jpg", data.thumbnailUrl)
    }

    @Test
    fun `enrich returns NotFound when search returns no results`() = runTest {
        // Given - Deezer API returns empty data array
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[]}""")
        val request = EnrichmentRequest.forAlbum("Nonexistent", "Nobody")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because no albums matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for artist requests`() = runTest {
        // Given - an artist-level request (Deezer only supports albums)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because Deezer doesn't handle artist requests
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich uses coverXl for main URL and coverMedium for thumbnail`() = runTest {
        // Given - Deezer response with multiple cover sizes
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART) as EnrichmentResult.Success
        val data = result.data as EnrichmentData.Artwork

        // Then - main URL is XL, thumbnail is medium
        assertTrue(data.url.contains("xl"))
        assertTrue(data.thumbnailUrl!!.contains("medium"))
    }

    @Test
    fun `enrich constructs correct search query`() = runTest {
        // Given - empty Deezer response (we only care about the outgoing URL)
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching triggers an HTTP request
        provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - query URL includes artist and album names
        val url = httpClient.requestedUrls.first()
        assertTrue(url.contains("Radiohead"))
        assertTrue(url.contains("OK+Computer") || url.contains("OK%20Computer"))
    }

    @Test
    fun `enrich returns NotFound for album art when the candidate's title is missing`() = runTest {
        // Given - Deezer API returns an album object missing the title field
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[{"artist":{"name":"Radiohead"},"cover_xl":"https://example.com/cover.jpg"}]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because a blank title can never clear the title-acceptance gate all three album types share
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when artist object is missing from album`() = runTest {
        // Given - Deezer API returns album without nested artist object
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[{"title":"OK Computer","cover_xl":"https://example.com/cover.jpg"}]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because artist verification rejects blank artist name
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when all cover fields are empty strings`() = runTest {
        // Given - Deezer API returns album with all cover URLs as empty strings
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[{
            "title":"OK Computer",
            "artist":{"name":"Radiohead"},
            "cover_small":"",
            "cover_medium":"",
            "cover_big":"",
            "cover_xl":""
        }]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because takeIfNotEmpty filters out blank strings
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Discography for artist`() = runTest {
        // Given - Deezer API returns an artist search result and albums
        httpClient.givenJsonResponse("search/artist", """{"data":[{"id":399,"name":"Radiohead"}]}""")
        httpClient.givenJsonResponse("artist/399/albums", ARTIST_ALBUMS_RESPONSE)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then - success with Discography data
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
        // Given - Deezer API returns album search and tracks
        httpClient.givenJsonResponse("search/album", """{"data":[{"id":6575,"title":"OK Computer","artist":{"name":"Radiohead"}}]}""")
        httpClient.givenJsonResponse("album/6575/tracks", ALBUM_TRACKS_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album tracks
        val result = provider.enrich(request, EnrichmentType.ALBUM_TRACKS)

        // Then - success with Tracklist data
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
        // Given - Deezer API returns no artist search results
        httpClient.givenJsonResponse("search/artist", """{"data":[]}""")
        val request = EnrichmentRequest.forArtist("Nonexistent Artist")

        // When - enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then - NotFound because no artist matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns album metadata from search result and album detail`() = runTest {
        // Given - Deezer search returns the album hit and its /album/{id} detail carries upc/label/release_date
        httpClient.givenJsonResponse("search/album", DEEZER_METADATA_RESPONSE)
        httpClient.givenJsonResponse("album/14879699", DEEZER_ALBUM_DETAIL_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - success with Metadata containing trackCount, explicit, releaseType, barcode, label, releaseDate
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertEquals(12, data.trackCount)
        assertEquals(false, data.explicit)
        assertEquals("album", data.releaseType)
        assertEquals("634904078164", data.barcode)
        assertEquals("XL Recordings", data.label)
        assertEquals("1997-06-17", data.releaseDate)
    }

    @Test
    fun `enrich leaves barcode label and releaseDate absent when album detail lacks them`() = runTest {
        // Given - Deezer search returns an album hit but /album/{id} carries none of upc, label, release_date
        httpClient.givenJsonResponse("search/album", DEEZER_METADATA_RESPONSE)
        httpClient.givenJsonResponse("album/14879699", DEEZER_ALBUM_DETAIL_MISSING_FIELDS_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA) as EnrichmentResult.Success
        val data = result.data as EnrichmentData.Metadata

        // Then - the missing fields are absent, not empty strings
        assertEquals(null, data.barcode)
        assertEquals(null, data.label)
        assertEquals(null, data.releaseDate)
    }

    @Test
    fun `enrich shares one album search across ALBUM_TRACKS, ALBUM_METADATA and ALBUM_ART within one ProviderCallScope`() = runTest {
        // Given - Deezer search and album detail stubbed once each, for one request asking three album types
        httpClient.givenJsonResponse("search/album", DEEZER_METADATA_RESPONSE)
        httpClient.givenJsonResponse("album/14879699/tracks", ALBUM_TRACKS_RESPONSE)
        httpClient.givenJsonResponse("album/14879699", DEEZER_ALBUM_DETAIL_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - ALBUM_TRACKS, ALBUM_METADATA and ALBUM_ART are all requested together
        withContext(ProviderCallScope()) {
            provider.enrich(request, EnrichmentType.ALBUM_TRACKS)
            provider.enrich(request, EnrichmentType.ALBUM_METADATA)
            provider.enrich(request, EnrichmentType.ALBUM_ART)
        }

        // Then - one album search and one album detail fetch serve all three types
        assertEquals(1, httpClient.requestedUrls.count { it.contains("search/album") })
        assertEquals(1, httpClient.requestedUrls.count { it.contains("album/14879699") && !it.contains("tracks") })
    }

    @Test
    fun `enrich resolves the album fresh in each new ProviderCallScope`() = runTest {
        // Given - Deezer search and album detail stubbed once each, for two separate requests to the same album
        httpClient.givenJsonResponse("search/album", DEEZER_METADATA_RESPONSE)
        httpClient.givenJsonResponse("album/14879699/tracks", ALBUM_TRACKS_RESPONSE)
        httpClient.givenJsonResponse("album/14879699", DEEZER_ALBUM_DETAIL_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - ALBUM_TRACKS and ALBUM_METADATA resolve as two separate requests, one after the other
        withContext(ProviderCallScope()) {
            provider.enrich(request, EnrichmentType.ALBUM_TRACKS)
            provider.enrich(request, EnrichmentType.ALBUM_METADATA)
        }
        withContext(ProviderCallScope()) {
            provider.enrich(request, EnrichmentType.ALBUM_TRACKS)
            provider.enrich(request, EnrichmentType.ALBUM_METADATA)
        }

        // Then - each of the two requests pays its own search and detail fetch
        assertEquals(2, httpClient.requestedUrls.count { it.contains("search/album") })
        assertEquals(2, httpClient.requestedUrls.count { it.contains("album/14879699") && !it.contains("tracks") })
    }

    @Test
    fun `enrich reaches upstream again on a forceRefresh-shaped second call outside any scope`() = runTest {
        // Given - Deezer search and album detail stubbed once each, with each call made independently
        httpClient.givenJsonResponse("search/album", DEEZER_METADATA_RESPONSE)
        httpClient.givenJsonResponse("album/14879699", DEEZER_ALBUM_DETAIL_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - ALBUM_METADATA is enriched twice, as forceRefresh calls the provider fresh each time
        provider.enrich(request, EnrichmentType.ALBUM_METADATA)
        provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - both calls reach upstream: two searches and two detail fetches, not one of each
        assertEquals(2, httpClient.requestedUrls.count { it.contains("search/album") })
        assertEquals(2, httpClient.requestedUrls.count { it.contains("album/14879699") })
    }

    @Test
    fun `enrich returns NotFound for album metadata when no results`() = runTest {
        // Given - Deezer API returns empty data
        httpClient.givenJsonResponse("api.deezer.com", """{"data":[]}""")
        val request = EnrichmentRequest.forAlbum("Nonexistent", "Nobody")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Error with ErrorKind NETWORK when network fails`() = runTest {
        // Given - Deezer API throws an IOException
        httpClient.givenIoException("api.deezer.com")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - Error with NETWORK kind
        assertTrue(result is EnrichmentResult.Error)
        val error = result as EnrichmentResult.Error
        assertEquals(ErrorKind.NETWORK, error.errorKind)
    }

    // Album title-acceptance tests

    @Test
    fun `enrich rejects a right-artist candidate whose title is unrelated, for all three album types`() = runTest {
        // Given - the only Deezer hit for "OK Computer" by Radiohead is "Kid A" by the same artist
        httpClient.givenJsonResponse(
            "search/album",
            """{"data":[{"id":1,"title":"Kid A","artist":{"name":"Radiohead"},"cover_xl":"https://example.com/kid-a.jpg","nb_tracks":10}]}""",
        )
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - all three album types are requested together
        val results = withContext(ProviderCallScope()) {
            listOf(
                provider.enrich(request, EnrichmentType.ALBUM_ART),
                provider.enrich(request, EnrichmentType.ALBUM_METADATA),
                provider.enrich(request, EnrichmentType.ALBUM_TRACKS),
            )
        }

        // Then - NotFound for every type, one shared search, and no detail or track fetch for the rejected id
        assertTrue(results.all { it is EnrichmentResult.NotFound })
        assertEquals(1, httpClient.requestedUrls.count { it.contains("search/album") })
        assertTrue(httpClient.requestedUrls.none { it.contains("album/1") })
    }

    @Test
    fun `enrich accepts a bare album request against a provider remaster-only edition`() = runTest {
        // Given - the only Deezer hit for a bare "Hunky Dory" request is the 2015 remaster
        httpClient.givenJsonResponse(
            "search/album",
            """{"data":[{"id":9,"title":"Hunky Dory (2015 Remaster)","artist":{"name":"David Bowie"},"cover_xl":"https://example.com/hunky-dory.jpg"}]}""",
        )
        val request = EnrichmentRequest.forAlbum("Hunky Dory", "David Bowie")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - success, because a bare request tolerates a provider-added remaster suffix
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test
    fun `enrich does not let an unqualified request admit a candidate's Live qualifier`() = runTest {
        // Given - the only Deezer hit for "Delicate Sound of Thunder" is a Live edition
        httpClient.givenJsonResponse(
            "search/album",
            """{"data":[{"id":3,"title":"Delicate Sound of Thunder (Live)","artist":{"name":"Pink Floyd"},"cover_xl":"https://example.com/dsot.jpg"}]}""",
        )
        val request = EnrichmentRequest.forAlbum("Delicate Sound of Thunder", "Pink Floyd")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound, because Live stays identity-bearing rather than provider decoration
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich keeps a caller-requested qualifier distinct from a candidate that lacks it`() = runTest {
        // Given - the caller asked for the Live edition, but the only Deezer hit is the studio album
        httpClient.givenJsonResponse(
            "search/album",
            """{"data":[{"id":4,"title":"Album","artist":{"name":"Artist"},"cover_xl":"https://example.com/album.jpg"}]}""",
        )
        val request = EnrichmentRequest.forAlbum("Album - Live", "Artist")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound, because the studio album is not the requested Live edition
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich ranks accepted candidates by artist quality instead of taking the first hit`() = runTest {
        // Given - both candidates title-match exactly; the loose "Bad Bunny" match is listed before the exact "Bad Company" match
        httpClient.givenJsonResponse(
            "search/album",
            """{"data":[
                {"id":111,"title":"Run With the Pack","artist":{"name":"Bad Bunny"}},
                {"id":222,"title":"Run With the Pack","artist":{"name":"Bad Company"}}
            ]}""",
        )
        httpClient.givenJsonResponse("album/222", """{"id":222,"label":"Swan Song"}""")
        val request = EnrichmentRequest.forAlbum("Run With the Pack", "Bad Company")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - the exact-artist candidate (222) is selected and detailed, not the first hit (111)
        assertTrue(result is EnrichmentResult.Success)
        assertTrue(httpClient.requestedUrls.any { it.contains("album/222") })
        assertTrue(httpClient.requestedUrls.none { it.contains("album/111") })
    }

    @Test
    fun `enrich prefers the edition whose track count matches the request over a larger box set`() = runTest {
        // Given - two identically-titled, identically-qualified editions; the 137-track box is listed first
        httpClient.givenJsonResponse(
            "search/album",
            """{"data":[
                {"id":55,"title":"Master Of Puppets (Remastered)","artist":{"name":"Metallica"},"cover_xl":"https://example.com/box.jpg","nb_tracks":137},
                {"id":56,"title":"Master Of Puppets (Remastered)","artist":{"name":"Metallica"},"cover_xl":"https://example.com/album.jpg","nb_tracks":8}
            ]}""",
        )
        val identifiers = EnrichmentIdentifiers()
        val request = EnrichmentRequest.ForAlbum(identifiers, "Master Of Puppets", "Metallica", trackCount = 8)

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - the 8-track edition's artwork wins, not the first-listed 137-track box
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://example.com/album.jpg", data.url)
    }

    @Test
    fun `enrich does not select a materially different edition when trackCount is unknown`() = runTest {
        // Given - the request supplies no trackCount, and the pool has both a plain album and a 137-track box under the same title
        httpClient.givenJsonResponse(
            "search/album",
            """{"data":[
                {"id":56,"title":"Master Of Puppets (Remastered)","artist":{"name":"Metallica"},"cover_xl":"https://example.com/album.jpg","nb_tracks":8},
                {"id":55,"title":"Master Of Puppets (Remastered)","artist":{"name":"Metallica"},"cover_xl":"https://example.com/box.jpg","nb_tracks":137}
            ]}""",
        )
        val request = EnrichmentRequest.forAlbum("Master Of Puppets", "Metallica")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - missing trackCount is unknown evidence, so provider order settles the tie: the first-listed edition wins
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://example.com/album.jpg", data.url)
    }

    @Test
    fun `enrich ranks title tier ahead of artist quality`() = runTest {
        // Given - both candidates clear the artist floor and the title floor: the accepted-remaster candidate has the exact artist, the exact-title candidate's artist is only a loose containment match
        val request = EnrichmentRequest.forAlbum(title = "Album", artist = "Real Band")
        httpClient.givenJsonResponse(
            "search/album",
            """{"data":[
                {"id":1,"title":"Album (Remastered)","artist":{"name":"Real Band"},"cover_xl":"https://example.com/edition.jpg"},
                {"id":2,"title":"Album","artist":{"name":"Real Band Tribute"},"cover_xl":"https://example.com/exact-title.jpg"}
            ]}""",
        )

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - the exact-title candidate wins even though the accepted-remaster candidate has the exact artist
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://example.com/exact-title.jpg", data.url)
    }

    @Test
    fun `enrich searches again when only trackCount differs between two requests in one ProviderCallScope`() = runTest {
        // Given - two editions under the same title/artist, distinguished only by trackCount
        httpClient.givenJsonResponse(
            "search/album",
            """{"data":[
                {"id":55,"title":"Master Of Puppets (Remastered)","artist":{"name":"Metallica"},"cover_xl":"https://example.com/box.jpg","nb_tracks":137},
                {"id":56,"title":"Master Of Puppets (Remastered)","artist":{"name":"Metallica"},"cover_xl":"https://example.com/album.jpg","nb_tracks":8}
            ]}""",
        )
        val identifiers = EnrichmentIdentifiers()
        val boxRequest = EnrichmentRequest.ForAlbum(identifiers, "Master Of Puppets", "Metallica", trackCount = 137)
        val albumRequest = EnrichmentRequest.ForAlbum(identifiers, "Master Of Puppets", "Metallica", trackCount = 8)

        // When - both requests are made together
        val (boxResult, albumResult) = withContext(ProviderCallScope()) {
            provider.enrich(boxRequest, EnrichmentType.ALBUM_ART) to
                provider.enrich(albumRequest, EnrichmentType.ALBUM_ART)
        }

        // Then - each request's own trackCount picks its own edition
        val boxUrl = ((boxResult as EnrichmentResult.Success).data as EnrichmentData.Artwork).url
        val albumUrl = ((albumResult as EnrichmentResult.Success).data as EnrichmentData.Artwork).url
        assertEquals("https://example.com/box.jpg", boxUrl)
        assertEquals("https://example.com/album.jpg", albumUrl)
    }

    @Test
    fun `enrich retries the album search after a transient failure instead of memoizing it as NotFound`() = runTest {
        // Given - the album search fails transiently on every attempt
        httpClient.givenIoException("search/album")
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
        assertEquals(2, httpClient.requestedUrls.count { it.contains("search/album") })
    }

    // SIMILAR_ARTISTS tests

    @Test
    fun `enrich returns SimilarArtists for artist`() = runTest {
        // Given - Deezer returns a matching artist and related artists
        httpClient.givenJsonResponse("search/artist", """{"data":[{"id":399,"name":"Radiohead"}]}""")
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_RESPONSE)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for similar artists
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ARTISTS)

        // Then - success with SimilarArtists data
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists
        assertEquals(3, data.artists.size)
        assertEquals("Muse", data.artists[0].name)
    }

    @Test
    fun `enrich returns NotFound for SimilarArtists when artist not found`() = runTest {
        // Given - Deezer returns no artist search results
        httpClient.givenJsonResponse("search/artist", """{"data":[]}""")
        val request = EnrichmentRequest.forArtist("Nonexistent Artist")

        // When - enriching for similar artists
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ARTISTS)

        // Then - NotFound because no artist matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for SimilarArtists when artist name does not match`() = runTest {
        // Given - Deezer returns a different artist
        httpClient.givenJsonResponse("search/artist", """{"data":[{"id":999,"name":"Completely Different Band"}]}""")
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for similar artists
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ARTISTS)

        // Then - NotFound because ArtistMatcher.isMatch() rejects the result
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for SimilarArtists on album request`() = runTest {
        // Given - a ForAlbum request (SIMILAR_ARTISTS requires ForArtist)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for similar artists
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ARTISTS)

        // Then - NotFound because SIMILAR_ARTISTS requires ForArtist
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns SimilarArtists with deezerId in identifiers`() = runTest {
        // Given - Deezer returns matching artist and related artists
        httpClient.givenJsonResponse("search/artist", """{"data":[{"id":399,"name":"Radiohead"}]}""")
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_RESPONSE)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for similar artists
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ARTISTS)

        // Then - each SimilarArtist has deezerId in identifiers.extra and sources = ["deezer"]
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists
        assertTrue(data.artists.all { it.sources == listOf("deezer") })
        assertTrue(data.artists.all { it.identifiers.extra["deezerId"] != null })
        assertEquals("1001", data.artists[0].identifiers.extra["deezerId"])
    }

    @Test
    fun `enrich returns SimilarArtists with positional match scores`() = runTest {
        // Given - Deezer returns artist and 3 related artists
        httpClient.givenJsonResponse("search/artist", """{"data":[{"id":399,"name":"Radiohead"}]}""")
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_RESPONSE)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for similar artists
        val result = provider.enrich(request, EnrichmentType.SIMILAR_ARTISTS)

        // Then - first artist has higher score than last
        val data = ((result as EnrichmentResult.Success).data as EnrichmentData.SimilarArtists).artists
        assertTrue(data[0].matchScore > data[2].matchScore)
        // First should be ~1.0 and last should be ~0.1
        assertEquals(1.0f, data[0].matchScore, 0.01f)
    }

    // SIMILAR_TRACKS tests — artist-derived via /artist/{id}/related + /artist/{id}/top, since
    // /track/{id}/radio does not exist on Deezer's public API.

    @Test
    fun `enrich returns SimilarTracks derived from related artists' top tracks`() = runTest {
        // Given - Karma Police resolves to Radiohead (artist id 399 comes off the track search
        // result itself), which has 3 related artists each with a top track
        httpClient.givenJsonResponse("search/track", TRACK_SEARCH_RESPONSE)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_RESPONSE)
        httpClient.givenJsonResponse("artist/1001/top", MUSE_TOP_TRACKS)
        httpClient.givenJsonResponse("artist/1002/top", SIGUR_ROS_TOP_TRACKS)
        httpClient.givenJsonResponse("artist/1003/top", PORTISHEAD_TOP_TRACKS)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When - enriching for similar tracks
        val result = provider.enrich(request, EnrichmentType.SIMILAR_TRACKS)

        // Then - success with one top track per related artist, all sourced from deezer
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        assertEquals(3, data.tracks.size)
        assertTrue(data.tracks.any { it.title == "Knights of Cydonia" && it.artist == "Muse" })
        assertTrue(data.tracks.all { it.sources == listOf("deezer") })
    }

    @Test
    fun `enrich sends the exact related and top URL contract for SimilarTracks, with no search_artist call`() = runTest {
        // Given - same fixtures as the happy path
        httpClient.givenJsonResponse("search/track", TRACK_SEARCH_RESPONSE)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_RESPONSE)
        httpClient.givenJsonResponse("artist/1001/top", MUSE_TOP_TRACKS)
        httpClient.givenJsonResponse("artist/1002/top", SIGUR_ROS_TOP_TRACKS)
        httpClient.givenJsonResponse("artist/1003/top", PORTISHEAD_TOP_TRACKS)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When - enriching for similar tracks
        provider.enrich(request, EnrichmentType.SIMILAR_TRACKS)

        // Then - pins the real replacement endpoints and their limits; a hand-rolled mock response
        // for /track/{id}/radio (as this test suite used to have) cannot catch a route Deezer
        // doesn't serve, only a URL assertion like this can. Also pins that the artist id comes off
        // the track search result directly — no redundant search/artist round trip.
        assertTrue(httpClient.requestedUrls.any { it == "https://api.deezer.com/artist/399/related?limit=5" })
        assertTrue(httpClient.requestedUrls.any { it == "https://api.deezer.com/artist/1001/top?limit=3" })
        assertTrue(httpClient.requestedUrls.none { it.contains("/radio") })
        assertTrue(httpClient.requestedUrls.none { it.contains("search/artist") })
    }

    @Test
    fun `enrich sends the advanced field query for SimilarTracks seed resolution when the request carries an album hint`() = runTest {
        // Given - same fixtures as the happy path, but the request also carries an album hint
        httpClient.givenJsonResponse("search/track", TRACK_SEARCH_RESPONSE)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_RESPONSE)
        httpClient.givenJsonResponse("artist/1001/top", MUSE_TOP_TRACKS)
        httpClient.givenJsonResponse("artist/1002/top", SIGUR_ROS_TOP_TRACKS)
        httpClient.givenJsonResponse("artist/1003/top", PORTISHEAD_TOP_TRACKS)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead", album = "OK Computer")

        // When - enriching for similar tracks
        provider.enrich(request, EnrichmentType.SIMILAR_TRACKS)

        // Then - the seed track resolution used the advanced artist:/track:/album: field query
        val url = httpClient.requestedUrls.single { it.contains("search/track") }
        assertTrue(url.contains("artist%3A%22Radiohead%22"))
        assertTrue(url.contains("track%3A%22Karma+Police%22"))
        assertTrue(url.contains("album%3A%22OK+Computer%22"))
    }

    @Test
    fun `enrich returns NotFound for SimilarTracks when no search result`() = runTest {
        // Given - Deezer returns no track search results
        httpClient.givenJsonResponse("search/track", """{"data":[]}""")
        val request = EnrichmentRequest.forTrack("Nonexistent", "Nobody")

        // When - enriching for similar tracks
        val result = provider.enrich(request, EnrichmentType.SIMILAR_TRACKS)

        // Then - NotFound because no track matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for SimilarTracks when artist mismatch`() = runTest {
        // Given - Deezer returns a track from a different artist
        httpClient.givenJsonResponse("search/track", """{"data":[{"id":999,"title":"Karma Police","artist":{"name":"Completely Different Band"}}]}""")
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When - enriching for similar tracks
        val result = provider.enrich(request, EnrichmentType.SIMILAR_TRACKS)

        // Then - NotFound because ArtistMatcher.isMatch() rejects the result
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for SimilarTracks on artist request`() = runTest {
        // Given - a ForArtist request (SIMILAR_TRACKS requires ForTrack)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for similar tracks
        val result = provider.enrich(request, EnrichmentType.SIMILAR_TRACKS)

        // Then - NotFound because SIMILAR_TRACKS requires ForTrack
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for SimilarTracks when track search result has no artist id`() = runTest {
        // Given - the track resolves and its artist name matches, but the search payload carries
        // no artist id (no name-search fallback: this is treated as absent data, not re-resolved)
        httpClient.givenJsonResponse(
            "search/track",
            """{"data":[{"id":789,"title":"Karma Police","artist":{"name":"Radiohead"}}]}""",
        )
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When - enriching for similar tracks
        val result = provider.enrich(request, EnrichmentType.SIMILAR_TRACKS)

        // Then - NotFound because there's no artist id to fetch related artists with
        assertTrue(result is EnrichmentResult.NotFound)
        assertTrue(httpClient.requestedUrls.none { it.contains("related") })
    }

    @Test
    fun `enrich returns NotFound for SimilarTracks when related artists endpoint returns empty list`() = runTest {
        // Given - the seed artist (from the track search result) has no related artists
        httpClient.givenJsonResponse("search/track", TRACK_SEARCH_RESPONSE)
        httpClient.givenJsonResponse("artist/399/related", """{"data":[]}""")
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When - enriching for similar tracks
        val result = provider.enrich(request, EnrichmentType.SIMILAR_TRACKS)

        // Then - NotFound because there are no related artists to sample top tracks from
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich excludes the seed track from related artists' top tracks by id and by title plus artist`() = runTest {
        // Given - Muse's top tracks include the seed by exact id; Sigur Ros's include the same
        // title+artist under a different id (e.g. a cover); only Portishead's track is genuinely new
        httpClient.givenJsonResponse("search/track", TRACK_SEARCH_RESPONSE) // id=789 Karma Police/Radiohead
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_RESPONSE)
        httpClient.givenJsonResponse(
            "artist/1001/top",
            """{"data":[{"id":789,"title":"Karma Police","artist":{"name":"Radiohead"},"duration":253,"rank":900000}]}""",
        )
        httpClient.givenJsonResponse(
            "artist/1002/top",
            """{"data":[{"id":9999,"title":"Karma Police","artist":{"name":"Radiohead"},"duration":253,"rank":800000}]}""",
        )
        httpClient.givenJsonResponse("artist/1003/top", PORTISHEAD_TOP_TRACKS)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When - enriching for similar tracks
        val result = provider.enrich(request, EnrichmentType.SIMILAR_TRACKS)

        // Then - only Portishead's track survives; both seed duplicates are excluded
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        assertEquals(1, data.tracks.size)
        assertEquals("Portishead", data.tracks[0].artist)
    }

    @Test
    fun `enrich returns SimilarTracks with positional match scores by related-artist rank`() = runTest {
        // Given - Deezer returns track search, related artists, and top tracks
        httpClient.givenJsonResponse("search/track", TRACK_SEARCH_RESPONSE)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS_RESPONSE)
        httpClient.givenJsonResponse("artist/1001/top", MUSE_TOP_TRACKS)
        httpClient.givenJsonResponse("artist/1002/top", SIGUR_ROS_TOP_TRACKS)
        httpClient.givenJsonResponse("artist/1003/top", PORTISHEAD_TOP_TRACKS)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When - enriching for similar tracks
        val result = provider.enrich(request, EnrichmentType.SIMILAR_TRACKS)

        // Then - Muse (index 0, highest-ranked related artist) outscores the last-ranked artist's track
        val data = ((result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks).tracks
        assertTrue(data[0].matchScore > data[2].matchScore)
        assertEquals(1.0f, data[0].matchScore, 0.01f)
        assertEquals("Muse", data[0].artist)
    }

    // ARTIST_RADIO tests

    @Test
    fun `enrich returns RadioPlaylist for artist`() = runTest {
        // Given - Deezer returns a matching artist and radio tracks
        httpClient.givenJsonResponse("search/artist", """{"data":[{"id":399,"name":"Radiohead"}]}""")
        httpClient.givenJsonResponse("artist/399/radio", RADIO_RESPONSE)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for artist radio
        val result = provider.enrich(request, EnrichmentType.ARTIST_RADIO)

        // Then - success with RadioPlaylist data
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.RadioPlaylist
        assertEquals(2, data.tracks.size)
        assertEquals("Creep", data.tracks[0].title)
        assertEquals("Radiohead", data.tracks[0].artist)
        assertEquals("Pablo Honey", data.tracks[0].album)
        assertEquals(238000L, data.tracks[0].durationMs)
    }

    @Test
    fun `enrich returns RadioPlaylist track with deezerId identifier`() = runTest {
        // Given - Deezer returns artist and radio tracks
        httpClient.givenJsonResponse("search/artist", """{"data":[{"id":399,"name":"Radiohead"}]}""")
        httpClient.givenJsonResponse("artist/399/radio", RADIO_RESPONSE)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for artist radio
        val result = provider.enrich(request, EnrichmentType.ARTIST_RADIO) as EnrichmentResult.Success
        val data = result.data as EnrichmentData.RadioPlaylist

        // Then - each track has deezerId in identifiers.extra
        assertTrue(data.tracks.all { it.identifiers.extra["deezerId"] != null })
        assertEquals("123", data.tracks[0].identifiers.extra["deezerId"])
    }

    @Test
    fun `enrich returns NotFound for ARTIST_RADIO on album request`() = runTest {
        // Given - a ForAlbum request (ARTIST_RADIO requires ForArtist)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for artist radio
        val result = provider.enrich(request, EnrichmentType.ARTIST_RADIO)

        // Then - NotFound because ARTIST_RADIO requires ForArtist
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for ARTIST_RADIO when artist search returns no results`() = runTest {
        // Given - Deezer returns no artist search results
        httpClient.givenJsonResponse("search/artist", """{"data":[]}""")
        val request = EnrichmentRequest.forArtist("Nonexistent Artist")

        // When - enriching for artist radio
        val result = provider.enrich(request, EnrichmentType.ARTIST_RADIO)

        // Then - NotFound because no artist matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for ARTIST_RADIO when artist name does not match`() = runTest {
        // Given - Deezer returns a different artist
        httpClient.givenJsonResponse("search/artist", """{"data":[{"id":999,"name":"Completely Different Band"}]}""")
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for artist radio
        val result = provider.enrich(request, EnrichmentType.ARTIST_RADIO)

        // Then - NotFound because ArtistMatcher.isMatch() rejects the result
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for ARTIST_RADIO when radio endpoint returns empty data`() = runTest {
        // Given - Deezer returns artist but radio endpoint returns no tracks
        httpClient.givenJsonResponse("search/artist", """{"data":[{"id":399,"name":"Radiohead"}]}""")
        httpClient.givenJsonResponse("artist/399/radio", """{"data":[]}""")
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for artist radio
        val result = provider.enrich(request, EnrichmentType.ARTIST_RADIO)

        // Then - NotFound because no tracks returned
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich skips artist search for ARTIST_RADIO when deezerId is cached`() = runTest {
        // Given - request already has deezerId cached, only radio endpoint needed
        httpClient.givenJsonResponse("artist/399/radio", RADIO_RESPONSE)
        val request = EnrichmentRequest.forArtist("Radiohead").copy(
            identifiers = EnrichmentIdentifiers().withExtra("deezerId", "399")
        )

        // When - enriching for artist radio
        val result = provider.enrich(request, EnrichmentType.ARTIST_RADIO)

        // Then - success without a search/artist call
        assertTrue(result is EnrichmentResult.Success)
        val urls = httpClient.requestedUrls
        assertTrue(urls.none { it.contains("search/artist") })
    }

    // TRACK_PREVIEW tests

    @Test
    fun `enrich returns TrackPreview for known track with preview URL`() = runTest {
        // Given - Deezer returns a track with a preview URL
        httpClient.givenJsonResponse("search/track", TRACK_SEARCH_WITH_PREVIEW_RESPONSE)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When - enriching for track preview
        val result = provider.enrich(request, EnrichmentType.TRACK_PREVIEW)

        // Then - Success with TrackPreview data
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TrackPreview
        assertEquals("https://cdns-preview.dzcdn.net/stream/abc123.mp3", data.url)
        assertEquals(30000L, data.durationMs)
        assertEquals("deezer", data.source)
    }

    @Test
    fun `enrich returns NotFound for TRACK_PREVIEW when no search result`() = runTest {
        // Given - Deezer returns no track search results
        httpClient.givenJsonResponse("search/track", """{"data":[]}""")
        val request = EnrichmentRequest.forTrack("Nonexistent", "Nobody")

        // When - enriching for track preview
        val result = provider.enrich(request, EnrichmentType.TRACK_PREVIEW)

        // Then - NotFound because no track matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for TRACK_PREVIEW when artist name does not match`() = runTest {
        // Given - Deezer returns a track from a different artist
        httpClient.givenJsonResponse("search/track", """{"data":[{"id":999,"title":"Karma Police","artist":{"name":"Completely Different Band"},"preview":"https://example.com/p.mp3","duration":200}]}""")
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When - enriching for track preview
        val result = provider.enrich(request, EnrichmentType.TRACK_PREVIEW)

        // Then - NotFound because ArtistMatcher.isMatch() rejects the result
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for TRACK_PREVIEW when preview URL is blank`() = runTest {
        // Given - Deezer returns a matching track but preview URL is empty
        httpClient.givenJsonResponse("search/track", TRACK_SEARCH_NO_PREVIEW_RESPONSE)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When - enriching for track preview
        val result = provider.enrich(request, EnrichmentType.TRACK_PREVIEW)

        // Then - NotFound because preview URL is blank
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for TRACK_PREVIEW on artist request`() = runTest {
        // Given - a ForArtist request (TRACK_PREVIEW requires ForTrack)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for track preview
        val result = provider.enrich(request, EnrichmentType.TRACK_PREVIEW)

        // Then - NotFound because TRACK_PREVIEW requires ForTrack
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `capabilities include TRACK_PREVIEW with priority 100`() {
        // Given - the provider's declared capabilities

        // When - checking for TRACK_PREVIEW capability
        val trackPreviewCap = provider.capabilities.find { it.type == EnrichmentType.TRACK_PREVIEW }

        // Then - exists with priority 100
        assertNotNull(trackPreviewCap)
        assertEquals(100, trackPreviewCap!!.priority)
    }

    @Test
    fun `enrich skips track search for TRACK_PREVIEW when deezerId is in identifiers`() = runTest {
        // Given - request has deezerId cached, only direct track endpoint needed
        httpClient.givenJsonResponse("track/789", TRACK_BY_ID_RESPONSE)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead",
            identifiers = EnrichmentIdentifiers().withExtra("deezerId", "789"),
        )

        // When - enriching for track preview
        val result = provider.enrich(request, EnrichmentType.TRACK_PREVIEW)

        // Then - success, fetched by direct GET /track/{id} with no search request made at all
        assertTrue(result is EnrichmentResult.Success)
        val preview = (result as EnrichmentResult.Success).data as EnrichmentData.TrackPreview
        assertEquals("https://cdns-preview.dzcdn.net/stream/abc123.mp3", preview.url)
        val urls = httpClient.requestedUrls
        assertEquals(1, urls.size)
        assertTrue("Should call GET /track/789", urls.single().contains("track/789"))
        assertTrue("Should not call search endpoint", urls.none { it.contains("search/track") })
    }

    @Test
    fun `enrich reports PROVIDER_NATIVE_ID for a deezerId lookup even when the request also carries an MBID`() = runTest {
        // Given - a request naming both a recording MBID and a trusted Deezer track id
        httpClient.givenJsonResponse("track/789", TRACK_BY_ID_RESPONSE)
        val request = EnrichmentRequest.forTrack(
            "Karma Police", "Radiohead",
            identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-does-not-belong-to-deezer")
                .withExtra("deezerId", "789"),
        )

        // When - enriching for track preview
        val result = provider.enrich(request, EnrichmentType.TRACK_PREVIEW)

        // Then - Deezer's own /track/{id} route is reported, not the MBID it never touched
        assertTrue(result is EnrichmentResult.Success)
        val preview = result as EnrichmentResult.Success
        assertEquals(LookupProvenance.PROVIDER_NATIVE_ID, preview.provenance)
    }

    @Test
    fun `enrich returns the deezerId track even when its display title has no relation to the request`() = runTest {
        // Given - an exact provider-id lookup, where the caller's own display title is stale or
        // wrong; the fetched track's title is wholly unrelated to what was requested
        httpClient.givenJsonResponse("track/789", TRACK_BY_ID_RESPONSE)
        val request = EnrichmentRequest.forTrack("Totally Different Title", "Someone Else",
            identifiers = EnrichmentIdentifiers().withExtra("deezerId", "789"),
        )

        // When - enriching for track preview by id
        val result = provider.enrich(request, EnrichmentType.TRACK_PREVIEW)

        // Then - the id lookup succeeds; title acceptance never runs on an exact id fetch
        assertTrue(result is EnrichmentResult.Success)
        val preview = (result as EnrichmentResult.Success).data as EnrichmentData.TrackPreview
        assertEquals("https://cdns-preview.dzcdn.net/stream/abc123.mp3", preview.url)
    }

    @Test
    fun `enrich returns NotFound for TRACK_PREVIEW fast path when track has no preview`() = runTest {
        // Given - track exists but has no preview URL
        httpClient.givenJsonResponse("track/789", TRACK_BY_ID_NO_PREVIEW_RESPONSE)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead",
            identifiers = EnrichmentIdentifiers().withExtra("deezerId", "789"),
        )

        // When - enriching for track preview
        val result = provider.enrich(request, EnrichmentType.TRACK_PREVIEW)

        // Then - NotFound because no preview URL
        assertTrue(result is EnrichmentResult.NotFound)
    }

    // TRACK_METADATA tests

    @Test
    fun `enrich returns TrackMetadata with real duration and album title`() = runTest {
        // Given - Deezer returns a track with a 253s duration and album title (not the 30s preview)
        httpClient.givenJsonResponse("search/track", TRACK_SEARCH_WITH_PREVIEW_RESPONSE)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When - enriching for track metadata
        val result = provider.enrich(request, EnrichmentType.TRACK_METADATA)

        // Then - Success with the real track length, not a hardcoded preview duration
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.TrackMetadata
        assertEquals(253000L, data.durationMs)
        assertEquals("OK Computer", data.albumTitle)
    }

    @Test
    fun `enrich returns NotFound for TRACK_METADATA when artist name does not match`() = runTest {
        // Given - Deezer returns a track from a different artist
        httpClient.givenJsonResponse("search/track", """{"data":[{"id":999,"title":"Karma Police","artist":{"name":"Completely Different Band"},"duration":200}]}""")
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead")

        // When - enriching for track metadata
        val result = provider.enrich(request, EnrichmentType.TRACK_METADATA)

        // Then - NotFound because ArtistMatcher.isMatch() rejects the result
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for TRACK_METADATA on artist request`() = runTest {
        // Given - a ForArtist request (TRACK_METADATA requires ForTrack)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for track metadata
        val result = provider.enrich(request, EnrichmentType.TRACK_METADATA)

        // Then - NotFound because TRACK_METADATA requires ForTrack
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `capabilities include TRACK_METADATA with priority 70`() {
        // Given - the provider's declared capabilities list
        // When - checking for TRACK_METADATA capability
        val cap = provider.capabilities.find { it.type == EnrichmentType.TRACK_METADATA }

        // Then - exists with priority 70
        assertNotNull(cap)
        assertEquals(70, cap!!.priority)
    }

    @Test
    fun `enrich gives two distinct requests in one call their own album selection`() = runTest {
        // Given - two different album requests, each with its own single-candidate search result
        httpClient.givenJsonResponsesInTurn(
            "search/album",
            """{"data":[{"id":100,"title":"C","artist":{"name":"A|B"},"cover_xl":"https://example.com/first.jpg"}]}""",
            """{"data":[{"id":200,"title":"B|C","artist":{"name":"A"},"cover_xl":"https://example.com/second.jpg"}]}""",
        )
        val firstRequest = EnrichmentRequest.forAlbum(title = "C", artist = "A|B")
        val secondRequest = EnrichmentRequest.forAlbum(title = "B|C", artist = "A")

        // When - both requests are resolved together in one ProviderCallScope
        val (firstResult, secondResult) = withContext(ProviderCallScope()) {
            provider.enrich(firstRequest, EnrichmentType.ALBUM_ART) to
                provider.enrich(secondRequest, EnrichmentType.ALBUM_ART)
        }

        // Then - each request searches and selects its own candidate rather than reusing the other's memoized selection
        val firstUrl = ((firstResult as EnrichmentResult.Success).data as EnrichmentData.Artwork).url
        val secondUrl = ((secondResult as EnrichmentResult.Success).data as EnrichmentData.Artwork).url
        assertEquals("https://example.com/first.jpg", firstUrl)
        assertEquals("https://example.com/second.jpg", secondUrl)
        assertEquals(2, httpClient.requestedUrls.count { it.contains("search/album") })
    }

    @Test
    fun `enrich exposes named selection evidence rather than a positional list`() = runTest {
        // Given - two accepted candidates, distinguished only by trackCount
        httpClient.givenJsonResponse(
            "search/album",
            """{"data":[
                {"id":55,"title":"Master Of Puppets (Remastered)","artist":{"name":"Metallica"},"cover_xl":"https://example.com/box.jpg","nb_tracks":137},
                {"id":56,"title":"Master Of Puppets (Remastered)","artist":{"name":"Metallica"},"cover_xl":"https://example.com/album.jpg","nb_tracks":8}
            ]}""",
        )
        val identifiers = EnrichmentIdentifiers()
        val request = EnrichmentRequest.ForAlbum(identifiers, "Master Of Puppets", "Metallica", trackCount = 8)

        // When - selecting directly against the search pool
        val results = listOf(
            DeezerAlbumResult(
                id = 55,
                title = "Master Of Puppets (Remastered)",
                artistName = "Metallica",
                coverSmall = null,
                coverMedium = null,
                coverBig = null,
                coverXl = "https://example.com/box.jpg",
                nbTracks = 137,
            ),
            DeezerAlbumResult(
                id = 56,
                title = "Master Of Puppets (Remastered)",
                artistName = "Metallica",
                coverSmall = null,
                coverMedium = null,
                coverBig = null,
                coverXl = "https://example.com/album.jpg",
                nbTracks = 8,
            ),
        )
        val match = results.selectAlbum(request)

        // Then - the winning candidate's tier, artist quality, and named trackCount evidence are all observable
        assertNotNull(match)
        assertEquals(56L, match!!.candidate.id)
        assertEquals(TitleMatcher.TitleTier.EDITION, match.tier)
        assertEquals(ArtistMatcher.matchQuality(request.artist, "Metallica"), match.artistQuality)
        assertEquals(true, match.tieBreaks["trackCount"])
    }

    @Test
    fun `a cancelled album search is not memoized as a miss`() = runTest {
        // Given - the first album search is cancelled mid-flight inside the same ProviderCallScope the retry reuses; the underlying data would be found on a retry
        val cancelling = CancellingOnceHttpClient(httpClient)
        val cancellingProvider = DeezerProvider(cancelling, RateLimiter(0))
        httpClient.givenJsonResponse("search/album", DEEZER_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
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
    fun `album selection is unaffected by the engine's canonical status`() = runTest {
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
            val albumHttpClient = FakeHttpClient().apply { givenJsonResponse("search/album", DEEZER_RESPONSE) }
            val deezer = DeezerProvider(albumHttpClient, RateLimiter(0))
            val mb = FakeProvider(
                id = "mb", isIdentityProvider = true,
                capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
            ).also { identityResult?.let(it::givenIdentityResult) }
            val engine = DefaultEnrichmentEngine(ProviderRegistry(listOf(deezer, mb)), FakeEnrichmentCache(), config)
            val results = engine.enrich(EnrichmentRequest.forAlbum("OK Computer", "Radiohead"), setOf(EnrichmentType.ALBUM_ART))
            assertEquals(expectedStatus, results.identity.status)
            ((results.raw.getValue(EnrichmentType.ALBUM_ART) as EnrichmentResult.Success).data as EnrichmentData.Artwork).url
        }

        // Then - every canonical-status configuration selects the same album
        assertEquals(1, urls.toSet().size)
    }

    companion object {
        val RADIO_RESPONSE = """
            {"data":[
                {"id":123,"title":"Creep","artist":{"name":"Radiohead"},"album":{"title":"Pablo Honey"},"duration":238},
                {"id":456,"title":"Fake Plastic Trees","artist":{"name":"Radiohead"},"album":{"title":"The Bends"},"duration":273}
            ]}
        """.trimIndent()

        val RELATED_ARTISTS_RESPONSE = """
            {"data":[
                {"id":1001,"name":"Muse"},
                {"id":1002,"name":"Sigur Ros"},
                {"id":1003,"name":"Portishead"}
            ]}
        """.trimIndent()

        // id 14879699 captured 2026-08-12: GET /search/album?q=Radiohead OK Computer, matches the
        // real Radiohead "OK Computer" album that DEEZER_ALBUM_DETAIL_RESPONSE below details
        val DEEZER_METADATA_RESPONSE = """
            {"data":[{
                "id":14879699,
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

        // captured 2026-08-12: GET /album/14879699, trimmed to the fields ALBUM_METADATA reads —
        // the same Radiohead "OK Computer" album DEEZER_METADATA_RESPONSE's search hit names
        val DEEZER_ALBUM_DETAIL_RESPONSE = """
            {
                "id":14879699,
                "title":"OK Computer",
                "upc":"634904078164",
                "label":"XL Recordings",
                "release_date":"1997-06-17"
            }
        """.trimIndent()

        // synthetic — no live counterpart; stands in for an album detail that carries none of
        // upc/label/release_date
        const val DEEZER_ALBUM_DETAIL_MISSING_FIELDS_RESPONSE = """{"id":14879699,"title":"OK Computer"}"""

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

        val TRACK_SEARCH_RESPONSE = """
            {"data":[
                {"id":789,"title":"Karma Police","artist":{"id":399,"name":"Radiohead"}}
            ]}
        """.trimIndent()

        val TRACK_SEARCH_WITH_PREVIEW_RESPONSE = """
            {"data":[
                {"id":789,"title":"Karma Police","artist":{"name":"Radiohead"},"preview":"https://cdns-preview.dzcdn.net/stream/abc123.mp3","duration":253,"album":{"title":"OK Computer"}}
            ]}
        """.trimIndent()

        val TRACK_SEARCH_NO_PREVIEW_RESPONSE = """
            {"data":[
                {"id":789,"title":"Karma Police","artist":{"name":"Radiohead"},"preview":"","duration":253,"album":{"title":"OK Computer"}}
            ]}
        """.trimIndent()

        val MUSE_TOP_TRACKS = """
            {"data":[
                {"id":5001,"title":"Knights of Cydonia","artist":{"name":"Muse"},"album":{"title":"Black Holes and Revelations"},"duration":366,"rank":950000}
            ]}
        """.trimIndent()

        val SIGUR_ROS_TOP_TRACKS = """
            {"data":[
                {"id":5002,"title":"Hoppipolla","artist":{"name":"Sigur Ros"},"album":{"title":"Takk..."},"duration":271,"rank":800000}
            ]}
        """.trimIndent()

        val PORTISHEAD_TOP_TRACKS = """
            {"data":[
                {"id":5003,"title":"Glory Box","artist":{"name":"Portishead"},"album":{"title":"Dummy"},"duration":304,"rank":700000}
            ]}
        """.trimIndent()

        val TRACK_BY_ID_RESPONSE = """
            {"id":789,"title":"Karma Police","artist":{"name":"Radiohead"},"preview":"https://cdns-preview.dzcdn.net/stream/abc123.mp3","duration":253,"album":{"title":"OK Computer"}}
        """.trimIndent()

        val TRACK_BY_ID_NO_PREVIEW_RESPONSE = """
            {"id":789,"title":"Karma Police","artist":{"name":"Radiohead"},"preview":"","duration":253,"album":{"title":"OK Computer"}}
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
