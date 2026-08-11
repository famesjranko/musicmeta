package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.GenreTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreMergerTest {

    @Test
    fun `merge returns empty list for empty input`() {
        // Given - an empty list of genre tags
        val tags = emptyList<GenreTag>()

        // When - merging the tags
        val result = GenreMerger.merge(tags)

        // Then - the result is empty
        assertTrue(result.isEmpty())
    }

    @Test
    fun `merge normalizes tag names to lowercase`() {
        // Given - a single tag with an uppercase name
        val tags = listOf(GenreTag(name = "ROCK", confidence = 0.8f))

        // When - merging the tags then normalizing the name directly
        val result = GenreMerger.merge(tags)

        // Then - the display name is preserved and normalize lowercases it
        assertEquals("ROCK", result.single().name) // display name preserved from first seen
        assertEquals("rock", GenreMerger.normalize("ROCK")) // normalize to lowercase
    }

    @Test
    fun `merge maps common aliases`() {
        // Given - "alt rock" should map to "alternative rock", "hip hop" to "hip-hop"
        val tags = listOf(
            GenreTag(name = "alt rock", confidence = 0.7f),
            GenreTag(name = "hip hop", confidence = 0.6f),
        )

        // When - merging the tags
        val result = GenreMerger.merge(tags)

        // Then - the aliased genre names appear in the result
        val names = result.map { it.name }
        assertTrue("alternative rock" in names)
        assertTrue("hip-hop" in names)
    }

    @Test
    fun `merge deduplicates tags by normalized name`() {
        // Given - "Rock" and "rock" should be merged into one tag
        val tags = listOf(
            GenreTag(name = "Rock", confidence = 0.4f, sources = listOf("mb")),
            GenreTag(name = "rock", confidence = 0.3f, sources = listOf("lastfm")),
        )

        // When - merging the tags
        val result = GenreMerger.merge(tags)

        // Then - the duplicate tags collapse into one with combined confidence and sources
        assertEquals(1, result.size)
        assertEquals("Rock", result.single().name) // first-seen casing preserved
        assertEquals(0.7f, result.single().confidence, 0.001f)
        assertTrue("mb" in result.single().sources)
        assertTrue("lastfm" in result.single().sources)
    }

    @Test
    fun `merge caps confidence at 1_0`() {
        // Given - two tags both at 0.6 for the same genre should cap at 1.0
        val tags = listOf(
            GenreTag(name = "rock", confidence = 0.6f, sources = listOf("mb")),
            GenreTag(name = "rock", confidence = 0.6f, sources = listOf("lastfm")),
        )

        // When - merging the tags
        val result = GenreMerger.merge(tags)

        // Then - the combined confidence is capped at 1.0 instead of summing to 1.2
        assertEquals(1, result.size)
        assertEquals(1.0f, result.single().confidence, 0.001f)
    }

    @Test
    fun `merge sorts by confidence descending`() {
        // Given - tags with varying confidences
        val tags = listOf(
            GenreTag(name = "Pop", confidence = 0.3f),
            GenreTag(name = "Rock", confidence = 0.8f),
            GenreTag(name = "Jazz", confidence = 0.5f),
        )

        // When - merging the tags
        val result = GenreMerger.merge(tags)

        // Then - the result is ordered from highest to lowest confidence
        assertEquals(listOf(0.8f, 0.5f, 0.3f), result.map { it.confidence })
    }

    @Test
    fun `merge preserves first-seen display name`() {
        // Given - "Rock" appears first, "rock" second
        val tags = listOf(
            GenreTag(name = "Rock", confidence = 0.5f, sources = listOf("mb")),
            GenreTag(name = "rock", confidence = 0.4f, sources = listOf("lastfm")),
        )

        // When - merging the tags
        val result = GenreMerger.merge(tags)

        // Then - display name from first-seen ("Rock") is kept
        assertEquals("Rock", result.single().name)
    }

    @Test
    fun `merge handles single provider input`() {
        // Given - two distinct tags both sourced from a single provider
        val tags = listOf(
            GenreTag(name = "Electronic", confidence = 0.9f, sources = listOf("lastfm")),
            GenreTag(name = "Ambient", confidence = 0.6f, sources = listOf("lastfm")),
        )

        // When - merging the tags
        val result = GenreMerger.merge(tags)

        // Then - tags are returned sorted by confidence; display name is first-seen casing
        assertEquals(2, result.size)
        assertEquals("Electronic", result[0].name) // first-seen casing preserved
        assertEquals("Ambient", result[1].name) // first-seen casing preserved
        assertEquals(0.9f, result[0].confidence, 0.001f)
    }

    @Test
    fun `a curated genre outranks a community tag several providers agreed on`() {
        // Given - one curated genre against a tag whose summed confidence is higher
        val tags = listOf(
            GenreTag("british", 0.4f, listOf("lastfm"), curated = false),
            GenreTag("british", 0.4f, listOf("discogs"), curated = false),
            GenreTag("alternative rock", 0.7f, listOf("musicbrainz"), curated = true),
        )

        // When - merging the tags
        val result = GenreMerger.merge(tags)

        // Then - the curated name leads, because summed agreement is not curation
        assertEquals("alternative rock", result[0].name)
        assertEquals(true, result[0].curated)
    }

    @Test
    fun `a name one provider curated stays curated once merged`() {
        // Given - the same name from a curated source and an uncurated one
        val tags = listOf(
            GenreTag("rock", 0.4f, listOf("lastfm"), curated = false),
            GenreTag("rock", 0.7f, listOf("musicbrainz"), curated = true),
        )

        // When - merging the tags
        val result = GenreMerger.merge(tags)

        // Then - the merged tag keeps the stronger claim
        assertEquals(true, result.single().curated)
    }

    @Test
    fun `electronica is no longer folded into electronic`() {
        // Given - two names MusicBrainz's genre vocabulary holds as distinct genres
        val tags = listOf(
            GenreTag("electronica", 0.7f, listOf("musicbrainz"), curated = true),
            GenreTag("electronic", 0.7f, listOf("musicbrainz"), curated = true),
        )

        // When - merging the tags
        val result = GenreMerger.merge(tags)

        // Then - both survive, rather than the alias map collapsing one curated genre onto another
        assertEquals(listOf("electronica", "electronic"), result.map { it.name })
    }
}
