package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Wikipedia photos remain quarantined until file-specific attribution reaches the full result path. */
class WikipediaMediaListSelectionTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: WikipediaProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = WikipediaProvider(httpClient, RateLimiter(0L))
    }

    @Test
    fun `enrich does not advertise or fetch artist photos without a complete attribution contract`() = runTest {
        // Given - an artist request whose article could have a selected media file
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Radiohead"),
            name = "Radiohead",
        )

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - the capability and result are withheld even when a media route is available
        assertTrue(result is EnrichmentResult.NotFound)
        assertTrue(provider.capabilities.none { it.type == EnrichmentType.ARTIST_PHOTO })
    }
}
