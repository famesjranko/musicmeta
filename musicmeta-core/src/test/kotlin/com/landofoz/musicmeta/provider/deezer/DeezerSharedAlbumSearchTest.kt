package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `deezer` and `deezer-similar-albums` ask `/search/album` the same question — the requested artist
 * and title — and on the shipped [EnrichmentRequest.DEFAULT_ALBUM_TYPES] both are asked in every
 * album call. Holding one [DeezerApi] between them makes that one request, and the two providers
 * one reader set of the album scope it owns.
 *
 * What the collapse must not buy: an answer for a query nobody asked, or one that outlives the call.
 */
class DeezerSharedAlbumSearchTest {

    private val httpClient = FakeHttpClient()
    private val api = DeezerApi(httpClient, RateLimiter(0))
    private val deezer = DeezerProvider(api)
    private val similarAlbums = SimilarAlbumsProvider(api)

    // Mergers and identity resolution excluded so the two types below reach their provider directly:
    // what is being counted is upstream requests, and either would add its own.
    private fun engine() = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(deezer, similarAlbums)),
        FakeEnrichmentCache(),
        EnrichmentConfig(enableIdentityResolution = false),
        mergers = emptyList(),
    )

    private fun albumSearches() = httpClient.requestedUrls.count { it.contains("search/album") }

    private fun givenOkComputer() {
        httpClient.givenJsonResponse("search/album?q=Radiohead", ALBUM_SEARCH_OK_COMPUTER)
        httpClient.givenJsonResponse("album/14879699", ALBUM_DETAIL_OK_COMPUTER)
        httpClient.givenJsonResponse("search/artist", RADIOHEAD_ARTIST_SEARCH)
        httpClient.givenJsonResponse("artist/399/related", RELATED_ARTISTS)
        httpClient.givenJsonResponse("artist/1001/albums", MUSE_ALBUMS)
    }

    @Test
    fun `one album search serves both Deezer providers in one call`() = runTest {
        // Given - an album whose call asks a type from each Deezer provider, as the album defaults do
        givenOkComputer()
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - ALBUM_METADATA and SIMILAR_ALBUMS are enriched together
        val results = engine().enrich(request, setOf(EnrichmentType.ALBUM_METADATA, EnrichmentType.SIMILAR_ALBUMS))

        // Then - the search both providers make is made once, and both types are answered from it
        assertEquals(1, albumSearches())
        assertTrue(results.raw[EnrichmentType.ALBUM_METADATA] is EnrichmentResult.Success)
        assertTrue(results.raw[EnrichmentType.SIMILAR_ALBUMS] is EnrichmentResult.Success)
    }

    @Test
    fun `two albums in one call do not share each other's search`() = runTest {
        // Given - two different albums, and a call that resolves both
        givenOkComputer()
        httpClient.givenJsonResponse("search/album?q=Trouble", ALBUM_SEARCH_PSALM_9)
        httpClient.givenJsonResponse("artist/10443928/related", RELATED_ARTISTS)
        val okComputer = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val psalm9 = EnrichmentRequest.forAlbum("Psalm 9", "Trouble")

        // When - one provider resolves the first album and the other the second, under one call scope
        val seeded = withContext(ProviderCallScope()) {
            deezer.enrich(okComputer, EnrichmentType.ALBUM_METADATA)
            similarAlbums.enrich(psalm9, EnrichmentType.SIMILAR_ALBUMS)
        }

        // Then - each query pays its own search, and the seed is the album that was asked for
        assertEquals(2, albumSearches())
        assertEquals(
            "10443928",
            (seeded as EnrichmentResult.Success).resolvedIdentifiers?.get(IdentifierNamespace.DEEZER),
        )
    }

    @Test
    fun `a failing album search is charged once and degrades both types`() = runTest {
        // Given - an album search endpoint that is down for the whole call
        givenOkComputer()
        httpClient.givenIoException("search/album")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - ALBUM_METADATA and SIMILAR_ALBUMS are enriched together
        val results = engine().enrich(request, setOf(EnrichmentType.ALBUM_METADATA, EnrichmentType.SIMILAR_ALBUMS))

        // Then - one attempt is made, and the second reader inherits its failure rather than repeating it
        assertEquals(1, albumSearches())
        assertTrue(results.raw[EnrichmentType.ALBUM_METADATA] is EnrichmentResult.Error)
        assertTrue(results.raw[EnrichmentType.SIMILAR_ALBUMS] is EnrichmentResult.Error)
    }

    @Test
    fun `a second call searches again rather than reading the first call's answer`() = runTest {
        // Given - the same album enriched once already
        givenOkComputer()
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        val engine = engine()
        engine.enrich(request, setOf(EnrichmentType.ALBUM_METADATA, EnrichmentType.SIMILAR_ALBUMS))

        // When - the consumer asks again for fresh data, and once more without asking
        engine.enrich(
            request,
            setOf(EnrichmentType.ALBUM_METADATA, EnrichmentType.SIMILAR_ALBUMS),
            forceRefresh = true,
        )
        engine.enrich(request, setOf(EnrichmentType.ALBUM_METADATA, EnrichmentType.SIMILAR_ALBUMS))

        // Then - only the refresh reached upstream: nothing the scope held outlived its own call,
        // and a warm read is still answered from the cache without touching Deezer
        assertEquals(2, albumSearches())
    }

    companion object {
        // Copied from DeezerProviderTest: id 14879699 captured 2026-08-12,
        // GET /search/album?q=Radiohead OK Computer. Its `artist` carries no id, which is the
        // shape that sends SimilarAlbumsProvider on to the name search below.
        const val ALBUM_SEARCH_OK_COMPUTER = """{"data":[{
            "id":14879699,
            "title":"OK Computer",
            "artist":{"name":"Radiohead"},
            "cover_xl":"https://e-cdns-images.dzcdn.net/images/cover/xl.jpg",
            "nb_tracks":12,
            "record_type":"album"
        }]}"""

        // Copied from DeezerProviderTest: captured 2026-08-12, GET /album/14879699.
        const val ALBUM_DETAIL_OK_COMPUTER = """{
            "id":14879699,
            "title":"OK Computer",
            "upc":"634904078164",
            "label":"XL Recordings",
            "release_date":"1997-06-17"
        }"""

        // Copied from SimilarAlbumsProviderTest: live `/search/artist?q=Radiohead&limit=10`,
        // 2026-09-06 — no second act of that name, so the seed resolves by name.
        const val RADIOHEAD_ARTIST_SEARCH = """{"data":[
            {"id":399,"name":"Radiohead","nb_album":45,"nb_fan":4085364},
            {"id":12189436,"name":"Radio Head","nb_album":7,"nb_fan":436}
        ]}"""

        // Copied from SimilarAlbumsProviderTest: live `/search/album?q=Trouble Psalm 9&limit=5`,
        // 2026-09-06 — the hit names its own artist, so it seeds without a name search.
        const val ALBUM_SEARCH_PSALM_9 = """{"data":[
            {"id":284590542,"title":"Psalm 9 (Remastered 2020)","nb_tracks":9,"record_type":"album",
             "artist":{"id":10443928,"name":"Trouble"}}
        ]}"""

        const val RELATED_ARTISTS = """{"data":[
            {"id":1001,"name":"Muse"},
            {"id":1002,"name":"Portishead"}
        ]}"""

        const val MUSE_ALBUMS = """{"data":[
            {"id":2001,"title":"Origin of Symmetry","release_date":"2001-07-17","record_type":"album","cover_medium":"https://img.dz/muse_oos.jpg"}
        ]}"""
    }
}
