package com.landofoz.musicmeta.provider.coverartarchive

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testkit.assertNoUrlRequestedTwice
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * [CoverArtArchiveProvider] holds no [com.landofoz.musicmeta.engine.ProviderCallScope] memo, so a
 * fan-out over its three metadata-backed types (ALBUM_ART_BACK, ALBUM_BOOKLET, CD_ART) fetches the
 * same release's image metadata once per type instead of once for the call.
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

    @Ignore(
        "Red now: CoverArtArchiveProvider requests release/{id}'s image-metadata endpoint three " +
            "times (once each for ALBUM_ART_BACK, ALBUM_BOOKLET, CD_ART) in one enrich() call, " +
            "where a memo would cost one. Remove this mark only once that count is one; this " +
            "assertion must go red first if it is not.",
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
