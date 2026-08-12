package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeProvider
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two defects with one guard. Registration validated nothing, so a provider could claim `"engine"`
 * — the id the engine stamps on its own synthesised results and the key
 * `EnrichmentConfig.confidenceOverrides` is read with — and two providers could register the same
 * id, which makes them share one circuit breaker: the healthy one's successes reset the counter the
 * failing one is filling, so the failing one is asked forever.
 */
class ProviderIdGuardTest {

    private fun provider(id: String) = FakeProvider(
        id = id,
        capabilities = listOf(ProviderCapability(EnrichmentType.ARTIST_BIO, priority = 100)),
    ).also {
        it.givenResult(
            EnrichmentType.ARTIST_BIO,
            EnrichmentResult.Success(
                EnrichmentType.ARTIST_BIO, EnrichmentData.Biography("bio", id), id, 0.9f,
            ),
        )
    }

    @Test
    fun `a provider claiming a reserved engine id is rejected at registration`() {
        // Given - a builder and a provider whose id is the engine's own sentinel
        val builder = EnrichmentEngine.Builder()

        // When - registering it
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            builder.addProvider(provider("engine"))
        }

        // Then - it is refused, naming the id rather than failing later as a confidence override
        assertTrue(thrown.message!!.contains("'engine' is reserved"))
    }

    @Test
    fun `every reserved sentinel and the merger suffix are refused`() {
        // Given - the full set of ids the engine puts in a result's provider field
        val reserved = listOf(
            "engine", "all_providers", "no_provider", "no_merger", "no_composite_handler",
            "genre_merger",
        )

        // When - each is offered to a fresh builder
        val accepted = reserved.filter { id ->
            runCatching { EnrichmentEngine.Builder().addProvider(provider(id)) }.isSuccess
        }

        // Then - none of them registers
        assertTrue("these ids registered: $accepted", accepted.isEmpty())
    }

    @Test
    fun `a duplicate provider id is rejected rather than sharing one circuit breaker`() {
        // Given - a builder already holding a provider
        val builder = EnrichmentEngine.Builder().addProvider(provider("dupe"))

        // When - registering a second, distinct provider under the same id
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            builder.addProvider(provider("dupe"))
        }

        // Then - it is refused, naming the shared-breaker consequence
        assertTrue(thrown.message!!.contains("already registered"))
    }

    @Test
    fun `the registry refuses the same pair, whichever way it was assembled`() {
        // Given - two providers sharing an id, handed straight to the registry
        val duplicates = listOf(provider("dupe"), provider("dupe"))

        // When - constructing a registry over them
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            ProviderRegistry(duplicates)
        }

        // Then - the guard sits on the registry too, not only on the builder that usually fills it
        assertTrue(thrown.message!!.contains("already registered"))
    }

    @Test
    fun `an ordinary provider id still registers`() {
        // Given - a provider with an id resembling nothing the engine reserves
        val builder = EnrichmentEngine.Builder()

        // When - registering it and then a differently-named sibling
        builder.addProvider(provider("my_provider")).addProvider(provider("merger_of_things"))

        // Then - the guard rejected neither, so it is not simply refusing everything
        assertTrue(ProviderRegistry(listOf(provider("my_provider"))).supportedTypes().isNotEmpty())
    }
}
