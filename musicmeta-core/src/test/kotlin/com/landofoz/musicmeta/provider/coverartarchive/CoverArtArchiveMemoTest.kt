package com.landofoz.musicmeta.provider.coverartarchive

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testkit.assertNoUrlRequestedTwice
import com.landofoz.musicmeta.testkit.countMatching
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [CoverArtArchiveProvider] memoizes `getArtworkMetadata` for the life of one
 * [com.landofoz.musicmeta.engine.ProviderCallScope]: a fan-out over its three metadata-backed types
 * (ALBUM_ART_BACK, ALBUM_BOOKLET, CD_ART), or over ALBUM_ART alongside one of them, fetches the
 * same release's image metadata once for the call, not once per type.
 */
class CoverArtArchiveMemoTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: CoverArtArchiveProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = CoverArtArchiveProvider(httpClient, RateLimiter(0))
    }

    private fun engine() = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(provider)),
        FakeEnrichmentCache(),
        EnrichmentConfig(enableIdentityResolution = false),
        mergers = emptyList(),
    )

    @Test
    fun `one album's fanout fetches release image metadata once, not once per type`() = runTest {
        // Given - a release whose metadata answers all three artwork-metadata types
        httpClient.givenJsonResponse("release/memo1", METADATA_JSON)
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "memo1"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - the three metadata-backed types are enriched in one call
        engine().enrich(
            request,
            setOf(EnrichmentType.ALBUM_ART_BACK, EnrichmentType.ALBUM_BOOKLET, EnrichmentType.CD_ART),
        )

        // Then - one request served the whole call, not one per type
        httpClient.assertNoUrlRequestedTwice()
    }

    @Test
    fun `ALBUM_ART and a metadata type share one release image-metadata request`() = runTest {
        // Given - a release whose front-cover redirect resolves independently of the metadata
        // endpoint, and whose metadata also answers ALBUM_ART_BACK
        httpClient.givenRedirectResult(
            "release/memo2/front-",
            HttpResult.Ok("https://archive.org/img/front.jpg"),
        )
        httpClient.givenJsonResponse("release/memo2", METADATA_JSON)
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "memo2"),
            title = "OK Computer",
            artist = "Radiohead",
        )

        // When - ALBUM_ART (reached via fetchFrontImage's side-fetch) and a metadata-backed type
        // are enriched in one call
        engine().enrich(request, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.ALBUM_ART_BACK))

        // Then - the image-metadata endpoint (no /front-* suffix) was hit once, not once per branch
        val metadataRequests = httpClient.requestedUrls.count {
            it.contains("release/memo2") && !it.contains("front-")
        }
        assertEquals(1, metadataRequests)
    }

    @Test
    fun `a release with no image metadata is looked up once, not once per type`() = runTest {
        // Given - a release the metadata endpoint has nothing for (unstubbed resolves to a 404)
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "missing1"),
            title = "Unknown Album",
            artist = "Unknown Artist",
        )

        // When - two metadata-backed types are enriched in one call
        engine().enrich(request, setOf(EnrichmentType.ALBUM_ART_BACK, EnrichmentType.ALBUM_BOOKLET))

        // Then - the miss was looked up once, not once per type
        assertEquals(1, httpClient.countMatching("release/missing1"))
    }

    @Test
    fun `forceRefresh re-fetches release image metadata, not the previous call's memo`() = runTest {
        // Given - a release whose metadata answers both metadata-backed types, already fetched once
        httpClient.givenJsonResponse("release/memo3", METADATA_JSON)
        val request = EnrichmentRequest.ForAlbum(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "memo3"),
            title = "OK Computer",
            artist = "Radiohead",
        )
        val eng = engine()
        eng.enrich(request, setOf(EnrichmentType.ALBUM_ART_BACK, EnrichmentType.ALBUM_BOOKLET))

        // When - the same engine is asked again with forceRefresh, bypassing its cache
        eng.enrich(
            request,
            setOf(EnrichmentType.ALBUM_ART_BACK, EnrichmentType.ALBUM_BOOKLET),
            forceRefresh = true,
        )

        // Then - the memo did not survive into the second call: one request per call, two total
        assertEquals(2, httpClient.countMatching("release/memo3"))
    }

    private companion object {
        val METADATA_JSON = """
            {
              "images": [
                {"front": false, "types": ["Back"], "image": "https://archive.org/img/back.jpg", "thumbnails": {}},
                {"front": false, "types": ["Booklet"], "image": "https://archive.org/img/booklet.jpg", "thumbnails": {}},
                {"front": false, "types": ["Medium"], "image": "https://archive.org/img/medium.jpg", "thumbnails": {}}
              ]
            }
        """.trimIndent()
    }
}
