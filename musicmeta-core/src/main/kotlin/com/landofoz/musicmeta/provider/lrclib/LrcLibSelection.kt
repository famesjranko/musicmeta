package com.landofoz.musicmeta.provider.lrclib

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.TitleMatcher
import kotlin.math.abs

/**
 * Whether a `/api/search` hit is plausibly the requested recording, checked before it is ranked
 * or selected. LRCLIB owns this floor because its payload and ranking evidence (album, duration)
 * differ from every other provider's; only the title comparison itself is shared (`docs/pitfalls.md` §7).
 */
internal object LrcLibAcceptance {

    fun accepts(request: EnrichmentRequest.ForTrack, result: LrcLibResult): Boolean =
        TitleMatcher.equivalent(request.title, result.trackName) && artistAccepts(request.artist, result.artistName)

    /**
     * Stricter than [ArtistMatcher.isMatch]'s bottom tier: LRCLIB has no popularity signal to
     * outrank a loose token-overlap ghost with, so a candidate needs at least name containment.
     */
    private fun artistAccepts(expected: String, candidate: String): Boolean =
        ArtistMatcher.matchQuality(expected, candidate) >= ArtistMatcher.QUALITY_CONTAINS
}

/** The tolerance a candidate's `duration` may differ from the request's before it stops matching. */
private const val DURATION_TOLERANCE_SEC = 3.0

/**
 * Ranks an accepted `/api/search` pool for [request], preferring the requested album and a
 * duration close to the requested one; ties keep pool order ([kotlin.collections.maxWithOrNull]
 * keeps the first maximum, the convention [com.landofoz.musicmeta.engine.bestArtistMatch] uses).
 */
internal fun List<LrcLibResult>.selectBest(request: EnrichmentRequest.ForTrack): LrcLibResult? {
    val expectedDurationSec = request.durationMs?.let { it / 1000.0 }
    return maxWithOrNull(
        compareBy(
            { it.albumMatches(request.album) },
            { it.durationMatches(expectedDurationSec) },
        ),
    )
}

private fun LrcLibResult.albumMatches(requestedAlbum: String?): Boolean =
    requestedAlbum.isNullOrBlank() ||
        albumName?.contains(requestedAlbum, ignoreCase = true) == true ||
        requestedAlbum.contains(albumName.orEmpty(), ignoreCase = true)

private fun LrcLibResult.durationMatches(expectedSec: Double?): Boolean =
    expectedSec == null || duration == null || abs(duration - expectedSec) <= DURATION_TOLERANCE_SEC

/** One accepted-and-selected [LrcLibResult], or a classified miss — never a raw search hit. */
internal sealed class LrcLibOutcome {
    data class Found(val result: LrcLibResult, val exact: Boolean) : LrcLibOutcome()
    data object Miss : LrcLibOutcome()
}
