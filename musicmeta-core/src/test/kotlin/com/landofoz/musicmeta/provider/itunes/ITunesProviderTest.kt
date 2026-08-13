package com.landofoz.musicmeta.provider.itunes

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
import org.junit.Test

class ITunesProviderTest {

    private val httpClient = FakeHttpClient()
    private val provider = ITunesProvider(httpClient, RateLimiter(0))

    @Test
    fun `enrich returns album art with upscaled URL`() = runTest {
        // Given - iTunes API returns a result for "OK Computer"
        httpClient.givenJsonResponse("itunes.apple.com", ITUNES_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - artwork URL is upscaled to 1200x1200
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue(data.url.contains("1200x1200bb"))
    }

    @Test
    fun `enrich returns NotFound when search returns no results`() = runTest {
        // Given - iTunes API returns empty results
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":0,"results":[]}""")
        val request = EnrichmentRequest.forAlbum("Nonexistent", "Nobody")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because no albums matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound for artist requests`() = runTest {
        // Given - an artist-level request (iTunes only supports ALBUM_ART/ALBUM_METADATA for albums)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because iTunes doesn't handle artist requests for ALBUM_ART
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich upscales artwork URL from 100x100 to 1200x1200`() = runTest {
        // Given - iTunes returns 100x100 artwork URL
        httpClient.givenJsonResponse("itunes.apple.com", ITUNES_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART) as EnrichmentResult.Success
        val data = result.data as EnrichmentData.Artwork

        // Then - main URL uses 1200x1200, thumbnail preserves original 100x100
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
        // Given - empty iTunes response (we only care about the outgoing URL)
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":0,"results":[]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching triggers an HTTP request
        provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - query URL includes entity=album and the artist name
        val url = httpClient.requestedUrls.first()
        assertTrue(url.contains("entity=album"))
        assertTrue(url.contains("Radiohead"))
    }

    @Test
    fun `enrich returns NotFound when result has no artworkUrl100`() = runTest {
        // Given - iTunes result object is missing the artworkUrl100 field entirely
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":1,"results":[{
            "collectionName":"OK Computer",
            "artistName":"Radiohead"
        }]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because artworkUrl is null after takeIfNotEmpty
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when collectionName is empty string`() = runTest {
        // Given - iTunes result has empty collectionName but valid artwork
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":1,"results":[{
            "collectionName":"",
            "artistName":"Radiohead",
            "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg"
        }]}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound, because a blank collectionName can never clear the title-acceptance gate
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when results field is missing from JSON`() = runTest {
        // Given - iTunes API returns JSON without a "results" array
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":0}""")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound because optJSONArray("results") returns null
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns album metadata with trackCount and genre`() = runTest {
        // Given - iTunes API returns result with trackCount and genre
        httpClient.givenJsonResponse("itunes.apple.com", ITUNES_METADATA_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - success with Metadata containing trackCount, genres, country, releaseDate
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertEquals(12, data.trackCount)
        assertEquals(listOf("Alternative"), data.genres)
        assertEquals("USA", data.country)
    }

    @Test
    fun `enrich returns NotFound for album metadata when no results`() = runTest {
        // Given - iTunes API returns empty results
        httpClient.givenJsonResponse("itunes.apple.com", """{"resultCount":0,"results":[]}""")
        val request = EnrichmentRequest.forAlbum("Nonexistent", "Nobody")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Error with NETWORK ErrorKind when API fails`() = runTest {
        // Given - simulate an IOException from the HTTP layer
        httpClient.givenIoException("itunes.apple.com")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - Error with NETWORK kind because IOException maps to ErrorKind.NETWORK
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
        assertEquals("itunes", result.provider)
    }

    // ---- New tests for ALBUM_TRACKS and ARTIST_DISCOGRAPHY ----

    @Test
    fun `capabilities include ALBUM_TRACKS and ARTIST_DISCOGRAPHY at priority 30`() = runTest {
        // Given - the provider capabilities list

        // When - checking capabilities
        val capabilities = provider.capabilities

        // Then - both new capabilities are registered at priority 30
        assertTrue(capabilities.any { it.type == EnrichmentType.ALBUM_TRACKS && it.priority == 30 })
        assertTrue(capabilities.any { it.type == EnrichmentType.ARTIST_DISCOGRAPHY && it.priority == 30 })
    }

    @Test
    fun `enrich returns album tracks from lookup API`() = runTest {
        // Given - ForAlbum request with itunesCollectionId in identifiers.extra
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_TRACKS_RESPONSE)
        val identifiers = EnrichmentIdentifiers().withExtra("itunesCollectionId", "203558498")
        val request = EnrichmentRequest.ForAlbum(identifiers, "OK Computer", "Radiohead")

        // When - enriching for album tracks
        val result = provider.enrich(request, EnrichmentType.ALBUM_TRACKS)

        // Then - Success with Tracklist containing track titles, positions, and durations
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
    fun `album tracks by collectionId self-reports a provider-native-id route`() = runTest {
        // Given - the same stored-collectionId request the lookup-API test above uses
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_TRACKS_RESPONSE)
        val identifiers = EnrichmentIdentifiers().withExtra("itunesCollectionId", "203558498")
        val request = EnrichmentRequest.ForAlbum(identifiers, "OK Computer", "Radiohead")

        // When - enriching for album tracks
        val result = provider.enrich(request, EnrichmentType.ALBUM_TRACKS)

        // Then - this call never searched, so its route is observed, not inferred from canonical status
        assertEquals(LookupProvenance.PROVIDER_NATIVE_ID, (result as EnrichmentResult.Success).provenance)
    }

    @Test
    fun `enrich returns album tracks by search when no collectionId`() = runTest {
        // Given - ForAlbum without itunesCollectionId; search returns result, then lookup returns tracks
        httpClient.givenJsonResponse("search", ITUNES_SEARCH_WITH_ID_RESPONSE)
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_TRACKS_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album tracks
        val result = provider.enrich(request, EnrichmentType.ALBUM_TRACKS)

        // Then - Success with Tracklist (search first, then lookup)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Tracklist
        assertEquals(2, data.tracks.size)
        // A name search leaves its own route unreported: the engine, not this provider, has the
        // canonical-status evidence to tell an exact name match from an unverified guess.
        assertEquals(null, (result as EnrichmentResult.Success).provenance)
    }

    @Test
    fun `enrich returns artist discography from lookup API`() = runTest {
        // Given - ForArtist with itunesArtistId in identifiers.extra
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_ARTIST_ALBUMS_RESPONSE)
        val identifiers = EnrichmentIdentifiers().withExtra("itunesArtistId", "657515")
        val request = EnrichmentRequest.ForArtist(identifiers, "Radiohead")

        // When - enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then - Success with Discography containing album titles and years
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Discography
        assertEquals(1, data.albums.size)
        assertEquals("OK Computer", data.albums[0].title)
        assertEquals("1997", data.albums[0].year)
        // This call never searched: it went straight to the stored artist id, so its route is
        // observed, not left for the engine to infer from canonical status.
        assertEquals(LookupProvenance.PROVIDER_NATIVE_ID, (result as EnrichmentResult.Success).provenance)
    }

    @Test
    fun `enrich returns artist discography by search when no artistId`() = runTest {
        // Given - ForArtist without itunesArtistId; search for artist returns ID, lookup returns albums
        httpClient.givenJsonResponse("search", ITUNES_SEARCH_ARTIST_RESPONSE)
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_ARTIST_ALBUMS_RESPONSE)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then - Success with Discography (search artist, then lookup albums)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Discography
        assertEquals(1, data.albums.size)
        // A name search leaves its own route unreported: the engine has the canonical-status
        // evidence to tell an exact name match from an unverified guess, not this provider.
        assertEquals(null, (result as EnrichmentResult.Success).provenance)
    }

    @Test
    fun `enrich stores itunesArtistId in resolvedIdentifiers for discography`() = runTest {
        // Given - ForArtist without itunesArtistId; search resolves the artist ID
        httpClient.givenJsonResponse("search", ITUNES_SEARCH_ARTIST_RESPONSE)
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_ARTIST_ALBUMS_RESPONSE)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then - resolvedIdentifiers includes itunesArtistId for future lookups
        assertTrue(result is EnrichmentResult.Success)
        val resolved = (result as EnrichmentResult.Success).resolvedIdentifiers
        assertNotNull(resolved)
        assertNotNull("itunesArtistId should be stored", resolved?.get("itunesArtistId"))
    }

    @Test
    fun `enrich returns NotFound for ALBUM_TRACKS when lookup returns empty`() = runTest {
        // Given - lookup returns only the collection wrapper, no tracks
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_EMPTY_TRACKS_RESPONSE)
        val identifiers = EnrichmentIdentifiers().withExtra("itunesCollectionId", "203558498")
        val request = EnrichmentRequest.ForAlbum(identifiers, "OK Computer", "Radiohead")

        // When - enriching for album tracks
        val result = provider.enrich(request, EnrichmentType.ALBUM_TRACKS)

        // Then - NotFound because no tracks in lookup result
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich stores itunesCollectionId and itunesArtistId on resolvedIdentifiers`() = runTest {
        // Given - search returns album result with collectionId and artistId
        httpClient.givenJsonResponse("search", ITUNES_SEARCH_WITH_IDS_RESPONSE)
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_TRACKS_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for album tracks (which triggers search then stores IDs)
        val result = provider.enrich(request, EnrichmentType.ALBUM_TRACKS)

        // Then - resolvedIdentifiers includes itunesCollectionId
        assertTrue(result is EnrichmentResult.Success)
        val resolved = (result as EnrichmentResult.Success).resolvedIdentifiers
        assertNotNull(resolved)
        assertEquals("203558498", resolved?.get("itunesCollectionId"))
    }

    @Test
    fun `picked search candidate resolves ALBUM_TRACKS by lookup instead of a second search`() = runTest {
        // Given - a search that returns an album with a collectionId, and a lookup for its tracks
        httpClient.givenJsonResponse("search", ITUNES_SEARCH_WITH_ID_RESPONSE)
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_TRACKS_RESPONSE)
        val search = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - a candidate is picked and its identifiers are carried into the next request
        val candidate = provider.searchCandidates(search, 5).first()
        val picked = EnrichmentRequest.ForAlbum(candidate.identifiers, "OK Computer", "Radiohead")
        val result = provider.enrich(picked, EnrichmentType.ALBUM_TRACKS)

        // Then - the candidate carried the id, and enrich took the id lookup path at 1.0 confidence
        assertEquals("203558498", candidate.identifiers.get("itunesCollectionId"))
        assertTrue(result is EnrichmentResult.Success)
        assertEquals(1.0f, (result as EnrichmentResult.Success).confidence, 0.0f)
        assertTrue(httpClient.requestedUrls.any { it.contains("/lookup?") })
        assertEquals(1, httpClient.requestedUrls.count { it.contains("/search?") })
    }

    @Test
    fun `search candidate has no identifiers when the result carries no collectionId`() = runTest {
        // Given - a search result without a collectionId
        httpClient.givenJsonResponse("search", ITUNES_RESPONSE)
        val search = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - building candidates
        val candidate = provider.searchCandidates(search, 5).first()

        // Then - no itunesCollectionId is invented from the 0 default
        assertNull(candidate.identifiers.get("itunesCollectionId"))
    }

    @Test
    fun `enrich returns NotFound for ARTIST_DISCOGRAPHY with ForAlbum request`() = runTest {
        // Given - ForAlbum request (wrong type for artist discography)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for artist discography
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then - NotFound because ARTIST_DISCOGRAPHY requires ForArtist
        assertTrue(result is EnrichmentResult.NotFound)
    }

    // ---- Barcode-present album resolution (UPC identity lookup) ----

    @Test
    fun `a barcode request skips search entirely`() = runTest {
        // Given - a request carrying a barcode iTunes resolves to a matching-artist collection
        httpClient.givenJsonResponse("upc=724384960650", UPC_LOOKUP_DISCOVERY)
        val request = barcodeRequest("724384960650", "Daft Punk", "Discovery")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - the lookup answered it and no /search request was ever made
        assertTrue(result is EnrichmentResult.Success)
        assertTrue(httpClient.requestedUrls.none { it.contains("/search") })
    }

    @Test
    fun `a barcode hit scores as an exact identity match and carries the collection id forward`() = runTest {
        // Given - the same matching-artist UPC lookup
        httpClient.givenJsonResponse("upc=724384960650", UPC_LOOKUP_DISCOVERY)
        val request = barcodeRequest("724384960650", "Daft Punk", "Discovery")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA) as EnrichmentResult.Success

        // Then - confidence is the identity-match ceiling, above fuzzyMatch(true), and the resolved
        // collection id is carried so ALBUM_TRACKS can reuse it without a further UPC round trip
        assertEquals(1.0f, result.confidence, 0.0f)
        assertEquals("697194953", result.resolvedIdentifiers?.get("itunesCollectionId"))
        // A barcode lookup is a direct identifier lookup, never a search — its route is observed,
        // not left for the engine's canonical-status inference.
        assertEquals(LookupProvenance.PROVIDER_NATIVE_ID, result.provenance)
    }

    @Test
    fun `resultCount 0 on a barcode lookup is NotFound, not an outage`() = runTest {
        // Given - a live-shaped resultCount 0 response for an absent barcode
        httpClient.givenJsonResponse("upc=602547670342", """{"resultCount":0,"results":[]}""")
        val request = barcodeRequest("602547670342", "The Beatles", "Abbey Road")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - a genuine miss, not Error
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `a barcode collection under a different artist is passed over for one that matches`() = runTest {
        // Given - the first collection under this barcode names an unrelated artist; the second
        // is the album the caller actually asked about
        httpClient.givenJsonResponse("upc=724384960650", UPC_LOOKUP_WRONG_ARTIST_FIRST)
        val request = barcodeRequest("724384960650", "Daft Punk", "Discovery")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - the matching-artist candidate wins, not the unrelated one iTunes listed first
        assertTrue(result is EnrichmentResult.Success)
        assertEquals(
            "697194953",
            (result as EnrichmentResult.Success).resolvedIdentifiers?.get("itunesCollectionId"),
        )
    }

    @Test
    fun `no candidate under a barcode names the requested artist is NotFound`() = runTest {
        // Given - every collection under this barcode names a different artist
        httpClient.givenJsonResponse("upc=724384960650", UPC_LOOKUP_NO_MATCH)
        val request = barcodeRequest("724384960650", "Daft Punk", "Discovery")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - a wrong-edition hit is refused, not accepted as a guess
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `a network failure on the barcode path is Error NETWORK, not NotFound`() = runTest {
        // Given - the UPC lookup itself fails transiently
        httpClient.givenIoException("upc=")
        val request = barcodeRequest("724384960650", "Daft Punk", "Discovery")

        // When - enriching for album metadata
        val result = provider.enrich(request, EnrichmentType.ALBUM_METADATA)

        // Then - a throttled/broken upstream reports as Error so the breaker sees it, never as a
        // silent absence
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    @Test
    fun `a blank barcode falls through to search rather than killing every album result`() = runTest {
        // Given - identifiers carrying an empty-string barcode (as an unset EnrichmentIdentifiers
        // field can arrive) alongside a search that would otherwise answer normally
        httpClient.givenJsonResponse("itunes.apple.com", ITUNES_RESPONSE)
        val identifiers = EnrichmentIdentifiers(barcode = "")
        val request = EnrichmentRequest.ForAlbum(identifiers, "OK Computer", "Radiohead")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - the search path still answers; a blank barcode is not treated as a real one
        assertTrue(result is EnrichmentResult.Success)
        assertTrue(httpClient.requestedUrls.any { it.contains("/search") })
    }

    @Test
    fun `metadata and tracks share one UPC lookup within the same call`() = runTest {
        // Given - one barcode request that will ask for both album metadata and tracks
        httpClient.givenJsonResponse("upc=724384960650", UPC_LOOKUP_DISCOVERY)
        httpClient.givenJsonResponse("lookup?id=697194953", ITUNES_LOOKUP_TRACKS_RESPONSE)
        val request = barcodeRequest("724384960650", "Daft Punk", "Discovery")

        // When - both types are requested together, as the engine would
        val results = withContext(ProviderCallScope()) {
            listOf(
                provider.enrich(request, EnrichmentType.ALBUM_METADATA),
                provider.enrich(request, EnrichmentType.ALBUM_TRACKS),
            )
        }

        // Then - both succeed from the one collection, and only one /lookup?upc= request was made
        assertTrue(results.all { it is EnrichmentResult.Success })
        assertEquals(1, httpClient.requestedUrls.count { it.contains("/lookup?upc=") })
        assertTrue(httpClient.requestedUrls.none { it.contains("/search") })
    }

    // Album title-acceptance tests

    @Test
    fun `enrich rejects a right-artist candidate whose title is unrelated, for all three album types`() = runTest {
        // Given - the only iTunes hit for "Song" by David Bowie is an unrelated Bowie collection
        httpClient.givenJsonResponse(
            "itunes.apple.com",
            """{"resultCount":1,"results":[{
                "collectionId":1039796877,
                "collectionName":"The Rise and Fall of Ziggy Stardust",
                "artistName":"David Bowie",
                "artworkUrl100":"https://example.com/ziggy.jpg"
            }]}""",
        )
        val request = EnrichmentRequest.forAlbum("Song", "David Bowie")

        // When - all three album types are requested together
        val results = withContext(ProviderCallScope()) {
            listOf(
                provider.enrich(request, EnrichmentType.ALBUM_ART),
                provider.enrich(request, EnrichmentType.ALBUM_METADATA),
                provider.enrich(request, EnrichmentType.ALBUM_TRACKS),
            )
        }

        // Then - NotFound for every type, one shared search, and no lookup for the rejected collection
        assertTrue(results.all { it is EnrichmentResult.NotFound })
        assertEquals(1, httpClient.requestedUrls.count { it.contains("/search") })
        assertTrue(httpClient.requestedUrls.none { it.contains("id=1039796877") })
    }

    @Test
    fun `enrich accepts a bare album request against a provider remaster-only edition`() = runTest {
        // Given - the only iTunes hit for a bare "Hunky Dory" request is the 2015 remaster
        httpClient.givenJsonResponse(
            "itunes.apple.com",
            """{"resultCount":1,"results":[{
                "collectionName":"Hunky Dory (2015 Remaster)",
                "artistName":"David Bowie",
                "artworkUrl100":"https://example.com/hunky-dory.jpg"
            }]}""",
        )
        val request = EnrichmentRequest.forAlbum("Hunky Dory", "David Bowie")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - success, because a bare request tolerates a provider-added remaster suffix
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test
    fun `enrich does not admit every base-equal suffix for a bare request`() = runTest {
        // Given - the only iTunes hit for a bare "Delicate Sound of Thunder" request is a Live edition
        httpClient.givenJsonResponse(
            "itunes.apple.com",
            """{"resultCount":1,"results":[{
                "collectionName":"Delicate Sound of Thunder (Live)",
                "artistName":"Pink Floyd",
                "artworkUrl100":"https://example.com/dsot.jpg"
            }]}""",
        )
        val request = EnrichmentRequest.forAlbum("Delicate Sound of Thunder", "Pink Floyd")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - NotFound, because Live names a different release than the request asked for
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich ranks accepted candidates by artist quality instead of taking the first hit`() = runTest {
        // Given - both candidates title-match exactly; the loose "Bad Bunny" match is listed before the exact "Bad Company" match
        httpClient.givenJsonResponse(
            "itunes.apple.com",
            """{"resultCount":2,"results":[
                {"collectionId":111,"collectionName":"Run With the Pack","artistName":"Bad Bunny","artworkUrl100":"https://example.com/bunny.jpg"},
                {"collectionId":222,"collectionName":"Run With the Pack","artistName":"Bad Company","artworkUrl100":"https://example.com/company.jpg"}
            ]}""",
        )
        val request = EnrichmentRequest.forAlbum("Run With the Pack", "Bad Company")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - the exact-artist candidate (222) wins, not the first hit (111)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue(data.url.contains("company"))
    }

    @Test
    fun `enrich prefers the edition whose track count matches the request over a differently-sized edition`() = runTest {
        // Given - two identically-titled, identically-qualified editions; the unrequested trackCount is listed first
        httpClient.givenJsonResponse(
            "itunes.apple.com",
            """{"resultCount":2,"results":[
                {"collectionId":55,"collectionName":"Master Of Puppets (Remastered)","artistName":"Metallica","artworkUrl100":"https://example.com/box.jpg","trackCount":137},
                {"collectionId":56,"collectionName":"Master Of Puppets (Remastered)","artistName":"Metallica","artworkUrl100":"https://example.com/album.jpg","trackCount":8}
            ]}""",
        )
        val identifiers = EnrichmentIdentifiers()
        val request = EnrichmentRequest.ForAlbum(identifiers, "Master Of Puppets", "Metallica", trackCount = 8)

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - the 8-track edition's artwork wins, not the first-listed 137-track box
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue(data.url.contains("album.jpg"))
    }

    @Test
    fun `enrich ranks title tier ahead of artist quality`() = runTest {
        // Given - both candidates clear the artist floor and the title floor: the accepted-remaster candidate has the exact artist, the exact-title candidate's artist is only a loose containment match
        val request = EnrichmentRequest.forAlbum("Album", "Real Band")
        httpClient.givenJsonResponse(
            "search",
            """{"resultCount":2,"results":[
                {"collectionId":1,"collectionName":"Album (Remastered)","artistName":"Real Band","artworkUrl100":"https://example.com/edition.jpg"},
                {"collectionId":2,"collectionName":"Album","artistName":"Real Band Tribute","artworkUrl100":"https://example.com/exact-title.jpg"}
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
    fun `enrich prefers the edition whose releaseDate matches the requested year over an undated tie`() = runTest {
        // Given - two identically-titled, identically-qualified editions; only one's releaseDate matches the requested year
        val request = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(), "Album", "Artist", year = 2015)
        httpClient.givenJsonResponse(
            "search",
            """{"resultCount":2,"results":[
                {"collectionId":1,"collectionName":"Album","artistName":"Artist","artworkUrl100":"https://example.com/2009.jpg","releaseDate":"2009-01-01T00:00:00Z"},
                {"collectionId":2,"collectionName":"Album","artistName":"Artist","artworkUrl100":"https://example.com/2015.jpg","releaseDate":"2015-01-01T00:00:00Z"}
            ]}""",
        )

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - the edition whose releaseDate starts with the requested year wins over the first-listed one
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://example.com/2015.jpg", data.url)
    }

    @Test
    fun `enrich searches again when only year differs between two requests in one ProviderCallScope`() = runTest {
        // Given - two editions under the same title/artist, distinguished only by releaseDate/year
        httpClient.givenJsonResponse(
            "search",
            """{"resultCount":2,"results":[
                {"collectionId":1,"collectionName":"Album","artistName":"Artist","artworkUrl100":"https://example.com/2009.jpg","releaseDate":"2009-01-01T00:00:00Z"},
                {"collectionId":2,"collectionName":"Album","artistName":"Artist","artworkUrl100":"https://example.com/2015.jpg","releaseDate":"2015-01-01T00:00:00Z"}
            ]}""",
        )
        val identifiers = EnrichmentIdentifiers()
        val request2009 = EnrichmentRequest.ForAlbum(identifiers, "Album", "Artist", year = 2009)
        val request2015 = EnrichmentRequest.ForAlbum(identifiers, "Album", "Artist", year = 2015)

        // When - both requests are made together
        val (result2009, result2015) = withContext(ProviderCallScope()) {
            provider.enrich(request2009, EnrichmentType.ALBUM_ART) to
                provider.enrich(request2015, EnrichmentType.ALBUM_ART)
        }

        // Then - each request's own year picks its own edition
        val url2009 = ((result2009 as EnrichmentResult.Success).data as EnrichmentData.Artwork).url
        val url2015 = ((result2015 as EnrichmentResult.Success).data as EnrichmentData.Artwork).url
        assertEquals("https://example.com/2009.jpg", url2009)
        assertEquals("https://example.com/2015.jpg", url2015)
    }

    @Test
    fun `enrich retries the name search after a transient failure instead of memoizing it as NotFound`() = runTest {
        // Given - the name search fails transiently on every attempt
        httpClient.givenIoException("search")
        val request = EnrichmentRequest.forAlbum("Album", "Artist")

        // When - the same request is enriched twice in immediate succession
        val (first, second) = withContext(ProviderCallScope()) {
            provider.enrich(request, EnrichmentType.ALBUM_ART) to
                provider.enrich(request, EnrichmentType.ALBUM_ART)
        }

        // Then - both calls surface the transient failure as Error, so the first failure was never
        // memoized as a false NotFound that the second call could reuse without searching again
        assertTrue(first is EnrichmentResult.Error)
        assertTrue(second is EnrichmentResult.Error)
        assertEquals(2, httpClient.requestedUrls.count { it.contains("search") })
    }

    @Test
    fun `enrich shares one name-search selection across ALBUM_ART ALBUM_METADATA and ALBUM_TRACKS`() = runTest {
        // Given - one search hit whose collectionId the ALBUM_TRACKS lookup then reuses
        httpClient.givenJsonResponse("search", ITUNES_SEARCH_WITH_ID_RESPONSE)
        httpClient.givenJsonResponse("lookup", ITUNES_LOOKUP_TRACKS_RESPONSE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - all three album types are requested together
        val results = withContext(ProviderCallScope()) {
            listOf(
                provider.enrich(request, EnrichmentType.ALBUM_ART),
                provider.enrich(request, EnrichmentType.ALBUM_METADATA),
                provider.enrich(request, EnrichmentType.ALBUM_TRACKS),
            )
        }

        // Then - all three succeed, and only one search request was made for the shared selection
        assertTrue(results.all { it is EnrichmentResult.Success })
        assertEquals(1, httpClient.requestedUrls.count { it.contains("/search") })
    }

    private fun barcodeRequest(barcode: String, artist: String, title: String): EnrichmentRequest.ForAlbum =
        EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(barcode = barcode), title, artist)

    @Test
    fun `enrich gives two distinct requests in one call their own album selection`() = runTest {
        // Given - two different album requests, each with its own single-candidate search result
        httpClient.givenJsonResponsesInTurn(
            "search",
            """{"resultCount":1,"results":[{"collectionId":100,"collectionName":"C","artistName":"A|B","artworkUrl100":"https://example.com/first.jpg"}]}""",
            """{"resultCount":1,"results":[{"collectionId":200,"collectionName":"B|C","artistName":"A","artworkUrl100":"https://example.com/second.jpg"}]}""",
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
        assertEquals(2, httpClient.requestedUrls.count { it.contains("search") })
    }

    @Test
    fun `enrich exposes named selection evidence rather than a positional list`() = runTest {
        // Given - two accepted candidates, distinguished by both trackCount and releaseDate
        val request = EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(), "Album", "Artist", trackCount = 8, year = 2015)
        val results = listOf(
            ITunesAlbumResult(
                collectionId = 55,
                collectionName = "Album",
                artistName = "Artist",
                artworkUrl = "https://example.com/box.jpg",
                releaseDate = "2009-01-01",
                trackCount = 137,
            ),
            ITunesAlbumResult(
                collectionId = 56,
                collectionName = "Album",
                artistName = "Artist",
                artworkUrl = "https://example.com/album.jpg",
                releaseDate = "2015-01-01",
                trackCount = 8,
            ),
        )

        // When - selecting directly against the search pool
        val match = results.selectAlbum(request)

        // Then - the winning candidate's tier, artist quality, and named trackCount/year evidence are all observable
        assertNotNull(match)
        assertEquals(56L, match!!.candidate.collectionId)
        assertEquals(TitleMatcher.TitleTier.EXACT, match.tier)
        assertEquals(ArtistMatcher.matchQuality(request.artist, "Artist"), match.artistQuality)
        assertEquals(true, match.tieBreaks["trackCount"])
        assertEquals(true, match.tieBreaks["year"])
    }

    @Test
    fun `a cancelled album search is not memoized as a miss`() = runTest {
        // Given - the first album search is cancelled mid-flight inside the same ProviderCallScope the retry reuses; the underlying data would be found on a retry
        val cancelling = CancellingOnceHttpClient(httpClient)
        val cancellingProvider = ITunesProvider(cancelling, RateLimiter(0))
        httpClient.givenJsonResponse("search", ITUNES_RESPONSE)
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
    fun `enrich does not select a materially different edition when trackCount and year are unknown`() = runTest {
        // Given - the request supplies neither trackCount nor year, and the pool has two equally-titled, equally-artist-matched editions
        httpClient.givenJsonResponse(
            "search",
            """{"resultCount":2,"results":[
                {"collectionId":56,"collectionName":"Master Of Puppets (Remastered)","artistName":"Metallica","artworkUrl100":"https://example.com/album.jpg","trackCount":8},
                {"collectionId":55,"collectionName":"Master Of Puppets (Remastered)","artistName":"Metallica","artworkUrl100":"https://example.com/box.jpg","trackCount":137}
            ]}""",
        )
        val request = EnrichmentRequest.forAlbum("Master Of Puppets", "Metallica")

        // When - enriching for album art
        val result = provider.enrich(request, EnrichmentType.ALBUM_ART)

        // Then - both tie-breaks are equally unknown, so provider order settles the tie: the first-listed edition wins
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertEquals("https://example.com/album.jpg", data.url)
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
            val albumHttpClient = FakeHttpClient().apply { givenJsonResponse("search", ITUNES_RESPONSE) }
            val itunes = ITunesProvider(albumHttpClient, RateLimiter(0))
            val mb = FakeProvider(
                id = "mb", isIdentityProvider = true,
                capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
            ).also { identityResult?.let(it::givenIdentityResult) }
            val engine = DefaultEnrichmentEngine(ProviderRegistry(listOf(itunes, mb)), FakeEnrichmentCache(), config)
            val results = engine.enrich(EnrichmentRequest.forAlbum("OK Computer", "Radiohead"), setOf(EnrichmentType.ALBUM_ART))
            assertEquals(expectedStatus, results.identity.status)
            ((results.raw.getValue(EnrichmentType.ALBUM_ART) as EnrichmentResult.Success).data as EnrichmentData.Artwork).url
        }

        // Then - every canonical-status configuration selects the same album
        assertEquals(1, urls.toSet().size)
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

        // captured 2026-08-12: GET /lookup?upc=724384960650, trimmed — Daft Punk "Discovery" as
        // carried by Deezer album 302127 (api.deezer.com/album/302127)
        val UPC_LOOKUP_DISCOVERY = """
            {"resultCount":1,"results":[
              {"wrapperType":"collection","collectionType":"Album","artistId":5468295,
               "collectionId":697194953,"artistName":"Daft Punk","collectionName":"Discovery",
               "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music221/v4/fd/4a/77/fd4a77db-0ebc-d043-41a2-f32fa1bb0fb4/dj.qrikkdwj.jpg/100x100bb.jpg",
               "trackCount":14,"country":"USA","releaseDate":"2001-03-12T08:00:00Z",
               "primaryGenreName":"Dance"}
            ]}
        """.trimIndent()

        // synthetic — constructed to pin the artist-match gate: a reused-barcode candidate under an
        // unrelated artist, listed ahead of the real match
        val UPC_LOOKUP_WRONG_ARTIST_FIRST = """
            {"resultCount":2,"results":[
              {"wrapperType":"collection","collectionId":111,"collectionName":"Unrelated Album",
               "artistName":"Someone Else"},
              {"wrapperType":"collection","collectionId":697194953,"collectionName":"Discovery",
               "artistName":"Daft Punk",
               "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg",
               "trackCount":14,"country":"USA","releaseDate":"2001-03-12T08:00:00Z"}
            ]}
        """.trimIndent()

        // synthetic — constructed to pin the no-match-among-candidates case
        val UPC_LOOKUP_NO_MATCH = """
            {"resultCount":2,"results":[
              {"wrapperType":"collection","collectionId":111,"collectionName":"Unrelated Album",
               "artistName":"Someone Else"},
              {"wrapperType":"collection","collectionId":222,"collectionName":"Another Unrelated",
               "artistName":"Also Not It"}
            ]}
        """.trimIndent()
    }
}
