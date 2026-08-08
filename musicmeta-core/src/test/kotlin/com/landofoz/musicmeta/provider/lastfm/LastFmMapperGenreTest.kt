package com.landofoz.musicmeta.provider.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastFmMapperGenreTest {

    @Test
    fun `toGenre populates genreTags with 0_3f confidence and lastfm source`() {
        // Given — three tags
        val tags = listOf("indie", "rock", "alternative")

        // When — mapping tags to genre metadata
        val metadata = LastFmMapper.toGenre(tags)

        // Then — each genreTag has 0.3f confidence and lastfm source
        val genreTags = metadata.genreTags
        assertTrue(genreTags != null)
        assertEquals(3, genreTags!!.size)
        assertEquals("indie", genreTags[0].name)
        assertEquals(0.3f, genreTags[0].confidence)
        assertEquals(listOf("lastfm"), genreTags[0].sources)
        assertEquals("rock", genreTags[1].name)
        assertEquals(0.3f, genreTags[1].confidence)
        assertEquals(listOf("lastfm"), genreTags[1].sources)
        assertEquals("alternative", genreTags[2].name)
        assertEquals(0.3f, genreTags[2].confidence)
    }

    @Test
    fun `toGenre still populates genres for backward compatibility`() {
        // Given — two tags
        val tags = listOf("jazz", "soul")

        // When — mapping tags to genre metadata
        val metadata = LastFmMapper.toGenre(tags)

        // Then — genres retains the original tag list
        assertEquals(listOf("jazz", "soul"), metadata.genres)
    }

    @Test
    fun `toGenre returns null genreTags for empty tag list`() {
        // Given — an empty tag list
        val tags = emptyList<String>()

        // When — mapping tags to genre metadata
        val metadata = LastFmMapper.toGenre(tags)

        // Then — genreTags is null
        assertNull(metadata.genreTags)
    }

    @Test
    fun `toGenre returns empty genres for empty tag list`() {
        // Given — an empty tag list
        val tags = emptyList<String>()

        // When — mapping tags to genre metadata
        val metadata = LastFmMapper.toGenre(tags)

        // Then — genres is an empty (non-null) list
        // genres was previously `tags` directly, so empty list maps to empty genres
        // The existing contract: toGenre(emptyList()) returns Metadata(genres = emptyList())
        // but that's non-null empty list. Let's check what the original did:
        // fun toGenre(tags: List<String>): EnrichmentData.Metadata = EnrichmentData.Metadata(genres = tags)
        // So with emptyList(), genres = emptyList() (not null).
        assertEquals(emptyList<String>(), metadata.genres)
    }

    @Test
    fun `toGenre with single tag produces one genreTag`() {
        // Given — a single tag
        val tags = listOf("classical")

        // When — mapping tags to genre metadata
        val metadata = LastFmMapper.toGenre(tags)

        // Then — genreTags contains exactly one entry
        val genreTags = metadata.genreTags
        assertTrue(genreTags != null)
        assertEquals(1, genreTags!!.size)
        assertEquals("classical", genreTags[0].name)
        assertEquals(0.3f, genreTags[0].confidence)
        assertEquals(listOf("lastfm"), genreTags[0].sources)
    }
}
