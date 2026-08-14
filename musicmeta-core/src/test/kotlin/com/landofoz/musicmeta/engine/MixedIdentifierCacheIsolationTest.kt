package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.deezer.DeezerProvider
import com.landofoz.musicmeta.testutil.FakeHttpClient
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A request carrying both a canonical MusicBrainz id and a provider id trusted for the call's
 * [EnrichmentType] may use either route — Deezer's exact-track lookup prefers its own id over the
 * MBID, so the two can legitimately name different entities. The complete request tuple keeps
 * those routes isolated without disabling cache replay.
 */
class MixedIdentifierCacheIsolationTest {

    @Test fun `a mismatched deezer id cannot poison an MBID-only preview cache entry`() = runTest {
        // Given - a mixed request whose Deezer id names a different track than its MBID
        val cache = InMemoryEnrichmentCache()
        val http = FakeHttpClient().also {
            it.givenJsonResponse(
                "track/789",
                """{"id":789,"title":"Karma Police","artist":{"name":"Radiohead"},""" +
                    """"preview":"https://example.com/deezer.mp3","duration":253}""",
            )
        }
        val mixed = EnrichmentRequest.forTrack(
            title = "Starman",
            artist = "David Bowie",
            identifiers = EnrichmentIdentifiers(musicBrainzId = "starman-mbid")
                .with(IdentifierNamespace.DEEZER, "789"),
        )

        // When - the mixed request is enriched, then a later MBID-only request for the same
        // nominal track is enriched against a fresh provider
        DefaultEnrichmentEngine(
            ProviderRegistry(listOf(DeezerProvider(http, RateLimiter(0)))),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        ).enrich(mixed, setOf(EnrichmentType.TRACK_PREVIEW))

        val fresh = EnrichmentResult.Success(
            EnrichmentType.TRACK_PREVIEW,
            EnrichmentData.TrackPreview("https://example.com/starman.mp3", 30_000, "fresh"),
            "fresh",
            1f,
        )
        val provider = FakeProvider(
            id = "fresh",
            capabilities = listOf(ProviderCapability(EnrichmentType.TRACK_PREVIEW, 100)),
        ).also { it.givenResult(EnrichmentType.TRACK_PREVIEW, fresh) }
        val mbidOnly = EnrichmentRequest.forTrack(
            title = "Starman",
            artist = "David Bowie",
            identifiers = EnrichmentIdentifiers(musicBrainzId = "starman-mbid"),
        )
        val replay = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        ).enrich(mbidOnly, setOf(EnrichmentType.TRACK_PREVIEW))

        // Then - the MBID-only request re-asked the fresh provider rather than replaying the
        // mismatched Deezer entry
        assertEquals(1, provider.enrichCalls.size)
        assertEquals(
            "https://example.com/starman.mp3",
            ((replay.raw.getValue(EnrichmentType.TRACK_PREVIEW) as EnrichmentResult.Success).data
                as EnrichmentData.TrackPreview).url,
        )
    }

    @Test fun `a mixed request does not overwrite a foreign entity already cached under the MBID key`() = runTest {
        // Given - an MBID key already holding an unrelated entity's cached preview
        val cache = InMemoryEnrichmentCache()
        val mbidOnly = EnrichmentRequest.forTrack(
            title = "Starman",
            artist = "David Bowie",
            identifiers = EnrichmentIdentifiers(musicBrainzId = "starman-mbid"),
        )
        val mbidKey = DefaultEnrichmentEngine.entityKeyFor(mbidOnly, EnrichmentType.TRACK_PREVIEW)
        val foreign = EnrichmentResult.Success(
            EnrichmentType.TRACK_PREVIEW,
            EnrichmentData.TrackPreview("https://example.com/foreign.mp3", 30_000, "seed"),
            "seed",
            1f,
        )
        cache.put(mbidKey, EnrichmentType.TRACK_PREVIEW, foreign, com.landofoz.musicmeta.CanonicalStatus.RESOLVED, 60_000)

        // When - a mixed request naming the same MBID plus a Deezer id is enriched
        val fresh = EnrichmentResult.Success(
            EnrichmentType.TRACK_PREVIEW,
            EnrichmentData.TrackPreview("https://example.com/route.mp3", 30_000, "deezer"),
            "deezer",
            0.9f,
        )
        val provider = FakeProvider(
            id = "deezer",
            capabilities = listOf(ProviderCapability(EnrichmentType.TRACK_PREVIEW, 100)),
        ).also { it.givenResult(EnrichmentType.TRACK_PREVIEW, fresh) }
        val mixed = EnrichmentRequest.forTrack(
            title = "Starman",
            artist = "David Bowie",
            identifiers = EnrichmentIdentifiers(musicBrainzId = "starman-mbid")
                .with(IdentifierNamespace.DEEZER, "789"),
        )
        val result = DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        ).enrich(mixed, setOf(EnrichmentType.TRACK_PREVIEW))

        // Then - the mixed call reached the provider's own route rather than the pre-seeded
        // foreign entry, and left the MBID-keyed entry exactly as it was
        assertEquals(1, provider.enrichCalls.size)
        assertEquals(
            "https://example.com/route.mp3",
            ((result.raw.getValue(EnrichmentType.TRACK_PREVIEW) as EnrichmentResult.Success).data
                as EnrichmentData.TrackPreview).url,
        )
        val stillForeign = cache.get(mbidKey, EnrichmentType.TRACK_PREVIEW)
        assertEquals(
            "https://example.com/foreign.mp3",
            ((stillForeign!!.result.data) as EnrichmentData.TrackPreview).url,
        )
    }

    @Test fun `a mixed request writes under its complete tuple rather than either single-id key`() = runTest {
        // Given - a mixed MBID/Deezer-id track request
        val cache = InMemoryEnrichmentCache()
        val mixed = EnrichmentRequest.forTrack(
            title = "Starman",
            artist = "David Bowie",
            identifiers = EnrichmentIdentifiers(musicBrainzId = "starman-mbid")
                .with(IdentifierNamespace.DEEZER, "789"),
        )
        val provider = FakeProvider(
            id = "deezer",
            capabilities = listOf(ProviderCapability(EnrichmentType.TRACK_PREVIEW, 100)),
        ).also {
            it.givenResult(
                EnrichmentType.TRACK_PREVIEW,
                EnrichmentResult.Success(
                    EnrichmentType.TRACK_PREVIEW,
                    EnrichmentData.TrackPreview("https://example.com/route.mp3", 30_000, "deezer"),
                    "deezer",
                    0.9f,
                ),
            )
        }

        // When - the mixed request is enriched
        DefaultEnrichmentEngine(
            ProviderRegistry(listOf(provider)),
            cache,
            EnrichmentConfig(enableIdentityResolution = false),
        ).enrich(mixed, setOf(EnrichmentType.TRACK_PREVIEW))

        // Then - neither single-id key holds the result; the complete tuple is the cache primary
        val mbidKey = DefaultEnrichmentEngine.entityKeyFor(
            EnrichmentRequest.forTrack(
                title = "Starman",
                artist = "David Bowie",
                identifiers = EnrichmentIdentifiers(musicBrainzId = "starman-mbid"),
            ),
            EnrichmentType.TRACK_PREVIEW,
        )
        val deezerKey = DefaultEnrichmentEngine.entityKeyFor(
            EnrichmentRequest.forTrack(
                title = "Starman",
                artist = "David Bowie",
                identifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, "789"),
            ),
            EnrichmentType.TRACK_PREVIEW,
        )
        assertNull(cache.get(mbidKey, EnrichmentType.TRACK_PREVIEW))
        assertNull(cache.get(deezerKey, EnrichmentType.TRACK_PREVIEW))
    }
}
