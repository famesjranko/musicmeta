package com.landofoz.musicmeta.contract

import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.testkit.TestStack
import com.landofoz.musicmeta.testkit.UpstreamPools

/**
 * The real default provider stack under [ProviderProvenanceContract]. There is deliberately one
 * subclass: the contract's subject is the engine, not a provider, so a defect in any of the twelve
 * registered providers' route reporting is covered without an implementation per provider.
 */
class DefaultEnrichmentEngineProvenanceContractTest : ProviderProvenanceContract() {
    override fun subject(): EnrichmentEngine = TestStack.build(UpstreamPools.load(LYRICS_SCENARIO))

    private companion object {
        const val LYRICS_SCENARIO = "lrclib-qualifier-match"
    }
}
