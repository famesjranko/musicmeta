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
        // Given — the GenreMerger object
        // When — its type is read
        // Then — it is GENRE
        assertEquals(EnrichmentType.GENRE, GenreMerger.type)
    }

    @Test
    fun `GenreMerger merge with empty list returns NotFound`() {
        // Given — an empty list of successful results
        val results = emptyList<EnrichmentResult.Success>()

        // When — merging the empty list
        val result = GenreMerger.merge(results)

        // Then — the merge yields NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `GenreMerger merge with success containing genreTags returns merged Success`() {
        // Given — a single success carrying two genre tags
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

        // When — merging the single-element list
        val result = GenreMerger.merge(listOf(success))

        // Then — the result is a Success attributed to genre_merger with the tags carried through
        assertTrue(result is EnrichmentResult.Success)
        val merged = result as EnrichmentResult.Success
        assertEquals("genre_merger", merged.provider)
        val mergedTags = (merged.data as? EnrichmentData.Metadata)?.genreTags
        assertNotNull(mergedTags)
        assertTrue(mergedTags!!.isNotEmpty())
    }

    @Test
    fun `GenreMerger merge with success without genreTags returns first success as fallback`() {
        // Given — a single success with legacy genres but no genreTags
        val success = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genres = listOf("Rock")),
            provider = "test_provider",
            confidence = 0.7f,
        )

        // When — merging the single-element list
        val result = GenreMerger.merge(listOf(success))

        // Then — the first success is returned unchanged, no genreTags to merge
        // Fallback: returns the first success as-is (no genreTags to merge)
        assertTrue(result is EnrichmentResult.Success)
        assertEquals("test_provider", (result as EnrichmentResult.Success).provider)
    }

    @Test
    fun `GenreMerger resolvedIdentifiers propagated from first result with identifiers`() {
        // Given — a single success carrying resolvedIdentifiers with a musicBrainzId
        val ids = EnrichmentIdentifiers(musicBrainzId = "test-mbid")
        val success = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genreTags = listOf(GenreTag("Rock", 0.8f))),
            provider = "test",
            confidence = 0.9f,
            resolvedIdentifiers = ids,
        )

        // When — merging the single-element list
        val result = GenreMerger.merge(listOf(success)) as EnrichmentResult.Success

        // Then — the merged result propagates the source musicBrainzId
        assertEquals("test-mbid", result.resolvedIdentifiers?.musicBrainzId)
    }
}
