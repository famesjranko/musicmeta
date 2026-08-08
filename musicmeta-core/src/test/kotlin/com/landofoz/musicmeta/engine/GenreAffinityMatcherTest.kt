package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.GenreAffinity
import com.landofoz.musicmeta.GenreTag
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreAffinityMatcherTest {

    // --- Task 1: Data model + serialization tests ---

    @Test
    fun `GENRE_DISCOVERY has 30-day TTL`() {
        // Given — the GENRE_DISCOVERY enrichment type
        // When — reading its default TTL
        // Then — the TTL is 30 days in milliseconds
        assertEquals(2_592_000_000L, EnrichmentType.GENRE_DISCOVERY.defaultTtlMs)
    }

    @Test
    fun `GenreAffinity round-trip serialization works`() {
        // Given — a GenreAffinity instance
        val affinity = GenreAffinity(
            name = "indie rock",
            affinity = 0.81f,
            relationship = "sibling",
            sourceGenres = listOf("alternative rock"),
        )

        // When — encoding to JSON then decoding back
        val json = Json.encodeToString(GenreAffinity.serializer(), affinity)
        val decoded = Json.decodeFromString(GenreAffinity.serializer(), json)

        // Then — the decoded value equals the original
        assertEquals(affinity, decoded)
    }

    @Test
    fun `GenreDiscovery round-trip serialization works`() {
        // Given — a GenreDiscovery containing one related genre
        val discovery = EnrichmentData.GenreDiscovery(
            relatedGenres = listOf(
                GenreAffinity(
                    name = "indie rock",
                    affinity = 0.81f,
                    relationship = "sibling",
                    sourceGenres = listOf("alternative rock"),
                ),
            ),
        )

        // When — encoding to JSON then decoding back
        val json = Json.encodeToString(EnrichmentData.GenreDiscovery.serializer(), discovery)
        val decoded = Json.decodeFromString(EnrichmentData.GenreDiscovery.serializer(), json)

        // Then — the decoded value equals the original
        assertEquals(discovery, decoded)
    }

    // --- Task 2: GenreAffinityMatcher.synthesize() tests ---

    @Test
    fun `synthesize returns NotFound when GENRE result is missing`() {
        // Given — a resolved map with no GENRE entry
        val resolved = emptyMap<EnrichmentType, EnrichmentResult>()

        // When — synthesizing genre discovery
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then — a NotFound for GENRE_DISCOVERY is returned
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals(EnrichmentType.GENRE_DISCOVERY, (result as EnrichmentResult.NotFound).type)
    }

    @Test
    fun `synthesize returns NotFound when GENRE result is NotFound`() {
        // Given — a resolved map whose GENRE entry is itself NotFound
        val resolved = mapOf(
            EnrichmentType.GENRE to EnrichmentResult.NotFound(EnrichmentType.GENRE, "no_data"),
        )

        // When — synthesizing genre discovery
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then — a NotFound is returned
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `synthesize returns NotFound when genre tags list is empty`() {
        // Given — a GENRE result with an empty genre tags list
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(emptyList()),
        )

        // When — synthesizing genre discovery
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then — a NotFound is returned
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `synthesize returns NotFound for unknown genre tags`() {
        // Given — a GENRE result containing only an unrecognized genre tag
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(listOf(GenreTag("zork_xenomorph", 0.9f))),
        )

        // When — synthesizing genre discovery
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then — unknown genre yields no taxonomy entries, so a NotFound is returned
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `synthesize returns Success with related genres for known input genre`() {
        // Given — a GENRE result with the known genre tag "rock"
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(listOf(GenreTag("rock", 1.0f))),
        )

        // When — synthesizing genre discovery
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then — a Success with a non-empty related genres list is returned
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.GenreDiscovery
        assertTrue(data.relatedGenres.isNotEmpty())
    }

    @Test
    fun `synthesize computes affinity as confidence times relationship weight`() {
        // Given — the known genre tag "rock", whose taxonomy child "alternative rock" has weight 0.8
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(listOf(GenreTag("rock", 1.0f))),
        )

        // When — synthesizing genre discovery
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then — alternative rock is a child (weight 0.8) of rock; affinity = 1.0 * 0.8 = 0.8
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.GenreDiscovery
        val altRock = data.relatedGenres.find { it.name == "alternative rock" }
        assertTrue("alternative rock should be in results", altRock != null)
        assertEquals(0.8f, altRock!!.affinity, 0.001f)
        assertEquals("child", altRock.relationship)
    }

    @Test
    fun `synthesize results are sorted by affinity descending`() {
        // Given — a GENRE result with the known genre tag "rock"
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(listOf(GenreTag("rock", 1.0f))),
        )

        // When — synthesizing genre discovery
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then — related genres are ordered by descending affinity
        val genres = ((result as EnrichmentResult.Success).data as EnrichmentData.GenreDiscovery).relatedGenres
        val affinities = genres.map { it.affinity }
        assertEquals(affinities.sortedDescending(), affinities)
    }

    @Test
    fun `synthesize deduplicates by name keeping highest affinity`() {
        // Given — both "rock" and "hard rock" map to "classic rock": rock → classic rock (child,
        // 0.8) is affinity 0.8 * 0.8 = 0.64, hard rock → classic rock (sibling, 0.9) is affinity
        // 0.7 * 0.9 = 0.63
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(listOf(
                GenreTag("rock", 0.8f),
                GenreTag("hard rock", 0.7f),
            )),
        )

        // When — synthesizing genre discovery
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then — "classic rock" appears only once, keeping the higher affinity
        val genres = ((result as EnrichmentResult.Success).data as EnrichmentData.GenreDiscovery).relatedGenres
        val classicRockCount = genres.count { it.name == "classic rock" }
        assertEquals(1, classicRockCount)
    }

    @Test
    fun `synthesize sourceGenres contains normalized input genre name`() {
        // Given — a GENRE result with an unnormalized genre tag name
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(listOf(GenreTag("Alternative Rock", 0.9f))),
        )

        // When — synthesizing genre discovery
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then — sourceGenres contains the normalized form
        val genres = ((result as EnrichmentResult.Success).data as EnrichmentData.GenreDiscovery).relatedGenres
        assertTrue(genres.isNotEmpty())
        assertTrue(genres.all { it.sourceGenres.contains("alternative rock") })
    }

    @Test
    fun `synthesize provider string is genre_affinity_matcher`() {
        // Given — a GENRE result with the known genre tag "jazz"
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(listOf(GenreTag("jazz", 0.9f))),
        )

        // When — synthesizing genre discovery
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then — the Success result's provider string is "genre_affinity_matcher"
        assertEquals("genre_affinity_matcher", (result as EnrichmentResult.Success).provider)
    }

    @Test
    fun `synthesize type is GENRE_DISCOVERY`() {
        // Given — the GenreAffinityMatcher singleton
        // When — reading its declared enrichment type
        // Then — it is GENRE_DISCOVERY
        assertEquals(EnrichmentType.GENRE_DISCOVERY, GenreAffinityMatcher.type)
    }

    @Test
    fun `synthesize dependencies contains GENRE`() {
        // Given — the GenreAffinityMatcher singleton
        // When — reading its declared dependencies
        // Then — GENRE is among them
        assertTrue(EnrichmentType.GENRE in GenreAffinityMatcher.dependencies)
    }

    // --- Helpers ---

    private fun genreResult(tags: List<GenreTag>): EnrichmentResult = EnrichmentResult.Success(
        type = EnrichmentType.GENRE,
        data = EnrichmentData.Metadata(genreTags = tags),
        provider = "test",
        confidence = 1.0f,
    )

    private fun fakeRequest() = com.landofoz.musicmeta.EnrichmentRequest.ForArtist(
        identifiers = com.landofoz.musicmeta.EnrichmentIdentifiers(),
        name = "Test Artist",
    )
}
