package com.landofoz.musicmeta.contract

import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache

/**
 * The test double under the same contract as the shipped caches. A fake that drifts from the
 * interface it stands in for makes every test using it a test of something that does not exist.
 */
class FakeEnrichmentCacheContractTest : EnrichmentCacheContract() {
    override fun subject(): EnrichmentCache = FakeEnrichmentCache()
}
