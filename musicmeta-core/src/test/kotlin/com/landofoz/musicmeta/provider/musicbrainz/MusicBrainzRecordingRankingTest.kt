package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Offline, no-API-dependency tests for [MusicBrainzEnricher.pickBestRecording]'s artist tier — the
 * recording-side counterpart to [MusicBrainzReleaseRankingTest]'s artist-tier coverage of
 * [MusicBrainzReleaseRanking.pickBestRelease]. The appendix-fixture pools (tied and tiered
 * wrong-artist wins) and the collaboration/non-Latin cases live at the engine level in
 * `MusicBrainzWrongArtistTrackRankingTest`, where a real search response can be exercised; the
 * properties below need only [MusicBrainzRecording] values, so they are pinned directly here.
 *
 * The first two tests below are regression: the tier fires and deciding the winner, so removing
 * `{ it.second.artistQuality }` from [MusicBrainzEnricher.pickBestRecording]'s comparator turns
 * them red. The last two are characterisation, not regression, and cannot fail under that same
 * removal — see each one's own KDoc for why.
 */
class MusicBrainzRecordingRankingTest {
    private val enricher =
        MusicBrainzEnricher(
            MusicBrainzApi(FakeHttpClient(), RateLimiter(0)),
            providerId = "musicbrainz",
            minMatchScore = 80,
        )

    private fun recording(
        id: String,
        title: String = "Test Track",
        score: Int = 100,
        disambiguation: String? = null,
        artistCredits: List<String> = emptyList(),
    ): MusicBrainzRecording =
        MusicBrainzRecording(
            id = id,
            title = title,
            isrcs = emptyList(),
            tags = emptyList(),
            score = score,
            disambiguation = disambiguation,
            artistCredits = artistCredits,
        )

    private fun pick(
        recordings: List<MusicBrainzRecording>,
        artist: String? = null,
    ): MusicBrainzRecording? = enricher.pickBestRecording("Test Track", recordings, artist = artist)

    @Test
    fun `wrong-artist recording tied on every other tier does not beat the matching-artist recording`() {
        // Given - two recordings tied on every tier below artist (same title, no album hint, neither
        // a video, neither disambiguated, neither an official-album release), differing only in
        // artist-credit
        val wrongArtist = recording(id = "a", artistCredits = listOf("Radiohead Tribute Band"))
        val correctArtist = recording(id = "b", artistCredits = listOf("Radiohead"))

        // When - the pool is ranked for a request naming "Radiohead"
        val result = pick(listOf(wrongArtist, correctArtist), artist = "Radiohead")

        // Then - the matching-artist recording wins, not the tied wrong-artist one a position-only
        // tiebreak would have picked
        assertEquals(correctArtist, result)
    }

    @Test
    fun `wrong-artist recording with a clean tier does not beat a matching-artist recording with a worse tier`() {
        // Given - the wrong-artist recording keeps a blank disambiguation (the best tier); the
        // matching-artist recording carries one, so it legitimately loses that lower tier for a
        // reason unrelated to artist identity
        val wrongArtist = recording(id = "a", artistCredits = listOf("Radiohead Tribute Band"))
        val correctArtist = recording(id = "b", disambiguation = "live", artistCredits = listOf("Radiohead"))

        // When - the pool is ranked for a request naming "Radiohead"
        val result = pick(listOf(wrongArtist, correctArtist), artist = "Radiohead")

        // Then - the artist tier outranks the disambiguation tier, not list position
        assertEquals(correctArtist, result)
    }

    @Test
    fun `characterisation - a pool with no matching-artist candidate still resolves rather than returning null`() {
        // Given - neither recording is credited to, contains, or token-overlaps the requested artist,
        // so the tier ties both at QUALITY_NONE — deleting the tier entirely ties them the same way,
        // which is why this test cannot distinguish "tier present but tied" from "tier absent" and
        // stays green under that mutation; it exists to pin that a tied artist tier still falls
        // through to the pre-existing tiers instead of rejecting the pool
        val liveTake = recording(id = "a", disambiguation = "live", artistCredits = listOf("Coldplay Tribute Band"))
        val studioTake = recording(id = "b", artistCredits = listOf("Muse Tribute Band"))

        // When - the pool is ranked for a request naming "Radiohead"
        val result = pick(listOf(liveTake, studioTake), artist = "Radiohead")

        // Then - the pre-existing tiers decide (the blank disambiguation), rather than the pool
        // being rejected to null
        assertEquals(studioTake, result)
    }

    @Test
    fun `characterisation - a null artist leaves the tier inert, as the already artist-filtered fallback callers rely on`() {
        // Given - two recordings differing in both artist-credit and disambiguation. A null artist
        // makes the tier compute QUALITY_NONE for both, identical to the tier not existing at all —
        // which is why this test stays green if the tier is deleted; it exists to pin that the
        // qualifier-fallback caller, which passes no artist because it already filtered with
        // anyArtistMatches, does not get a second, redundant artist check here
        val wrongArtistCleanTier = recording(id = "a", artistCredits = listOf("Radiohead Tribute Band"))
        val correctArtistWorseTier = recording(id = "b", disambiguation = "live", artistCredits = listOf("Radiohead"))

        // When - the pool is ranked with no artist supplied
        val result = pick(listOf(wrongArtistCleanTier, correctArtistWorseTier))

        // Then - the pre-existing tiers decide on their own terms: the blank-disambiguation
        // recording wins regardless of which one is actually credited correctly
        assertEquals(wrongArtistCleanTier, result)
    }
}
