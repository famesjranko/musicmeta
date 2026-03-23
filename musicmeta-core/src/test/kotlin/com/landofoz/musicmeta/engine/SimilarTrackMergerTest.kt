package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.SimilarTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarTrackMergerTest {

    @Test
    fun `merge returns NotFound for empty results`() {
        // Given
        val results = emptyList<EnrichmentResult.Success>()

        // When
        val result = SimilarTrackMerger.merge(results)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals("all_providers", (result as EnrichmentResult.NotFound).provider)
    }

    @Test
    fun `merge returns single provider results unchanged`() {
        // Given — lastfm returns 2 tracks
        val tracks = listOf(
            SimilarTrack("Lucky", "Radiohead", matchScore = 0.9f, sources = listOf("lastfm")),
            SimilarTrack("Karma Police", "Radiohead", matchScore = 0.7f, sources = listOf("lastfm")),
        )
        val results = listOf(
            EnrichmentResult.Success(
                type = EnrichmentType.SIMILAR_TRACKS,
                data = EnrichmentData.SimilarTracks(tracks = tracks),
                provider = "lastfm",
                confidence = 0.9f,
            )
        )

        // When
        val result = SimilarTrackMerger.merge(results)

        // Then — both tracks returned with original sources
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        assertEquals(2, data.tracks.size)
        assertEquals("similar_track_merger", result.provider)
        assertTrue(data.tracks.all { it.sources.contains("lastfm") })
    }

    @Test
    fun `merge deduplicates tracks by title and artist`() {
        // Given — lastfm has "Lucky" by Radiohead, deezer has "lucky" by "radiohead" (case difference)
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(tracks = listOf(
                SimilarTrack("Lucky", "Radiohead", matchScore = 0.9f, sources = listOf("lastfm")),
            )),
            provider = "lastfm",
            confidence = 0.9f,
        )
        val deezerResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(tracks = listOf(
                SimilarTrack("lucky", "radiohead", matchScore = 0.5f, sources = listOf("deezer")),
            )),
            provider = "deezer",
            confidence = 0.8f,
        )

        // When
        val result = SimilarTrackMerger.merge(listOf(lastfmResult, deezerResult))

        // Then — only 1 "Lucky" entry (merged from both)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        assertEquals(1, data.tracks.size)
        assertEquals("Lucky", data.tracks[0].title) // first-seen casing preserved
    }

    @Test
    fun `merge sums scores capped at 1_0`() {
        // Given — same track from both providers with high scores
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(tracks = listOf(
                SimilarTrack("Lucky", "Radiohead", matchScore = 0.9f, sources = listOf("lastfm")),
            )),
            provider = "lastfm",
            confidence = 0.9f,
        )
        val deezerResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(tracks = listOf(
                SimilarTrack("Lucky", "Radiohead", matchScore = 0.8f, sources = listOf("deezer")),
            )),
            provider = "deezer",
            confidence = 0.8f,
        )

        // When
        val result = SimilarTrackMerger.merge(listOf(lastfmResult, deezerResult))

        // Then — matchScore = min(0.9 + 0.8, 1.0) = 1.0, not 1.7
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        assertEquals(1.0f, data.tracks[0].matchScore, 0.001f)
    }

    @Test
    fun `merge combines sources from multiple providers`() {
        // Given — same track from lastfm and deezer
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(tracks = listOf(
                SimilarTrack("Lucky", "Radiohead", matchScore = 0.9f, sources = listOf("lastfm")),
            )),
            provider = "lastfm",
            confidence = 0.9f,
        )
        val deezerResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(tracks = listOf(
                SimilarTrack("Lucky", "Radiohead", matchScore = 0.5f, sources = listOf("deezer")),
            )),
            provider = "deezer",
            confidence = 0.8f,
        )

        // When
        val result = SimilarTrackMerger.merge(listOf(lastfmResult, deezerResult))

        // Then — both sources listed
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        val sources = data.tracks[0].sources
        assertTrue("lastfm" in sources)
        assertTrue("deezer" in sources)
    }

    @Test
    fun `merge prefers MBID from provider that has it`() {
        // Given — lastfm has MBID, deezer has deezerId
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(tracks = listOf(
                SimilarTrack(
                    title = "Lucky",
                    artist = "Radiohead",
                    matchScore = 0.9f,
                    identifiers = EnrichmentIdentifiers(musicBrainzId = "lucky-mbid"),
                    sources = listOf("lastfm"),
                ),
            )),
            provider = "lastfm",
            confidence = 0.9f,
        )
        val deezerResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(tracks = listOf(
                SimilarTrack(
                    title = "Lucky",
                    artist = "Radiohead",
                    matchScore = 0.5f,
                    identifiers = EnrichmentIdentifiers(extra = mapOf("deezerId" to "456")),
                    sources = listOf("deezer"),
                ),
            )),
            provider = "deezer",
            confidence = 0.8f,
        )

        // When
        val result = SimilarTrackMerger.merge(listOf(lastfmResult, deezerResult))

        // Then — merged result has both MBID and deezerId
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        val merged = data.tracks[0]
        assertEquals("lucky-mbid", merged.identifiers.musicBrainzId)
        assertEquals("456", merged.identifiers.extra["deezerId"])
    }

    @Test
    fun `merge sorts by matchScore descending`() {
        // Given — tracks from different providers with varying scores
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(tracks = listOf(
                SimilarTrack("Lucky", "Radiohead", matchScore = 0.6f, sources = listOf("lastfm")),
            )),
            provider = "lastfm",
            confidence = 0.9f,
        )
        val deezerResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(tracks = listOf(
                SimilarTrack("No Surprises", "Radiohead", matchScore = 0.9f, sources = listOf("deezer")),
                SimilarTrack("Fake Plastic Trees", "Radiohead", matchScore = 0.75f, sources = listOf("deezer")),
            )),
            provider = "deezer",
            confidence = 0.8f,
        )

        // When
        val result = SimilarTrackMerger.merge(listOf(lastfmResult, deezerResult))

        // Then — sorted by matchScore descending
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        val scores = data.tracks.map { it.matchScore }
        assertEquals(listOf(0.9f, 0.75f, 0.6f), scores)
    }

    @Test
    fun `merge handles tracks unique to each provider`() {
        // Given — "Lucky" only from lastfm, "No Surprises" only from deezer
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(tracks = listOf(
                SimilarTrack("Lucky", "Radiohead", matchScore = 0.9f, sources = listOf("lastfm")),
            )),
            provider = "lastfm",
            confidence = 0.9f,
        )
        val deezerResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(tracks = listOf(
                SimilarTrack("No Surprises", "Radiohead", matchScore = 0.8f, sources = listOf("deezer")),
            )),
            provider = "deezer",
            confidence = 0.8f,
        )

        // When
        val result = SimilarTrackMerger.merge(listOf(lastfmResult, deezerResult))

        // Then — both appear once with their original single-provider sources
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        assertEquals(2, data.tracks.size)
        val titles = data.tracks.map { it.title }
        assertTrue("Lucky" in titles)
        assertTrue("No Surprises" in titles)
    }

    @Test
    fun `merge distinguishes tracks with same title but different artists`() {
        // Given — "Lucky" by Radiohead and "Lucky" by Britney Spears are different tracks
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(tracks = listOf(
                SimilarTrack("Lucky", "Radiohead", matchScore = 0.9f, sources = listOf("lastfm")),
            )),
            provider = "lastfm",
            confidence = 0.9f,
        )
        val deezerResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(tracks = listOf(
                SimilarTrack("Lucky", "Britney Spears", matchScore = 0.5f, sources = listOf("deezer")),
            )),
            provider = "deezer",
            confidence = 0.8f,
        )

        // When
        val result = SimilarTrackMerger.merge(listOf(lastfmResult, deezerResult))

        // Then — 2 distinct entries (different artists means different tracks)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        assertEquals(2, data.tracks.size)
    }
}
