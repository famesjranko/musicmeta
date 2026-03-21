package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability

open class FakeProvider(
    override val id: String = "fake",
    override val displayName: String = "Fake Provider",
    override val capabilities: List<ProviderCapability> = emptyList(),
    override val requiresApiKey: Boolean = false,
    override val isAvailable: Boolean = true,
    override val isIdentityProvider: Boolean = false,
) : EnrichmentProvider {
    private val results = mutableMapOf<EnrichmentType, EnrichmentResult>()
    private var identityResult: EnrichmentResult? = null
    val enrichCalls = mutableListOf<Pair<EnrichmentRequest, EnrichmentType>>()

    fun givenResult(type: EnrichmentType, result: EnrichmentResult) { results[type] = result }
    fun givenIdentityResult(result: EnrichmentResult) { identityResult = result }

    override suspend fun resolveIdentity(request: EnrichmentRequest): EnrichmentResult {
        val result = identityResult ?: return super.resolveIdentity(request)
        enrichCalls.add(request to EnrichmentType.GENRE)
        return result
    }

    override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult {
        enrichCalls.add(request to type)
        return results[type] ?: EnrichmentResult.NotFound(type, id)
    }
}
