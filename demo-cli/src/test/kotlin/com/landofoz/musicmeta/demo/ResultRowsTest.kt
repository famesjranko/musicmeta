package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.GenreAffinity
import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.SimilarTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Results block: what each row says about a score, and which rows the user has pinned. */
class ResultRowsTest {

    private fun success(type: EnrichmentType, data: EnrichmentData) = EnrichmentResult.Success(
        type = type,
        data = data,
        provider = "lastfm",
        confidence = 0.9f,
    )

    private fun render(
        raw: Map<EnrichmentType, EnrichmentResult>,
        pinned: Set<EnrichmentType> = emptySet(),
    ): String = captureOutput { term -> Formatter.printResults(resultsOf(raw), term, pinned = pinned) }

    @Test
    fun `a similar-artist score is labelled as a rank, not left as a bare number`() {
        // Given - a similar-artists payload whose scores are summed, so no percentage can be honest
        val raw = mapOf(
            EnrichmentType.SIMILAR_ARTISTS to success(
                EnrichmentType.SIMILAR_ARTISTS,
                EnrichmentData.SimilarArtists(listOf(SimilarArtist(name = "Portishead", matchScore = 0.9f))),
            ),
        )

        // When - rendering the results block
        val output = render(raw)

        // Then - the number carries a unit rather than standing alone beside the name
        assertTrue(output, output.contains("Portishead (rank 0.90)"))
    }

    @Test
    fun `a similar-track score is labelled as a rank`() {
        // Given - a similar-tracks payload carrying the same kind of score
        val raw = mapOf(
            EnrichmentType.SIMILAR_TRACKS to success(
                EnrichmentType.SIMILAR_TRACKS,
                EnrichmentData.SimilarTracks(
                    listOf(SimilarTrack(title = "Glory Box", artist = "Portishead", matchScore = 0.8f)),
                ),
            ),
        )

        // When - rendering the results block
        val output = render(raw)

        // Then - the number carries the same unit as the artist rows
        assertTrue(output, output.contains("Glory Box (rank 0.80)"))
    }

    @Test
    fun `a genre affinity is labelled as an affinity`() {
        // Given - a genre-discovery payload whose number is an affinity, not a match
        val raw = mapOf(
            EnrichmentType.GENRE_DISCOVERY to success(
                EnrichmentType.GENRE_DISCOVERY,
                EnrichmentData.GenreDiscovery(
                    listOf(
                        GenreAffinity(
                            name = "trip hop",
                            affinity = 0.75f,
                            relationship = "adjacent",
                            sourceGenres = listOf("rock"),
                        ),
                    ),
                ),
            ),
        )

        // When - rendering the results block
        val output = render(raw)

        // Then - the row names the quantity it is showing
        assertTrue(output, output.contains("trip hop (affinity 0.75)"))
    }

    @Test
    fun `a pinned type is tagged in the results block`() {
        // Given - an artist bio the user has marked as manually selected
        val raw = mapOf(
            EnrichmentType.ARTIST_BIO to success(
                EnrichmentType.ARTIST_BIO,
                EnrichmentData.Biography(text = "An English rock band.", source = "lastfm"),
            ),
        )

        // When - rendering the results block with that type pinned
        val output = render(raw, pinned = setOf(EnrichmentType.ARTIST_BIO))

        // Then - the row says so, since a pinned type is protected from automatic overwrites
        assertTrue(output, output.contains("[pinned]"))
    }

    @Test
    fun `an unpinned type carries no tag`() {
        // Given - the same bio with nothing pinned
        val raw = mapOf(
            EnrichmentType.ARTIST_BIO to success(
                EnrichmentType.ARTIST_BIO,
                EnrichmentData.Biography(text = "An English rock band.", source = "lastfm"),
            ),
        )

        // When - rendering the results block
        val output = render(raw)

        // Then - no row claims a pin nobody made
        assertFalse(output, output.contains("[pinned]"))
    }
}
