package com.landofoz.musicmeta.provider.fanarttv

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderRegistry
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
 * [FanartTvProvider] memoizes `getArtistImages` and `getAlbumImages` for the life of one
 * [com.landofoz.musicmeta.engine.ProviderCallScope]: a fan-out over the four artist-image types
 * fetches the same artist document once for the call, and a fan-out over ALBUM_ART/CD_ART fetches
 * the same album document once for the call, not once per type. The fixtures below carry every
 * image type, so these also pin the hit path: a recognised artist or album collides exactly as the
 * miss path does, not less.
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

    @Test
    fun `an unrecognised artist's document is fetched once, not once per type`() = runTest {
        // Given - an artist MBID the fanart.tv document endpoint has nothing for
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "unknown-mbid"),
            name = "Unknown Artist",
        )

        // When - two artist-image types are enriched in one call
        engine().enrich(request, setOf(EnrichmentType.ARTIST_PHOTO, EnrichmentType.ARTIST_BACKGROUND))

        // Then - the miss was looked up once, not once per type
        assertEquals(1, httpClient.countMatching("unknown-mbid"))
    }

    @Test
    fun `forceRefresh re-fetches the artist document, not the previous call's memo`() = runTest {
        // Given - an artist document already fetched once
        httpClient.givenJsonResponse("fanart.tv", ARTIST_IMAGES_JSON)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "a74b1b7f-71a5-4011-9441-d0b5e4122711"),
            name = "Radiohead",
        )
        val eng = engine()
        eng.enrich(request, setOf(EnrichmentType.ARTIST_PHOTO, EnrichmentType.ARTIST_BACKGROUND))

        // When - the same engine is asked again with forceRefresh, bypassing its cache
        eng.enrich(
            request,
            setOf(EnrichmentType.ARTIST_PHOTO, EnrichmentType.ARTIST_BACKGROUND),
            forceRefresh = true,
        )

        // Then - the memo did not survive into the second call: one request per call, two total
        assertEquals(2, httpClient.countMatching("a74b1b7f-71a5-4011-9441-d0b5e4122711"))
    }

    @Test
    fun `one album's fanout fetches the album document once, not once per type`() = runTest {
        // Given - an album document that answers both ALBUM_ART and CD_ART
        httpClient.givenJsonResponse("albums/rg-memo1", ALBUM_IMAGES_JSON)
        val request = albumRequest("rg-memo1")

        // When - both album-scoped types are enriched in one call
        engine().enrich(request, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.CD_ART))

        // Then - one request served the whole call, not one per type
        httpClient.assertNoUrlRequestedTwice()
    }

    @Test
    fun `an unrecognised release group's album document is fetched once, not once per type`() = runTest {
        // Given - a release-group id the album endpoint has nothing for
        val request = albumRequest("unknown-rg")

        // When - both album-scoped types are enriched in one call
        engine().enrich(request, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.CD_ART))

        // Then - the miss was looked up once, not once per type
        assertEquals(1, httpClient.countMatching("unknown-rg"))
    }

    @Test
    fun `forceRefresh re-fetches the album document, not the previous call's memo`() = runTest {
        // Given - an album document already fetched once
        httpClient.givenJsonResponse("albums/rg-memo1", ALBUM_IMAGES_JSON)
        val request = albumRequest("rg-memo1")
        val eng = engine()
        eng.enrich(request, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.CD_ART))

        // When - the same engine is asked again with forceRefresh, bypassing its cache
        eng.enrich(request, setOf(EnrichmentType.ALBUM_ART, EnrichmentType.CD_ART), forceRefresh = true)

        // Then - the memo did not survive into the second call: one request per call, two total
        assertEquals(2, httpClient.countMatching("rg-memo1"))
    }

    private fun albumRequest(releaseGroupMbid: String) = EnrichmentRequest.ForAlbum(
        identifiers = EnrichmentIdentifiers(musicBrainzReleaseGroupId = releaseGroupMbid),
        title = "OK Computer",
        artist = "Radiohead",
    )

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

        // Mirrors FanartTvProviderTest's ALBUM_ENDPOINT_JSON: the album endpoint nests the release
        // group under `albums`, captured 2026-09-03.
        val ALBUM_IMAGES_JSON = """
            {"albums": {"rg-memo1": {"albumcover": [{"url": "https://assets.fanart.tv/fanart/cover1.jpg"}], "cdart": [{"url": "https://assets.fanart.tv/fanart/cdart1.jpg"}]}}}
        """.trimIndent()
    }
}
