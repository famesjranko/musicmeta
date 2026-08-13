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
 * A ranking field's agreement with the request: [NOT_REQUESTED] when the request supplied no
 * value to compare, [UNKNOWN] when the candidate has none, [MATCH]/[MISMATCH] when both sides have
 * a value. Only an observed [MATCH] may rank a candidate above one the request has no opinion
 * about — a candidate silent on a field is never treated as though it agreed with the request.
 */
internal enum class LrcLibEvidence { NOT_REQUESTED, UNKNOWN, MATCH, MISMATCH }

/** [LrcLibEvidence]'s ranking weight — [LrcLibEvidence.MATCH] outranks everything else. */
private fun LrcLibEvidence.rank(): Int = when (this) {
    LrcLibEvidence.MATCH -> 2
    LrcLibEvidence.NOT_REQUESTED, LrcLibEvidence.UNKNOWN -> 1
    LrcLibEvidence.MISMATCH -> 0
}

/** The album and duration [LrcLibEvidence] that ranked a selected candidate — see [selectBest]. */
internal data class LrcLibSelectionEvidence(val album: LrcLibEvidence, val duration: LrcLibEvidence)

/**
 * Ranks an accepted `/api/search` pool for [request], preferring the requested album and a
 * duration close to the requested one; ties keep pool order ([kotlin.collections.maxWithOrNull]
 * keeps the first maximum, the convention [com.landofoz.musicmeta.engine.bestArtistMatch] uses).
 * Returns the winner paired with the [LrcLibSelectionEvidence] that ranked it, so a caller can
 * report why it won rather than only that a search (versus exact) lookup selected it.
 */
internal fun List<LrcLibResult>.selectBest(
    request: EnrichmentRequest.ForTrack,
): Pair<LrcLibResult, LrcLibSelectionEvidence>? {
    val expectedDurationSec = request.durationMs?.let { it / 1000.0 }
    return map { candidate ->
        candidate to LrcLibSelectionEvidence(
            candidate.albumEvidence(request.album),
            candidate.durationEvidence(expectedDurationSec),
        )
    }.maxWithOrNull(compareBy({ it.second.album.rank() }, { it.second.duration.rank() }))
}

private fun LrcLibResult.albumEvidence(requestedAlbum: String?): LrcLibEvidence = when {
    requestedAlbum.isNullOrBlank() -> LrcLibEvidence.NOT_REQUESTED
    albumName.isNullOrBlank() -> LrcLibEvidence.UNKNOWN
    albumName.contains(requestedAlbum, ignoreCase = true) || requestedAlbum.contains(albumName, ignoreCase = true) ->
        LrcLibEvidence.MATCH
    else -> LrcLibEvidence.MISMATCH
}

private fun LrcLibResult.durationEvidence(expectedSec: Double?): LrcLibEvidence = when {
    expectedSec == null -> LrcLibEvidence.NOT_REQUESTED
    duration == null -> LrcLibEvidence.UNKNOWN
    abs(duration - expectedSec) <= DURATION_TOLERANCE_SEC -> LrcLibEvidence.MATCH
    else -> LrcLibEvidence.MISMATCH
}

/** One accepted-and-selected [LrcLibResult], or a classified miss — never a raw search hit. */
internal sealed class LrcLibOutcome {
    /**
     * [evidence] is null for an exact `/api/get` hit, which has no ranked pool to have won from;
     * a search-selected (`exact = false`) result always carries the [LrcLibSelectionEvidence]
     * [selectBest] ranked it with.
     */
    data class Found(val result: LrcLibResult, val exact: Boolean, val evidence: LrcLibSelectionEvidence? = null) :
        LrcLibOutcome()
    data object Miss : LrcLibOutcome()
}
