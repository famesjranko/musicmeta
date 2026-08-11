package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.AttributionRequirement
import com.landofoz.musicmeta.CommercialUse
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.ProviderPolicies
import com.landofoz.musicmeta.testutil.FakeHttpClient
import org.junit.Assert.*
import org.junit.Test

class ProviderPolicyTest {

    // Every key field filled reflectively, not by name: the guard must register providers gated
    // behind keys that do not exist yet, and a hardcoded ApiKeyConfig would pass green when a new
    // gate is added without a policy.
    private fun allKeysSupplied(): ApiKeyConfig {
        val ctor = ApiKeyConfig::class.java.declaredConstructors
            .filter { c -> c.parameterTypes.isNotEmpty() && c.parameterTypes.all { it == String::class.java } }
            .maxByOrNull { it.parameterCount }
            ?: error("ApiKeyConfig has no all-String constructor; update this guard to fill its key fields")
        val args = Array<Any?>(ctor.parameterCount) { "test-key" }
        return ctor.newInstance(*args) as ApiKeyConfig
    }

    private fun allDefaultProviderIds(): List<String> =
        EnrichmentEngine.Builder()
            .httpClient(FakeHttpClient())
            .apiKeys(allKeysSupplied())
            .withDefaultProviders()
            .build()
            .getProviders()
            .map { it.id }

    @Test fun `every default provider including key-gated ones has a policy`() {
        // Given - every provider withDefaultProviders registers, keys supplied so none are skipped
        val ids = allDefaultProviderIds()

        // When - looking each one up in the registry
        val missing = ids.filter { ProviderPolicies[it] == null }

        // Then - none is missing; a new provider must ship its terms snapshot with it
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun `the registry ships no policy for a provider the engine does not register`() {
        // Given - the ids withDefaultProviders registers
        val ids = allDefaultProviderIds().toSet()

        // When - comparing them against the registry's keys
        val orphaned = ProviderPolicies.all.keys - ids

        // Then - the registry describes those providers and no others
        assertEquals(emptySet<String>(), orphaned)
    }

    @Test fun `a required attribution carries the words to render`() {
        // Given - the policies whose terms oblige a notice
        val required = ProviderPolicies.all.values.filter { it.attribution == AttributionRequirement.REQUIRED }

        // When - checking each for renderable text
        val wordless = required.filter { it.attributionNotice.isNullOrBlank() }

        // Then - REQUIRED without the words would leave a consumer unable to comply
        assertEquals(emptyList<String>(), wordless.map { it.providerId })
    }

    @Test fun `an unverified policy states no licence rather than guessing one`() {
        // Given - the policies whose terms could not be read
        val unverified = ProviderPolicies.all.values.filter { it.commercialUse == CommercialUse.UNVERIFIED }

        // When - reading what each claims about the data licence
        val claiming = unverified.filter { it.dataLicence != null }

        // Then - an unreadable terms page yields no licence claim
        assertEquals(emptyList<String>(), claiming.map { it.providerId })
        assertTrue(unverified.isNotEmpty())
    }
}
