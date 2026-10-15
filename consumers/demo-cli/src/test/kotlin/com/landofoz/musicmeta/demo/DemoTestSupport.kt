package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.ProviderInfo
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.demo.ui.Terminal
import com.landofoz.musicmeta.demo.ui.Theme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Everything the demo prints goes through `println`, so a test reads it off a swapped `System.out`. */
internal fun captureOutput(block: (Terminal) -> Unit): String {
    val buffer = ByteArrayOutputStream()
    val original = System.out
    System.setOut(PrintStream(buffer))
    try {
        block(Terminal(Theme.Plain))
    } finally {
        System.setOut(original)
    }
    return buffer.toString()
}

/** An [EnrichmentResults] carrying exactly [raw], with identity resolved and nothing else pending. */
internal fun resultsOf(
    raw: Map<EnrichmentType, EnrichmentResult>,
    requestedTypes: Set<EnrichmentType> = raw.keys,
): EnrichmentResults = EnrichmentResults(
    raw = raw,
    requestedTypes = requestedTypes,
    identity = IdentityResolution(EnrichmentIdentifiers(), CanonicalStatus.RESOLVED),
)

/**
 * An engine that answers from [snapshots] and records what it was asked to invalidate or pin, so a
 * command's use of the engine can be asserted without reaching a provider.
 */
internal class FakeEngine(
    private val snapshots: List<EnrichmentResults> = emptyList(),
    /** Thrown from [enrich], for pinning what happens to the engine when a command fails. */
    private val failWith: Throwable? = null,
) : EnrichmentEngine {

    val invalidated = mutableListOf<Pair<EnrichmentRequest, EnrichmentType?>>()
    val pinned = mutableListOf<Pair<EnrichmentRequest, EnrichmentType>>()
    var closed = false

    override val cache: EnrichmentCache = InMemoryEnrichmentCache()

    override suspend fun enrich(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean,
    ): EnrichmentResults {
        failWith?.let { throw it }
        return snapshots.lastOrNull() ?: resultsOf(emptyMap(), types)
    }

    override fun enrichProgressive(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean,
    ): Flow<EnrichmentResults> = snapshots.asFlow()

    override suspend fun search(request: EnrichmentRequest, limit: Int): List<SearchCandidate> = emptyList()

    override fun getProviders(): List<ProviderInfo> = emptyList()

    override fun close() {
        closed = true
    }

    override suspend fun invalidate(request: EnrichmentRequest, type: EnrichmentType?) {
        invalidated += request to type
    }

    override suspend fun isManuallySelected(request: EnrichmentRequest, type: EnrichmentType): Boolean =
        (request to type) in pinned

    override suspend fun markManuallySelected(request: EnrichmentRequest, type: EnrichmentType) {
        pinned += request to type
    }
}
