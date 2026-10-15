package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.CatalogFilterMode
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.RadioDiscoveryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three per-type/per-provider override maps, and what a `config` command has to say to reach them. */
class ConfigOverrideTest {

    private val base = EnrichmentConfig(userAgent = "test/1.0 (+https://example.invalid)")

    private fun parse(key: String, value: String): Pair<OverrideResult?, String> {
        var result: OverrideResult? = null
        val output = captureOutput { term -> result = parseOverride(base, key, value, term) }
        return result to output
    }

    @Test
    fun `a ttl override is keyed by enrichment type`() {
        // Given - a type named by its alias and a lifetime in milliseconds
        val command = "art 60000"

        // When - parsing it as a ttl override
        val (result, _) = parse("ttl", command)

        // Then - the type maps to that lifetime and nothing else moves
        assertEquals(mapOf(EnrichmentType.ALBUM_ART to 60_000L), result?.config?.ttlOverrides)
        assertEquals(base.confidenceOverrides, result?.config?.confidenceOverrides)
    }

    @Test
    fun `a ttl override accepts a full type name and keeps the ones already set`() {
        // Given - a config that already carries one override
        val existing = base.copy(ttlOverrides = mapOf(EnrichmentType.ALBUM_ART to 1_000L))

        // When - adding a second by full type name
        var result: OverrideResult? = null
        captureOutput { term -> result = parseOverride(existing, "ttl", "ARTIST_BIO 2000", term) }

        // Then - both are present, since each command sets one key rather than replacing the map
        assertEquals(
            mapOf(EnrichmentType.ALBUM_ART to 1_000L, EnrichmentType.ARTIST_BIO to 2_000L),
            result?.config?.ttlOverrides,
        )
    }

    @Test
    fun `an unresolvable type name in a ttl override changes nothing and is reported`() {
        // Given - a typo where a type name belongs
        val command = "boi 60000"

        // When - parsing it as a ttl override
        val (result, output) = parse("ttl", command)

        // Then - no config comes back, so the caller leaves the engine alone, and the name is named
        assertNull(result)
        assertTrue(output, output.contains("boi"))
    }

    @Test
    fun `a confidence override is keyed by provider id`() {
        // Given - a provider id and a floor in the 0-1 range the config documents
        val command = "lastfm 0.75"

        // When - parsing it as a confidence override
        val (result, _) = parse("provider-confidence", command)

        // Then - the provider maps to that floor
        assertEquals(mapOf("lastfm" to 0.75f), result?.config?.confidenceOverrides)
    }

    @Test
    fun `a confidence override outside the 0-1 range is refused`() {
        // Given - a confidence expressed as a percentage rather than a fraction
        val command = "lastfm 75"

        // When - parsing it as a confidence override
        val (result, output) = parse("provider-confidence", command)

        // Then - nothing is set and the range is stated
        assertNull(result)
        assertTrue(output, output.contains("0.0-1.0"))
    }

    @Test
    fun `a priority override is keyed by provider and then by type`() {
        // Given - a provider, the type to reorder it for, and the new priority
        val command = "discogs art 90"

        // When - parsing it as a priority override
        val (result, _) = parse("priority", command)

        // Then - the nested map carries exactly that one entry
        assertEquals(
            mapOf("discogs" to mapOf(EnrichmentType.ALBUM_ART to 90)),
            result?.config?.priorityOverrides,
        )
    }

    @Test
    fun `a priority override keeps the other types already set for the same provider`() {
        // Given - a provider that already has one type overridden
        val existing = base.copy(
            priorityOverrides = mapOf("discogs" to mapOf(EnrichmentType.ALBUM_ART to 90)),
        )

        // When - overriding a second type for that provider
        var result: OverrideResult? = null
        captureOutput { term -> result = parseOverride(existing, "priority", "discogs bio 10", term) }

        // Then - the provider's inner map is merged, not replaced
        assertEquals(
            mapOf("discogs" to mapOf(EnrichmentType.ALBUM_ART to 90, EnrichmentType.ARTIST_BIO to 10)),
            result?.config?.priorityOverrides,
        )
    }

    @Test
    fun `an override command missing an argument changes nothing and states its usage`() {
        // Given - a ttl command carrying a type and no lifetime
        val command = "art"

        // When - parsing it as a ttl override
        val (result, output) = parse("ttl", command)

        // Then - nothing is set and the shape is spelled out
        assertNull(result)
        assertTrue(output, output.contains("Usage: config ttl"))
    }

    @Test
    fun `the overrides that are set are printed with the rest of the configuration`() {
        // Given - a config carrying one of each kind of override
        val config = base.copy(
            ttlOverrides = mapOf(EnrichmentType.ALBUM_ART to 60_000L),
            confidenceOverrides = mapOf("lastfm" to 0.75f),
            priorityOverrides = mapOf("discogs" to mapOf(EnrichmentType.ALBUM_ART to 90)),
        )

        // When - printing the configuration
        val output = captureOutput { term ->
            InfoFormatter.printConfig(
                config,
                verbose = false,
                catalogMode = CatalogFilterMode.UNFILTERED,
                httpBackend = HttpBackend.DEFAULT,
                radioMode = RadioDiscoveryMode.EASY,
                term = term,
            )
        }

        // Then - all three maps are visible, each as the command that would set it again
        assertTrue(output, output.contains("ttl ALBUM_ART 60000"))
        assertTrue(output, output.contains("provider-confidence lastfm 0.75"))
        assertTrue(output, output.contains("priority discogs ALBUM_ART 90"))
    }
}
