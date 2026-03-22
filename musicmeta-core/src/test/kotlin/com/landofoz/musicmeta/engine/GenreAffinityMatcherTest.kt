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
        assertEquals(2_592_000_000L, EnrichmentType.GENRE_DISCOVERY.defaultTtlMs)
    }

    @Test
    fun `GenreAffinity round-trip serialization works`() {
        // Given
        val affinity = GenreAffinity(
            name = "indie rock",
            affinity = 0.81f,
            relationship = "sibling",
            sourceGenres = listOf("alternative rock"),
        )

        // When
        val json = Json.encodeToString(GenreAffinity.serializer(), affinity)
        val decoded = Json.decodeFromString(GenreAffinity.serializer(), json)

        // Then
        assertEquals(affinity, decoded)
    }

    @Test
    fun `GenreDiscovery round-trip serialization works`() {
        // Given
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

        // When
        val json = Json.encodeToString(EnrichmentData.GenreDiscovery.serializer(), discovery)
        val decoded = Json.decodeFromString(EnrichmentData.GenreDiscovery.serializer(), json)

        // Then
        assertEquals(discovery, decoded)
    }

    // --- Task 2: GenreAffinityMatcher.synthesize() tests ---

    @Test
    fun `synthesize returns NotFound when GENRE result is missing`() {
        // Given
        val resolved = emptyMap<EnrichmentType, EnrichmentResult>()

        // When
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals(EnrichmentType.GENRE_DISCOVERY, (result as EnrichmentResult.NotFound).type)
    }

    @Test
    fun `synthesize returns NotFound when GENRE result is NotFound`() {
        // Given
        val resolved = mapOf(
            EnrichmentType.GENRE to EnrichmentResult.NotFound(EnrichmentType.GENRE, "no_data"),
        )

        // When
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `synthesize returns NotFound when genre tags list is empty`() {
        // Given
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(emptyList()),
        )

        // When
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `synthesize returns NotFound for unknown genre tags`() {
        // Given
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(listOf(GenreTag("zork_xenomorph", 0.9f))),
        )

        // When
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then
        // Unknown genre → no taxonomy entries → effectively empty results → NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `synthesize returns Success with related genres for known input genre`() {
        // Given
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(listOf(GenreTag("rock", 1.0f))),
        )

        // When
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.GenreDiscovery
        assertTrue(data.relatedGenres.isNotEmpty())
    }

    @Test
    fun `synthesize computes affinity as confidence times relationship weight`() {
        // Given: "rock" has sibling "blues" at weight 0.9 (but checking child "alternative rock" at 0.8)
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(listOf(GenreTag("rock", 1.0f))),
        )

        // When
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then: alternative rock is a child (weight 0.8) of rock; affinity = 1.0 * 0.8 = 0.8
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.GenreDiscovery
        val altRock = data.relatedGenres.find { it.name == "alternative rock" }
        assertTrue("alternative rock should be in results", altRock != null)
        assertEquals(0.8f, altRock!!.affinity, 0.001f)
        assertEquals("child", altRock.relationship)
    }

    @Test
    fun `synthesize results are sorted by affinity descending`() {
        // Given
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(listOf(GenreTag("rock", 1.0f))),
        )

        // When
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then
        val genres = ((result as EnrichmentResult.Success).data as EnrichmentData.GenreDiscovery).relatedGenres
        val affinities = genres.map { it.affinity }
        assertEquals(affinities.sortedDescending(), affinities)
    }

    @Test
    fun `synthesize deduplicates by name keeping highest affinity`() {
        // Given: both "rock" and "hard rock" map to "classic rock" as sibling
        // rock → classic rock (child, 0.8): affinity = 0.8 * 0.8 = 0.64
        // hard rock → classic rock (sibling, 0.9): affinity = 0.7 * 0.9 = 0.63
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(listOf(
                GenreTag("rock", 0.8f),
                GenreTag("hard rock", 0.7f),
            )),
        )

        // When
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then: "classic rock" should appear only once
        val genres = ((result as EnrichmentResult.Success).data as EnrichmentData.GenreDiscovery).relatedGenres
        val classicRockCount = genres.count { it.name == "classic rock" }
        assertEquals(1, classicRockCount)
    }

    @Test
    fun `synthesize sourceGenres contains normalized input genre name`() {
        // Given
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(listOf(GenreTag("Alternative Rock", 0.9f))),
        )

        // When
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then: sourceGenres should contain normalized form
        val genres = ((result as EnrichmentResult.Success).data as EnrichmentData.GenreDiscovery).relatedGenres
        assertTrue(genres.isNotEmpty())
        assertTrue(genres.all { it.sourceGenres.contains("alternative rock") })
    }

    @Test
    fun `synthesize provider string is genre_affinity_matcher`() {
        // Given
        val resolved = mapOf(
            EnrichmentType.GENRE to genreResult(listOf(GenreTag("jazz", 0.9f))),
        )

        // When
        val result = GenreAffinityMatcher.synthesize(resolved, null, fakeRequest())

        // Then
        assertEquals("genre_affinity_matcher", (result as EnrichmentResult.Success).provider)
    }

    @Test
    fun `synthesize type is GENRE_DISCOVERY`() {
        assertEquals(EnrichmentType.GENRE_DISCOVERY, GenreAffinityMatcher.type)
    }

    @Test
    fun `synthesize dependencies contains GENRE`() {
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
