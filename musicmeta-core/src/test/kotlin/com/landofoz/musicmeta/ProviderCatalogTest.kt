package com.landofoz.musicmeta

import com.landofoz.musicmeta.testutil.FakeHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {

    private val httpClient = FakeHttpClient()

    private val fullyKeyedConfig = ApiKeyConfig(
        lastFmKey = "test-lastfm-key",
        fanartTvProjectKey = "test-fanarttv-key",
        discogsPersonalToken = "test-discogs-token",
        listenBrainzToken = "test-listenbrainz-token",
    )

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
        assertTrue("Mismatched display names: $mismatches", mismatches.isEmpty())
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

    @Test fun `Required entry registers only when its own field is set`() {
        // Given - each Required catalog entry, keyed with only its own field
        val requiredEntries = ProviderCatalog.entries.filter { it.keyRequirement is KeyRequirement.Required }

        requiredEntries.forEach { entry ->
            val requirement = entry.keyRequirement as KeyRequirement.Required
            val config = when (requirement.fieldName) {
                "lastFmKey" -> ApiKeyConfig(lastFmKey = "solo-key")
                "fanartTvProjectKey" -> ApiKeyConfig(fanartTvProjectKey = "solo-key")
                "discogsPersonalToken" -> ApiKeyConfig(discogsPersonalToken = "solo-key")
                else -> error("Unhandled Required field: ${requirement.fieldName}")
            }

            // When - building an engine keyed with only that field
            val registeredIds = buildEngine(config).getProviders().map { it.id }.toSet()

            // Then - that entry's id is registered, and the selector reads the field that fed it
            assertTrue("${entry.id} should register with only its own key set", entry.id in registeredIds)
            assertEquals("solo-key", requirement.key(config))
        }
    }

    @Test fun `Optional listenbrainz entry always registers and its selector reads the token`() {
        // Given - the ListenBrainz catalog entry and a config setting only its token
        val entry = ProviderCatalog.entries.single { it.id == "listenbrainz" }
        val requirement = entry.keyRequirement as KeyRequirement.Optional
        val config = ApiKeyConfig(listenBrainzToken = "lb-token")

        // When - building keyless and token-only engines
        val keylessIds = buildEngine(apiKeyConfig = null).getProviders().map { it.id }.toSet()
        val keyedIds = buildEngine(config).getProviders().map { it.id }.toSet()

        // Then - ListenBrainz registers either way, and the selector returns the set token
        assertTrue("listenbrainz" in keylessIds)
        assertTrue("listenbrainz" in keyedIds)
        assertEquals("lb-token", requirement.key(config))
    }
}
