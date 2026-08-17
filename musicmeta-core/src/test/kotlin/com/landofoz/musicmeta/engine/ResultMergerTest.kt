package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.GenreTag
import com.landofoz.musicmeta.LookupProvenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultMergerTest {

    @Test
    fun `GenreMerger type is GENRE`() {
        // Given - the GenreMerger object
        // When - its type is read
        // Then - it is GENRE
        assertEquals(EnrichmentType.GENRE, GenreMerger.type)
    }

    @Test
    fun `GenreMerger merge with empty list returns NotFound`() {
        // Given - an empty list of successful results
        val results = emptyList<EnrichmentResult.Success>()

        // When - merging the empty list
        val result = GenreMerger.merge(results)

        // Then - the merge yields NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `GenreMerger merge with success containing genreTags returns merged Success`() {
        // Given - a single success carrying two genre tags
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

        // When - merging the single-element list
        val result = GenreMerger.merge(listOf(success))

        // Then - the result is a Success attributed to genre_merger with the tags carried through
        assertTrue(result is EnrichmentResult.Success)
        val merged = result as EnrichmentResult.Success
        assertEquals("genre_merger", merged.provider)
        val mergedTags = (merged.data as? EnrichmentData.Metadata)?.genreTags
        assertNotNull(mergedTags)
        assertTrue(mergedTags!!.isNotEmpty())
    }

    @Test
    fun `GenreMerger merge with success without genreTags attributes to genre_merger like every other path`() {
        // Given - a single success with legacy genres but no genreTags
        val success = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genres = listOf("Rock")),
            provider = "test_provider",
            confidence = 0.7f,
        )

        // When - merging the single-element list
        val result = GenreMerger.merge(listOf(success))

        // Then - the populated path now reads genres as well as genreTags, so a legacy-only
        // And - contributor is merged rather than returned verbatim; every contributor reaching the
        // And - populated path is attributed to the merger, so a contributor's own id here would be
        // And - the exception, not the rule.
        assertTrue(result is EnrichmentResult.Success)
        assertEquals("genre_merger", (result as EnrichmentResult.Success).provider)
        assertEquals(listOf("Rock"), (result.data as EnrichmentData.Metadata).genres)
    }

    @Test
    fun `GenreMerger merge with two legacy-only contributors unions both names instead of dropping the second`() {
        // Given - two contributors that only ever filled the legacy genres field, no genreTags on either
        val c1 = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genres = listOf("rock")),
            provider = "musicbrainz",
            confidence = 0.9f,
            provenance = LookupProvenance.CANONICAL_ID,
        )
        val c2 = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genres = listOf("indie")),
            provider = "lastfm",
            confidence = 0.9f,
            provenance = LookupProvenance.FUZZY_NAME,
        )

        // When - merging both legacy-only contributors
        val result = GenreMerger.merge(listOf(c1, c2)) as EnrichmentResult.Success
        val data = result.data as EnrichmentData.Metadata

        // Then - both names survive, genreTags stays null since neither contributor supplied one
        assertEquals("genre_merger", result.provider)
        assertEquals(listOf("rock", "indie"), data.genres)
        assertEquals(null, data.genreTags)
        assertEquals(LookupProvenance.FUZZY_NAME, result.provenance)
    }

    @Test
    fun `GenreMerger merge with one genreTags contributor and one legacy-only contributor keeps both names`() {
        // Given - one contributor with genreTags and one legacy-only contributor
        val t1 = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genreTags = listOf(GenreTag("rock", 0.8f))),
            provider = "musicbrainz",
            confidence = 0.9f,
            provenance = LookupProvenance.CANONICAL_ID,
        )
        val t2 = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genres = listOf("indie")),
            provider = "lastfm",
            confidence = 0.9f,
            provenance = LookupProvenance.FUZZY_NAME,
        )

        // When - merging the mixed pair
        val result = GenreMerger.merge(listOf(t1, t2)) as EnrichmentResult.Success
        val data = result.data as EnrichmentData.Metadata

        // Then - the legacy-only contributor's name is not invisible to the populated path
        assertEquals("genre_merger", result.provider)
        assertEquals(listOf("rock", "indie"), data.genres)
        assertNotNull(data.genreTags)
        assertEquals(1, data.genreTags!!.size)
        assertEquals("rock", data.genreTags!!.first().name)
        assertEquals(LookupProvenance.FUZZY_NAME, result.provenance)
    }

    @Test
    fun `GenreMerger resolvedIdentifiers propagated from first result with identifiers`() {
        // Given - a single success carrying resolvedIdentifiers with a musicBrainzId
        val ids = EnrichmentIdentifiers(musicBrainzId = "test-mbid")
        val success = EnrichmentResult.Success(
            type = EnrichmentType.GENRE,
            data = EnrichmentData.Metadata(genreTags = listOf(GenreTag("Rock", 0.8f))),
            provider = "test",
            confidence = 0.9f,
            resolvedIdentifiers = ids,
        )

        // When - merging the single-element list
        val result = GenreMerger.merge(listOf(success)) as EnrichmentResult.Success

        // Then - the merged result propagates the source musicBrainzId
        assertEquals("test-mbid", result.resolvedIdentifiers?.musicBrainzId)
    }
}
