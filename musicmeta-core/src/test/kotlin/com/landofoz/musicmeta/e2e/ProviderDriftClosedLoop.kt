package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.engine.TitleMatcher
import com.landofoz.musicmeta.provider.lrclib.LrcLibEvidence
import com.landofoz.musicmeta.provider.lrclib.LrcLibSelectionEvidence

/**
 * A provider-emitted title's decoration shape, for the closed-loop drift trend — never a claim
 * about whether the title is acceptable, only how it is punctuated. See `docs/pitfalls.md` §7 for
 * the acceptance/ranking vocabulary this is deliberately separate from.
 */
internal enum class TitleShape { PLAIN, BRACKETED, ALLOWLISTED_DASH_REISSUE, OTHER_DASH, OTHER }

private val TERMINAL_BRACKET = Regex("""^(.+?)\s*(?:\(([^()\[\]]+)\)|\[([^()\[\]]+)\])$""")

/** Classifies [title]'s terminal-qualifier syntax into a [TitleShape] for the drift trend. */
internal fun classifyTitleShape(title: String): TitleShape {
    val trimmed = title.trim()
    if (TERMINAL_BRACKET.matches(trimmed)) return TitleShape.BRACKETED
    val dashIndex = trimmed.lastIndexOf(" - ")
    if (dashIndex > 0) {
        val qualifier = trimmed.substring(dashIndex + 3).trim().lowercase()
        return if (TitleMatcher.isEditionDecoration(qualifier)) {
            TitleShape.ALLOWLISTED_DASH_REISSUE
        } else {
            TitleShape.OTHER_DASH
        }
    }
    return if (trimmed.isEmpty()) TitleShape.OTHER else TitleShape.PLAIN
}

/**
 * Which of ticket 05's four provider-produced listings a [ClosedLoopRow] was sampled from — Top
 * Tracks alone under-samples the decoration shapes a catalogue actually emits.
 */
internal enum class RowSource { TOP_TRACKS, RADIO, SIMILAR_TRACKS, DISCOGRAPHY }

/** How the winning lookup for one closed-loop sample row reached its answer. */
internal enum class ClosedLoopRoute { EXACT_ID, NAME }

/**
 * One closed-loop sample row: a provider-emitted title fed back through a name-search enrichment
 * type, reduced to only the fields the trend needs. Deliberately carries no lyrics text, preview
 * URL, credential, or raw provider payload — [redactedLogLine] is safe to print in CI output
 * because this type cannot hold anything that needs redacting in the first place.
 *
 * [sourceProvider] is the provider that produced the sampled title (the listing call's own
 * [EnrichmentResult.Success.provider]), not the provider that answered the feedback lookup.
 */
internal data class ClosedLoopRow(
    val source: RowSource,
    val sourceProvider: String,
    val shape: TitleShape,
    val route: ClosedLoopRoute,
    val canonicalStatus: CanonicalStatus,
    val outcome: String,
    val latencyMs: Long,
    val timedOut: Boolean,
)

/** [outcome] classification for [toClosedLoopRow] — trend-only, never a pass/fail gate. */
private fun outcomeOf(result: EnrichmentResult): String = when (result) {
    is EnrichmentResult.Success -> "SUCCESS"
    is EnrichmentResult.NotFound -> "NOT_FOUND"
    is EnrichmentResult.RateLimited -> "RATE_LIMITED"
    is EnrichmentResult.Error -> "ERROR"
}

/**
 * True when [result] is the engine's own [ErrorKind.TIMEOUT] classification — never inferred from
 * elapsed latency, which conflates a slow-but-real answer with a deadline miss.
 */
private fun timedOutOf(result: EnrichmentResult): Boolean =
    result is EnrichmentResult.Error && result.errorKind == ErrorKind.TIMEOUT

/**
 * Reduces one sample title's fetch outcome to a [ClosedLoopRow]. [result]'s [EnrichmentResult.Success.data]
 * is read only to classify [outcome] — never copied into the row, so lyrics/preview-URL content
 * never enters the trend.
 */
internal fun toClosedLoopRow(
    source: RowSource,
    sourceProvider: String,
    title: String,
    route: ClosedLoopRoute,
    canonicalStatus: CanonicalStatus,
    result: EnrichmentResult,
    latencyMs: Long,
): ClosedLoopRow = ClosedLoopRow(
    source = source,
    sourceProvider = sourceProvider,
    shape = classifyTitleShape(title),
    route = route,
    canonicalStatus = canonicalStatus,
    outcome = outcomeOf(result),
    latencyMs = latencyMs,
    timedOut = timedOutOf(result),
)

/** One safe-to-print line for [row] — every field is a classification, never sample content. */
internal fun redactedLogLine(row: ClosedLoopRow): String =
    "source=${row.source} provider=${row.sourceProvider} shape=${row.shape} route=${row.route} " +
        "canonical=${row.canonicalStatus} outcome=${row.outcome} latencyMs=${row.latencyMs} timedOut=${row.timedOut}"

/**
 * Counts [rows] by (row source, source provider, title shape, route, canonical status, outcome) —
 * the trend this ticket reports, dated by the caller. Never a success-rate gate: nothing here
 * fails a build.
 */
internal fun aggregateClosedLoopTrend(rows: List<ClosedLoopRow>): Map<String, Int> =
    rows.groupingBy {
        "source=${it.source} provider=${it.sourceProvider} shape=${it.shape} route=${it.route} " +
            "canonical=${it.canonicalStatus} outcome=${it.outcome}"
    }.eachCount()

/**
 * A title-tier acceptance's outcome, coarsened from [TitleMatcher.TitleTier] for the drift trend:
 * [EXACT] and [SYNTAX_EQUIVALENT] are that tier's own two accepted values today,
 * [OTHER_ACCEPTED] is reserved for any accepted tier added later, and [REJECTED] is a candidate a
 * selector's title floor dropped before ranking.
 */
internal enum class AcceptanceTier { EXACT, SYNTAX_EQUIVALENT, OTHER_ACCEPTED, REJECTED }

private fun TitleMatcher.TitleTier.toAcceptanceTier(): AcceptanceTier = when (this) {
    TitleMatcher.TitleTier.EXACT -> AcceptanceTier.EXACT
    TitleMatcher.TitleTier.EDITION -> AcceptanceTier.SYNTAX_EQUIVALENT
    TitleMatcher.TitleTier.NONE -> AcceptanceTier.REJECTED
}

/**
 * The selected candidate's artist/title and acceptance tier for a track or album selector's
 * closed-loop diagnostic — safe to print because it carries nothing but the two strings a request
 * already named and a classification, never a raw candidate payload.
 */
internal data class AcceptanceDiagnostic(val selectedArtist: String, val selectedTitle: String, val tier: AcceptanceTier)

/** Reduces one selector's title-tier decision for [selectedArtist]/[selectedTitle] to an [AcceptanceDiagnostic]. */
internal fun toAcceptanceDiagnostic(
    selectedArtist: String,
    selectedTitle: String,
    tier: TitleMatcher.TitleTier,
): AcceptanceDiagnostic = AcceptanceDiagnostic(selectedArtist, selectedTitle, tier.toAcceptanceTier())

/** One safe-to-print line for [diagnostic]. */
internal fun redactedDiagnosticLine(diagnostic: AcceptanceDiagnostic): String =
    "artist=${diagnostic.selectedArtist} title=${diagnostic.selectedTitle} tier=${diagnostic.tier}"

/**
 * LRCLIB's own ranking evidence (album/duration agreement, never tiered like [TitleMatcher.TitleTier])
 * for the closed-loop diagnostic — see [LrcLibSelectionEvidence].
 */
internal data class LrcLibAcceptanceDiagnostic(
    val selectedArtist: String,
    val selectedTitle: String,
    val albumEvidence: LrcLibEvidence,
    val durationEvidence: LrcLibEvidence,
)

/** Reduces LRCLIB's [evidence] for its selected [selectedArtist]/[selectedTitle] to a [LrcLibAcceptanceDiagnostic]. */
internal fun toLrcLibAcceptanceDiagnostic(
    selectedArtist: String,
    selectedTitle: String,
    evidence: LrcLibSelectionEvidence,
): LrcLibAcceptanceDiagnostic =
    LrcLibAcceptanceDiagnostic(selectedArtist, selectedTitle, evidence.album, evidence.duration)

/** One safe-to-print line for [diagnostic]. */
internal fun redactedDiagnosticLine(diagnostic: LrcLibAcceptanceDiagnostic): String =
    "artist=${diagnostic.selectedArtist} title=${diagnostic.selectedTitle} " +
        "album=${diagnostic.albumEvidence} duration=${diagnostic.durationEvidence}"
