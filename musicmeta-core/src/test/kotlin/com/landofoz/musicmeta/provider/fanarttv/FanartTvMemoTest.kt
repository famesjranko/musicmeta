package com.landofoz.musicmeta.provider.fanarttv

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
 * [FanartTvProvider] holds no [com.landofoz.musicmeta.engine.ProviderCallScope] memo, so a fan-out
 * over its four artist-image types fetches the same artist document once per type instead of once
 * for the call. The fixture below carries every image type, so this also pins the hit path: a
 * recognised artist collides exactly as the miss path does, not less.
 */
class FanartTvMemoTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: FanartTvProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = FanartTvProvider(
            projectKey = "test-project-key",
            httpClient = httpClient,
            rateLimiter = RateLimiter(0L),
        )
    }

    private fun engine() = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(provider)),
        FakeEnrichmentCache(),
        EnrichmentConfig(enableIdentityResolution = false),
        mergers = emptyList(),
    )

    @Ignore(
        "Red now: FanartTvProvider requests the v3/music/{mbid} artist document four times " +
            "(once each for ARTIST_PHOTO, ARTIST_BACKGROUND, ARTIST_LOGO, ARTIST_BANNER) in one " +
            "enrich() call, where a memo would cost one. Remove this mark only once that count is " +
            "one; this assertion must go red first if it is not.",
    )
    @Test
    fun `one artist's fanout fetches the artist document once, not once per type`() = runTest {
        // Given - an artist document that answers all four image types
        httpClient.givenJsonResponse("fanart.tv", ARTIST_IMAGES_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "a74b1b7f-71a5-4011-9441-d0b5e4122711"),
            name = "Radiohead",
        )

        // When - all four artist-image types are enriched in one call
        engine().enrich(
            request,
            setOf(
                EnrichmentType.ARTIST_PHOTO, EnrichmentType.ARTIST_BACKGROUND,
                EnrichmentType.ARTIST_LOGO, EnrichmentType.ARTIST_BANNER,
            ),
        )

        // Then - one request served the whole call, not one per type
        httpClient.assertNoUrlRequestedTwice()
    }

    private companion object {
        // synthetic: no ground truth available for v3 field-level shapes, mirrors
        // FanartTvProviderTest's ARTIST_IMAGES_JSON with musicbanner added so every type resolves
        val ARTIST_IMAGES_JSON = """
            {
              "artistthumb": [{"url": "https://assets.fanart.tv/fanart/thumb1.jpg"}],
              "artistbackground": [{"url": "https://assets.fanart.tv/fanart/bg1.jpg"}],
              "hdmusiclogo": [{"url": "https://assets.fanart.tv/fanart/logo1.png"}],
              "musicbanner": [{"url": "https://assets.fanart.tv/fanart/banner1.jpg"}]
            }
        """.trimIndent()
    }
}
