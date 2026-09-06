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
        // Given - no provider results at all
        val results = emptyList<EnrichmentResult.Success>()

        // When - merging the empty list
        val result = SimilarTrackMerger.merge(results)

        // Then - NotFound is returned with provider "all_providers"
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals("all_providers", (result as EnrichmentResult.NotFound).provider)
    }

    @Test
    fun `merge returns single provider results unchanged`() {
        // Given - lastfm returns 2 tracks
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

        // When - merging the single-provider result list
        val result = SimilarTrackMerger.merge(results)

        // Then - both tracks returned with original sources
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        assertEquals(2, data.tracks.size)
        assertEquals("similar_track_merger", result.provider)
        assertTrue(data.tracks.all { it.sources.contains("lastfm") })
    }

    @Test
    fun `merge deduplicates tracks by title and artist`() {
        // Given - lastfm has "Lucky" by Radiohead, deezer has "lucky" by "radiohead" (case difference)
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

        // When - merging the lastfm and deezer results
        val result = SimilarTrackMerger.merge(listOf(lastfmResult, deezerResult))

        // Then - only 1 "Lucky" entry (merged from both)
        assertTrue(result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        assertEquals(1, data.tracks.size)
        assertEquals("Lucky", data.tracks[0].title) // first-seen casing preserved
    }

    @Test
    fun `merge keeps Last_fm's score outright on overlap with Deezer, does not sum`() {
        // Given - same track from both providers, under a Last.fm entry already at the top of the scale
        val lastfmResult = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(tracks = listOf(
                SimilarTrack("Nude", "Radiohead", matchScore = 1.0f, sources = listOf("lastfm")),
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

        // When - merging the lastfm and deezer results
        val result = SimilarTrackMerger.merge(listOf(lastfmResult, deezerResult))

        // Then - matchScore is Last.fm's 0.9 outright, not 0.9 + 0.8 (would overstate the match)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        val lucky = data.tracks.first { it.title == "Lucky" }
        assertEquals(0.9f, lucky.matchScore, 0.001f)
        assertTrue("lastfm" in lucky.sources)
        assertTrue("deezer" in lucky.sources)
    }

    @Test
    fun `merge combines sources from multiple providers`() {
        // Given - same track from lastfm and deezer
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

        // When - merging the lastfm and deezer results
        val result = SimilarTrackMerger.merge(listOf(lastfmResult, deezerResult))

        // Then - both sources listed
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        val sources = data.tracks[0].sources
        assertTrue("lastfm" in sources)
        assertTrue("deezer" in sources)
    }

    @Test
    fun `merge prefers MBID from provider that has it`() {
        // Given - lastfm has MBID, deezer has deezerId
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

        // When - merging the lastfm and deezer results
        val result = SimilarTrackMerger.merge(listOf(lastfmResult, deezerResult))

        // Then - merged result has both MBID and deezerId
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        val merged = data.tracks[0]
        assertEquals("lucky-mbid", merged.identifiers.musicBrainzId)
        assertEquals("456", merged.identifiers.extra["deezerId"])
    }

    @Test
    fun `merge sorts by matchScore descending`() {
        // Given - tracks from different providers with varying scores
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

        // When - merging the lastfm and deezer results
        val result = SimilarTrackMerger.merge(listOf(lastfmResult, deezerResult))

        // Then - sorted by matchScore descending, each score a fraction of the 0.9 the list tops out at
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        assertEquals(listOf("No Surprises", "Fake Plastic Trees", "Lucky"), data.tracks.map { it.title })
        assertEquals(listOf(1.0f, 0.75f / 0.9f, 0.6f / 0.9f), data.tracks.map { it.matchScore })
    }

    @Test
    fun `merge handles tracks unique to each provider`() {
        // Given - "Lucky" only from lastfm, "No Surprises" only from deezer
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

        // When - merging the lastfm and deezer results
        val result = SimilarTrackMerger.merge(listOf(lastfmResult, deezerResult))

        // Then - both appear once with their original single-provider sources
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        assertEquals(2, data.tracks.size)
        val titles = data.tracks.map { it.title }
        assertTrue("Lucky" in titles)
        assertTrue("No Surprises" in titles)
    }

    @Test
    fun `merge distinguishes tracks with same title but different artists`() {
        // Given - "Lucky" by Radiohead and "Lucky" by Britney Spears are different tracks
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

        // When - merging the lastfm and deezer results
        val result = SimilarTrackMerger.merge(listOf(lastfmResult, deezerResult))

        // Then - 2 distinct entries (different artists means different tracks)
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        assertEquals(2, data.tracks.size)
    }

    @Test
    fun `merge rescales summed scores against the merged maximum`() {
        // Given - two artist-derived sources agreeing on "Lucky" and a third track only one names
        val deezer = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(
                tracks = listOf(
                    SimilarTrack("Lucky", "Radiohead", 0.9f, sources = listOf("deezer")),
                    SimilarTrack("Nude", "Radiohead", 0.5f, sources = listOf("deezer")),
                ),
            ),
            provider = "deezer",
            confidence = 0.8f,
        )
        val other = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(
                tracks = listOf(SimilarTrack("Lucky", "Radiohead", 0.8f, sources = listOf("other"))),
            ),
            provider = "other",
            confidence = 0.8f,
        )

        // When - merging the two artist-derived results
        val result = SimilarTrackMerger.merge(listOf(deezer, other))

        // Then - the 1.7 sum becomes the list's 1.0 and the 0.5 keeps its distance below it
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        assertEquals(1.0f, data.tracks[0].matchScore, 0.001f)
        assertEquals(0.5f / 1.7f, data.tracks[1].matchScore, 0.001f)
    }

    @Test
    fun `merge lifts a list whose every score is below 1_0 to the top of the scale`() {
        // Given - one source whose best track scores 0.4
        val deezer = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(
                tracks = listOf(
                    SimilarTrack("Lucky", "Radiohead", 0.4f, sources = listOf("deezer")),
                    SimilarTrack("Nude", "Radiohead", 0.2f, sources = listOf("deezer")),
                ),
            ),
            provider = "deezer",
            confidence = 0.8f,
        )

        // When - merging that single result
        val result = SimilarTrackMerger.merge(listOf(deezer))

        // Then - the top entry is 1.0 and the spacing below it survives
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        assertEquals(1.0f, data.tracks[0].matchScore, 0.001f)
        assertEquals(0.5f, data.tracks[1].matchScore, 0.001f)
    }

    @Test
    fun `merge leaves a list whose maximum is not positive untouched`() {
        // Given - a source whose every track scores zero
        val deezer = EnrichmentResult.Success(
            type = EnrichmentType.SIMILAR_TRACKS,
            data = EnrichmentData.SimilarTracks(
                tracks = listOf(
                    SimilarTrack("Lucky", "Radiohead", 0f, sources = listOf("deezer")),
                    SimilarTrack("Nude", "Radiohead", 0f, sources = listOf("deezer")),
                ),
            ),
            provider = "deezer",
            confidence = 0.8f,
        )

        // When - merging that single result
        val result = SimilarTrackMerger.merge(listOf(deezer))

        // Then - every score is still zero rather than NaN
        val data = (result as EnrichmentResult.Success).data as EnrichmentData.SimilarTracks
        assertEquals(listOf(0f, 0f), data.tracks.map { it.matchScore })
    }
}
