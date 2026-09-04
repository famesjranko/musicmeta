package com.landofoz.musicmeta

import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the interface itself promises an implementation that overrides nothing optional — the
 * behaviour a third-party engine inherits, which the published API dump cannot show.
 */
class EnrichmentEngineTest {

    /** An engine with no provider registry at all: only the interface's own defaults answer. */
    private class BareEngine : EnrichmentEngine {
        override val cache = InMemoryEnrichmentCache()
        override suspend fun enrich(
            request: EnrichmentRequest,
            types: Set<EnrichmentType>,
            forceRefresh: Boolean,
        ): Nothing = throw UnsupportedOperationException()

        override suspend fun search(request: EnrichmentRequest, limit: Int): List<SearchCandidate> = emptyList()
        override fun getProviders(): List<ProviderInfo> = emptyList()
        override suspend fun invalidate(request: EnrichmentRequest, type: EnrichmentType?) = Unit
        override suspend fun isManuallySelected(request: EnrichmentRequest, type: EnrichmentType): Boolean = false
        override suspend fun markManuallySelected(request: EnrichmentRequest, type: EnrichmentType) = Unit
    }

    @Test
    fun `an engine with nothing to probe refuses the identifier rather than calling it absent`() = runTest {
        // Given - an engine that overrides nothing and so has no MusicBrainz provider to probe
        val engine = BareEngine()

        // When - it is asked what an identifier names
        val thrown = runCatching { engine.discoverMbidEntityType(MBID) }.exceptionOrNull()

        // Then - it says it cannot answer, since null would read as an answer about the identifier
        assertTrue(thrown is IllegalStateException)
        assertTrue(thrown!!.message!!.contains("MusicBrainz identity provider"))
    }

    private companion object {
        const val MBID = "b1a9c0e9-d987-4042-ae91-78d6a3267d69"
    }
}
