package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.testutil.FakeHttpClient
import org.junit.Assert.*
import org.junit.Test

class BuilderDefaultProvidersTest {

    private val httpClient = FakeHttpClient()

    @Test fun `withDefaultProviders without keys creates 9 providers`() {
        // Given -- no API keys configured
        val engine = EnrichmentEngine.Builder()
            .httpClient(httpClient)
            .withDefaultProviders()
            .build()

        // When -- listing providers
        val providers = engine.getProviders()

        // Then -- 9 keyless providers registered (includes SimilarAlbumsProvider)
        assertEquals(9, providers.size)
        val ids = providers.map { it.id }.toSet()
        assertTrue("musicbrainz" in ids)
        assertTrue("coverartarchive" in ids)
        assertTrue("wikidata" in ids)
        assertTrue("wikipedia" in ids)
        assertTrue("deezer" in ids)
        assertTrue("deezer-similar-albums" in ids)
        assertTrue("itunes" in ids)
        assertTrue("listenbrainz" in ids)
        assertTrue("lrclib" in ids)
        // Key-requiring providers should NOT be present
        assertFalse("lastfm" in ids)
        assertFalse("fanarttv" in ids)
        assertFalse("discogs" in ids)
    }

    @Test fun `withDefaultProviders with all API keys creates 11 providers`() {
        // Given -- all API keys provided
        val engine = EnrichmentEngine.Builder()
            .httpClient(httpClient)
            .apiKeys(
                ApiKeyConfig(
                    lastFmKey = "test-lastfm-key",
                    fanartTvProjectKey = "test-fanarttv-key",
                    discogsPersonalToken = "test-discogs-token",
                ),
            )
            .withDefaultProviders()
            .build()

        // When -- listing providers
        val providers = engine.getProviders()

        // Then -- all 12 providers registered (9 keyless + 3 key-requiring)
        assertEquals(12, providers.size)
        val ids = providers.map { it.id }.toSet()
        assertTrue("lastfm" in ids)
        assertTrue("fanarttv" in ids)
        assertTrue("discogs" in ids)
    }

    @Test fun `apiKeys with single key enables only that provider`() {
        // Given -- only Last.fm key provided
        val engine = EnrichmentEngine.Builder()
            .httpClient(httpClient)
            .apiKeys(ApiKeyConfig(lastFmKey = "test-key"))
            .withDefaultProviders()
            .build()

        // When -- listing providers
        val providers = engine.getProviders()

        // Then -- 10 providers (9 keyless + Last.fm)
        assertEquals(10, providers.size)
        val ids = providers.map { it.id }.toSet()
        assertTrue("lastfm" in ids)
        assertFalse("fanarttv" in ids)
        assertFalse("discogs" in ids)
    }

    @Test fun `withDefaultProviders registers identity provider`() {
        // Given -- default providers
        val engine = EnrichmentEngine.Builder()
            .httpClient(httpClient)
            .withDefaultProviders()
            .build()

        // When -- checking providers
        val providers = engine.getProviders()
        val mb = providers.first { it.id == "musicbrainz" }

        // Then -- MusicBrainz has capabilities (identity provider)
        assertTrue(mb.capabilities.isNotEmpty())
    }
}
