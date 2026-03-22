package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.GenreTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultMergerTest {

    @Test
    fun `GenreMerger type is GENRE`() {
        // Given / When / Then
        assertEquals(EnrichmentType.GENRE, GenreMerger.type)
    }

    @Test
    fun `GenreMerger merge with empty list returns NotFound`() {
        // Given
        val results = emptyList<EnrichmentResult.Success>()

        // When
        val result = GenreMerger.merge(results)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `GenreMerger merge with success containing genreTags returns merged Success`() {
        // Given
        val tags = listOf(
            GenreTag(name = "Rock", confidence = 0.8f, sources = listOf("mb")),
            GenreTag(name = "Alternative", confidence = 0.6f, sources = listOf("lastfm")),
        )
        val success = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genreTags = tags),
            provider = "test",
            confidence = 0.9f,
        )

        // When
        val result = GenreMerger.merge(listOf(success))

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val merged = result as EnrichmentResult.Success
        assertEquals("genre_merger", merged.provider)
        val mergedTags = (merged.data as? EnrichmentData.Metadata)?.genreTags
        assertNotNull(mergedTags)
        assertTrue(mergedTags!!.isNotEmpty())
    }

    @Test
    fun `GenreMerger merge with success without genreTags returns first success as fallback`() {
        // Given
        val success = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genres = listOf("Rock")),
            provider = "test_provider",
            confidence = 0.7f,
        )

        // When
        val result = GenreMerger.merge(listOf(success))

        // Then
        // Fallback: returns the first success as-is (no genreTags to merge)
        assertTrue(result is EnrichmentResult.Success)
        assertEquals("test_provider", (result as EnrichmentResult.Success).provider)
    }

    @Test
    fun `GenreMerger resolvedIdentifiers propagated from first result with identifiers`() {
        // Given
        val ids = EnrichmentIdentifiers(musicBrainzId = "test-mbid")
        val success = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genreTags = listOf(GenreTag("Rock", 0.8f))),
            provider = "test",
            confidence = 0.9f,
            resolvedIdentifiers = ids,
        )

        // When
        val result = GenreMerger.merge(listOf(success)) as EnrichmentResult.Success

        // Then
        assertEquals("test-mbid", result.resolvedIdentifiers?.musicBrainzId)
    }
}
