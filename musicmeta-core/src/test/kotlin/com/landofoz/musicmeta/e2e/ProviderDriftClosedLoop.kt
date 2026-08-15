package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.engine.TitleMatcher

/**
 * A provider-emitted title's decoration shape, for the closed-loop drift trend — never a claim
 * about whether the title is acceptable, only how it is punctuated. See `docs/pitfalls.md` §7 for
 * the acceptance/ranking vocabulary this is deliberately separate from.
 */
internal enum class TitleShape { PLAIN, BRACKETED, ALLOWLISTED_DASH_REISSUE, OTHER_DASH, OTHER }

private val TERMINAL_BRACKET = Regex("""^(.+?)\s*(?:\(([^()\[\]]+)\)|\[([^()\[\]]+)\])$""")

/** Terminal-qualifier separators a provider may punctuate a title with; see [classifyTitleShape]. */
private val QUALIFIER_SEPARATORS = listOf(" - ", " – ", " — ")

/** Classifies [title]'s terminal-qualifier syntax into a [TitleShape] for the drift trend. */
internal fun classifyTitleShape(title: String): TitleShape {
    val trimmed = title.trim()
    if (TERMINAL_BRACKET.matches(trimmed)) return TitleShape.BRACKETED
    val lastSeparator = QUALIFIER_SEPARATORS
        .mapNotNull { separator -> trimmed.lastIndexOf(separator).takeIf { it > 0 }?.let { separator to it } }
        .maxByOrNull { (_, index) -> index }
    if (lastSeparator != null) {
        val (separator, index) = lastSeparator
        val qualifier = trimmed.substring(index + separator.length).trim().lowercase()
        return if (TitleMatcher.isEditionDecoration(qualifier)) {
            TitleShape.ALLOWLISTED_DASH_REISSUE
        } else {
            TitleShape.OTHER_DASH
        }
    }
    return if (trimmed.isEmpty()) TitleShape.OTHER else TitleShape.PLAIN
}

/** Which configured provider-produced listing a [ClosedLoopRow] was sampled from. */
internal enum class RowSource { TOP_TRACKS, RADIO, SIMILAR_TRACKS, DISCOGRAPHY }

/** How the winning lookup for one closed-loop sample row reached its answer. */
internal enum class ClosedLoopRoute { EXACT_ID, NAME }

/**
 * One closed-loop sample row: a provider-emitted title fed back through a name-search enrichment
 * type, reduced to only the fields the trend needs. Deliberately carries no lyrics text, preview
 * URL, credential, or raw provider payload — [redactedLogLine] is safe to print in CI output
 * because this type cannot hold anything that needs redacting in the first place.
 *
 * [sourceProvider] names the row's own contributing providers (see [sourceProviderFor]), not the
 * provider that answered the feedback lookup.
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

/**
 * The provider attribution for one sampled row: [sources] when the listing merges more than one
 * provider's contributions for that row (e.g. `TopTrack.sources`, `SimilarTrack.sources`), or
 * [listingProvider] — the listing call's own [EnrichmentResult.Success.provider] — when the row
 * carries no per-row provenance of its own.
 */
internal fun sourceProviderFor(sources: List<String>, listingProvider: String): String =
    (sources.ifEmpty { listOf(listingProvider) }).distinct().sorted().joinToString(",")

/** A non-gating availability line: zero rows remains visible in the redacted live report. */
internal fun sourceAvailabilityLine(source: RowSource, sampledCount: Int): String =
    "availability source=$source sampled=$sampledCount"

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
 * One (row source, source provider, title shape, route, canonical status, outcome) combination —
 * a typed key, so two rows can only ever collide by having every one of those fields equal, never
 * by an incidental string-concatenation collision.
 */
private data class TrendKey(
    val source: RowSource,
    val sourceProvider: String,
    val shape: TitleShape,
    val route: ClosedLoopRoute,
    val canonicalStatus: CanonicalStatus,
    val outcome: String,
) {
    override fun toString(): String =
        "source=$source provider=$sourceProvider shape=$shape route=$route canonical=$canonicalStatus outcome=$outcome"
}

/**
 * Counts [rows] by (row source, source provider, title shape, route, canonical status, outcome) —
 * the dated trend. Never a success-rate gate: nothing here fails a build.
 */
internal fun aggregateClosedLoopTrend(rows: List<ClosedLoopRow>): Map<String, Int> =
    rows.groupingBy {
        TrendKey(it.source, it.sourceProvider, it.shape, it.route, it.canonicalStatus, it.outcome)
    }.eachCount().mapKeys { it.key.toString() }
