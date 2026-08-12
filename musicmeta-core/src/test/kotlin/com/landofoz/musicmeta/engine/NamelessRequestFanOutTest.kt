package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.deezer.DeezerProvider
import com.landofoz.musicmeta.provider.lrclib.LrcLibProvider
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a request identity resolution could not name is allowed to spend upstream: nothing, on any
 * provider that searches by name.
 *
 * `EnrichmentRequest.forTrackByMbid` and its siblings leave the names for identity resolution to
 * fill, and roughly half of real third-party recording MBIDs are held under no entity type — so a
 * blank name reaching the fan-out is the common case, not the corner. A name-search provider asked
 * with one queries the empty string and answers with whatever ranks first for it, which is a wrong
 * answer dressed as a right one, on top of a live request per provider. Pinned across the whole
 * fan-out rather than in MusicBrainz, because MusicBrainz's own guard covers exactly one of the
 * providers this costs.
 */
class NamelessRequestFanOutTest {

    private val httpClient = FakeHttpClient()

    private fun engine() = DefaultEnrichmentEngine(
        ProviderRegistry(
            listOf(
                MusicBrainzProvider(httpClient, RateLimiter(0)),
                DeezerProvider(httpClient, RateLimiter(0)),
                LrcLibProvider(httpClient, RateLimiter(0)),
            ),
        ),
        FakeEnrichmentCache(),
        EnrichmentConfig(),
        mergers = emptyList(),
    )

    @Test
    fun `a dead identifier spends nothing on the providers that search by name`() = runTest {
        // Given - nothing stubbed, so MusicBrainz answers 404 for the identifier as it does for a
        // dead one, leaving identity resolution no entity to take names from
        val types = setOf(EnrichmentType.LYRICS_PLAIN, EnrichmentType.TRACK_POPULARITY)

        // When - a request naming only that identifier is enriched
        val results = engine().enrich(EnrichmentRequest.forTrackByMbid(DEAD_MBID), types)

        // Then - Deezer and LRCLIB were never called, and MusicBrainz spent only the lookup that
        // established the identifier is dead
        val nameSearches = httpClient.requestedUrls.filterNot { it.contains("musicbrainz.org") }
        assertEquals(emptyList<String>(), nameSearches)
        // Then - every type is a plain NotFound: nobody could answer, and nobody failed
        assertTrue(types.all { results.raw[it] is EnrichmentResult.NotFound })
    }

    @Test
    fun `a request that names an entity still reaches them`() = runTest {
        // Given - the same providers and the same absent MusicBrainz, with a title and artist
        // When - the request names the track rather than an identifier
        engine().enrich(
            EnrichmentRequest.forTrack("Under Pressure", "Queen"),
            setOf(EnrichmentType.LYRICS_PLAIN),
        )

        // Then - LRCLIB was asked, so the gate above turns on the blank name and nothing else
        assertTrue(httpClient.requestedUrls.any { it.contains("lrclib.net") })
    }

    private companion object {
        /** Probed 2026-08-12: recording, release and artist lookups all answered 404 for this id. */
        const val DEAD_MBID = "60d043af-b702-30d9-b6c0-0688d7863b14"
    }
}
