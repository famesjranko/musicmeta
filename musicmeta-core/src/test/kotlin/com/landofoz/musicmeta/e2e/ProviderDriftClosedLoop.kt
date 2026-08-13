package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.engine.TitleMatcher

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

/** How the winning lookup for one closed-loop sample row reached its answer. */
internal enum class ClosedLoopRoute { EXACT_ID, NAME }

/**
 * One closed-loop sample row: a provider-emitted title fed back through a name-search enrichment
 * type, reduced to only the fields the trend needs. Deliberately carries no lyrics text, preview
 * URL, credential, or raw provider payload — [redactedLogLine] is safe to print in CI output
 * because this type cannot hold anything that needs redacting in the first place.
 */
internal data class ClosedLoopRow(
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
 * Reduces one sample title's fetch outcome to a [ClosedLoopRow]. [result]'s [EnrichmentResult.Success.data]
 * is read only to classify [outcome] — never copied into the row, so lyrics/preview-URL content
 * never enters the trend.
 */
internal fun toClosedLoopRow(
    sourceProvider: String,
    title: String,
    route: ClosedLoopRoute,
    canonicalStatus: CanonicalStatus,
    result: EnrichmentResult,
    latencyMs: Long,
    timedOut: Boolean,
): ClosedLoopRow = ClosedLoopRow(
    sourceProvider = sourceProvider,
    shape = classifyTitleShape(title),
    route = route,
    canonicalStatus = canonicalStatus,
    outcome = outcomeOf(result),
    latencyMs = latencyMs,
    timedOut = timedOut,
)

/** One safe-to-print line for [row] — every field is a classification, never sample content. */
internal fun redactedLogLine(row: ClosedLoopRow): String =
    "provider=${row.sourceProvider} shape=${row.shape} route=${row.route} " +
        "canonical=${row.canonicalStatus} outcome=${row.outcome} latencyMs=${row.latencyMs} timedOut=${row.timedOut}"

/**
 * Counts [rows] by (source provider, title shape, route, canonical status, outcome) — the trend
 * this ticket reports, dated by the caller. Never a success-rate gate: nothing here fails a build.
 */
internal fun aggregateClosedLoopTrend(rows: List<ClosedLoopRow>): Map<String, Int> =
    rows.groupingBy {
        "provider=${it.sourceProvider} shape=${it.shape} route=${it.route} canonical=${it.canonicalStatus} outcome=${it.outcome}"
    }.eachCount()
