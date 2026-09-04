package com.landofoz.musicmeta

import com.landofoz.musicmeta.testutil.FakeHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {

    private val httpClient = FakeHttpClient()

    private val fullyKeyedConfig = ApiKeyConfig(ApiKey.entries.associateWith { "test-key" })

    private fun buildEngine(apiKeyConfig: ApiKeyConfig? = null) =
        EnrichmentEngine.Builder()
            .httpClient(httpClient)
            .apply { if (apiKeyConfig != null) apiKeys(apiKeyConfig) }
            .withDefaultProviders()
            .build()

    @Test fun `catalog ids match the ids a fully-keyed engine registers`() {
        // Given - a fully-keyed default-providers engine
        val engine = buildEngine(fullyKeyedConfig)

        // When - comparing catalog ids against the engine's registered ids
        val catalogIds = ProviderCatalog.entries.map { it.id }.toSet()
        val registeredIds = engine.getProviders().map { it.id }.toSet()

        // Then - the two sets are identical
        assertEquals(registeredIds, catalogIds)
    }

    @Test fun `catalog display names match ProviderInfo display names id by id`() {
        // Given - a fully-keyed default-providers engine
        val engine = buildEngine(fullyKeyedConfig)
        val registeredById = engine.getProviders().associateBy { it.id }

        // When - looking up each catalog entry's registered counterpart
        val mismatches = ProviderCatalog.entries.filter {
            registeredById.getValue(it.id).displayName != it.displayName
        }

        // Then - no entry's display name differs from the registered provider's
        val details = mismatches.map { it.id to (it.displayName to registeredById.getValue(it.id).displayName) }
        assertTrue("Mismatched display names (id to (catalog, registered)): $details", mismatches.isEmpty())
    }

    @Test fun `keyless engine is missing exactly the Required catalog ids`() {
        // Given - a default-providers engine built with no ApiKeyConfig
        val engine = buildEngine(apiKeyConfig = null)

        // When - comparing catalog ids absent from the keyless registration
        val registeredIds = engine.getProviders().map { it.id }.toSet()
        val missingIds = ProviderCatalog.entries.map { it.id }.toSet() - registeredIds
        val requiredIds = ProviderCatalog.entries
            .filter { it.keyRequirement is KeyRequirement.Required }
            .map { it.id }
            .toSet()

        // Then - the missing ids are exactly the Required ones
        assertEquals(requiredIds, missingIds)
    }

    @Test fun `every catalog id is a key of ProviderPolicies all`() {
        // Given - the full provider catalog
        val catalogIds = ProviderCatalog.entries.map { it.id }

        // When - checking each id against the provider policy table
        val unpolicedIds = catalogIds.filterNot { ProviderPolicies.all.containsKey(it) }

        // Then - every catalog id has a policy
        assertTrue("Catalog ids missing a policy: $unpolicedIds", unpolicedIds.isEmpty())
    }

    @Test fun `Required entry registers only when its own key is set`() {
        // Given - each Required catalog entry, keyed with only its own ApiKey
        val requiredEntries = ProviderCatalog.entries.filter { it.keyRequirement is KeyRequirement.Required }

        requiredEntries.forEach { entry ->
            val requirement = entry.keyRequirement as KeyRequirement.Required
            val config = ApiKeyConfig.of(requirement.key to "solo-key")

            // When - building an engine keyed with only that key
            val registeredIds = buildEngine(config).getProviders().map { it.id }.toSet()

            // Then - that entry's id is registered, and the config reads back the key that fed it
            assertTrue("${entry.id} should register with only its own key set", entry.id in registeredIds)
            assertEquals("solo-key", config[requirement.key])
        }
    }

    @Test fun `every ApiKey constant is named by exactly one catalog entry`() {
        // Given - the ApiKey each keyed catalog entry names
        val named = ProviderCatalog.entries.mapNotNull {
            when (val requirement = it.keyRequirement) {
                is KeyRequirement.Required -> requirement.key
                is KeyRequirement.Optional -> requirement.key
                KeyRequirement.None -> null
            }
        }

        // When - counting how many entries name each constant
        val timesNamed = ApiKey.entries.associateWith { key -> named.count { it == key } }

        // Then - every constant is named exactly once, so no key is unreachable or double-gated
        assertEquals(ApiKey.entries.associateWith { 1 }, timesNamed)
    }

    @Test fun `Optional listenbrainz entry always registers and its selector reads the token`() {
        // Given - the ListenBrainz catalog entry and a config setting only its token
        val entry = ProviderCatalog.entries.single { it.id == "listenbrainz" }
        val requirement = entry.keyRequirement as KeyRequirement.Optional
        val config = ApiKeyConfig.of(requirement.key to "lb-token")

        // When - building keyless and token-only engines
        val keylessIds = buildEngine(apiKeyConfig = null).getProviders().map { it.id }.toSet()
        val keyedIds = buildEngine(config).getProviders().map { it.id }.toSet()

        // Then - ListenBrainz registers either way, and the config returns the set token
        assertTrue("listenbrainz" in keylessIds)
        assertTrue("listenbrainz" in keyedIds)
        assertEquals("lb-token", config[requirement.key])
    }
}
