package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.deezer.DeezerProvider
import com.landofoz.musicmeta.provider.itunes.ITunesProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A track-scoped request naming its album has everything Deezer, iTunes and Discogs need for
 * ALBUM_ART — same artist/album a `ForAlbum` request would carry — so it must reach them, not just
 * MBID-keyed providers. Fixtures copied from [com.landofoz.musicmeta.provider.deezer.DeezerProviderTest]
 * and [com.landofoz.musicmeta.provider.itunes.ITunesProviderTest].
 */
class TrackScopedAlbumArtTest {

    private val httpClient = FakeHttpClient()

    private fun engine() = DefaultEnrichmentEngine(
        ProviderRegistry(
            listOf(
                DeezerProvider(httpClient, RateLimiter(0)),
                ITunesProvider(httpClient, RateLimiter(0)),
            ),
        ),
        FakeEnrichmentCache(),
        EnrichmentConfig(),
        mergers = DEFAULT_MERGERS,
    )

    @Test
    fun `a track request naming its album reaches Deezer and iTunes and merges their art`() = runTest {
        // Given - both providers answer the same album/artist from a track-scoped request
        httpClient.givenJsonResponse("api.deezer.com", DEEZER_RESPONSE)
        httpClient.givenJsonResponse("itunes.apple.com", ITUNES_RESPONSE)
        val request = EnrichmentRequest.forTrack("Karma Police", "Radiohead", album = "OK Computer")

        // When - enriching for album art
        val results = engine().enrich(request, setOf(EnrichmentType.ALBUM_ART))

        // Then - both providers contributed, and one names the other as an alternative
        val result = results.raw.getValue(EnrichmentType.ALBUM_ART) as EnrichmentResult.Success
        val artwork = result.data as EnrichmentData.Artwork
        val contributingProviders = setOf(result.provider) + artwork.alternatives.orEmpty().map { it.provider }
        assertEquals(setOf("deezer", "itunes"), contributingProviders)
        val allUrls = listOf(artwork.url) + artwork.alternatives.orEmpty().map { it.url }
        assertTrue("expected iTunes's upscaled artwork among $allUrls", allUrls.any { it.contains("1200x1200bb") })
        assertTrue(
            "expected Deezer's cover_xl among $allUrls",
            allUrls.any { it.contains("e-cdns-images.dzcdn.net/images/cover/xl.jpg") },
        )
    }

    private companion object {
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

        val ITUNES_RESPONSE = """
            {"resultCount":1,"results":[{
                "collectionName":"OK Computer",
                "artistName":"Radiohead",
                "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/100x100bb.jpg"
            }]}
        """.trimIndent()
    }
}
