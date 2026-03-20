package com.cascade.enrichment.testutil

import com.cascade.enrichment.EnrichmentProvider
import com.cascade.enrichment.EnrichmentRequest
import com.cascade.enrichment.EnrichmentResult
import com.cascade.enrichment.EnrichmentType
import com.cascade.enrichment.ProviderCapability

open class FakeProvider(
    override val id: String = "fake",
    override val displayName: String = "Fake Provider",
    override val capabilities: List<ProviderCapability> = emptyList(),
    override val requiresApiKey: Boolean = false,
    override val isAvailable: Boolean = true,
) : EnrichmentProvider {
    private val results = mutableMapOf<EnrichmentType, EnrichmentResult>()
    val enrichCalls = mutableListOf<Pair<EnrichmentRequest, EnrichmentType>>()

    fun givenResult(type: EnrichmentType, result: EnrichmentResult) { results[type] = result }

    override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
        enrichCalls.add(request to type)
        return results[type] ?: EnrichmentResult.NotFound(type, id)
    }
}
