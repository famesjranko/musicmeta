package com.landofoz.musicmeta.provider.lrclib

import com.landofoz.musicmeta.EnrichmentRequest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [selectBest]'s ranking must treat a candidate's missing album/duration evidence as strictly
 * weaker than a candidate that explicitly agrees with the request — never a tie a later,
 * evidence-bearing candidate cannot break by provider-returned order alone.
 */
class LrcLibSelectionEvidenceTest {

    private fun result(id: Long, album: String?, duration: Double?) = LrcLibResult(
        id = id,
        trackName = "Song",
        artistName = "Artist",
        albumName = album,
        duration = duration,
        instrumental = false,
        syncedLyrics = null,
        plainLyrics = "lyrics",
    )

    @Test fun `known matching album outranks missing album evidence`() {
        // Given - a requested album, one candidate silent on album and one that matches it
        val request = EnrichmentRequest.forTrack("Song", "Artist", album = "Wanted")
        val missing = result(id = 1, album = null, duration = null)
        val matching = result(id = 2, album = "Wanted", duration = 200.0)

        // When - ranking the pool
        val winner = listOf(missing, matching).selectBest(request)

        // Then - the explicit album match wins over the album-silent candidate
        assertEquals(2L, winner?.first?.id)
        assertEquals(LrcLibEvidence.MATCH, winner?.second?.album)
    }

    @Test fun `known matching duration outranks missing duration evidence`() {
        // Given - a requested duration, one candidate silent on duration and one within tolerance
        val request = EnrichmentRequest.forTrack("Song", "Artist", durationMs = 200_000)
        val missing = result(id = 1, album = null, duration = null)
        val matching = result(id = 2, album = null, duration = 200.0)

        // When - ranking the pool
        val winner = listOf(missing, matching).selectBest(request)

        // Then - the explicit duration match wins over the duration-silent candidate
        assertEquals(2L, winner?.first?.id)
        assertEquals(LrcLibEvidence.MATCH, winner?.second?.duration)
    }

    @Test fun `a mismatched candidate ranks below one silent on the same field`() {
        // Given - a requested album, one candidate silent on album and one that names a different album
        val request = EnrichmentRequest.forTrack("Song", "Artist", album = "Wanted")
        val silent = result(id = 1, album = null, duration = null)
        val wrongAlbum = result(id = 2, album = "Unrelated", duration = null)

        // When - ranking the pool
        val winner = listOf(wrongAlbum, silent).selectBest(request)

        // Then - the album-silent candidate outranks the one that actively disagrees
        assertEquals(1L, winner?.first?.id)
    }
}
