package com.landofoz.musicmeta.drift

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * What one pinned route said, in the only two kinds that matter.
 *
 * The split is the whole point of the pin. Before it, a 429, a read timeout and a renamed field
 * were one indistinguishable red, so the daily watch cried wolf often enough to be ignored and a
 * real field move could hide inside the noise.
 */
internal sealed class PinVerdict {

    /** A 200 whose body carries every pinned path. */
    object Ok : PinVerdict()

    /**
     * A 200 whose body is missing [missingPaths], or carries them blank. This is drift: the
     * provider answered, and the answer no longer holds a field a mapper reads.
     */
    data class Drift(val missingPaths: List<String>) : PinVerdict()

    /**
     * Everything else, with no exceptions — any non-200, any transport failure, any body that
     * will not parse. [kind] is a label for the report, never a branch: this arm is reached by
     * being the default, never by recognising a failure.
     *
     * Written this way because failures do not arrive in the shapes an allowlist expects. A DNS
     * failure measured on a developer machine arrived as HTTP 529, not as a resolver error,
     * because something between the machine and the wire answered it; a matcher keyed on
     * exception type would have fallen through to [Drift] and reported a healthy provider as
     * having moved a field.
     */
    data class Unavailable(val kind: String) : PinVerdict()
}

/** One pinned route and what it said. */
internal data class PinResult(val target: SchemaTarget, val verdict: PinVerdict)

/**
 * Requests [target]'s route and classifies the answer.
 *
 * Never throws: a thrown exception from the transport is an [PinVerdict.Unavailable], because the
 * alternative is a run that dies on the first shed request and reports nothing about the other ten.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun probe(httpClient: HttpClient, target: SchemaTarget): PinVerdict =
    try {
        // fetchJsonArrayResult carries no per-request headers, which costs nothing today: the one
        // provider that authenticates with a header answers with an object. A keyed array route
        // would need the overload before it could be pinned.
        val result: HttpResult<Any> = when (target.shape) {
            BodyShape.OBJECT -> httpClient.fetchJsonResult(target.url, target.headers)
            BodyShape.ARRAY -> httpClient.fetchJsonArrayResult(target.url)
        }
        when (result) {
            is HttpResult.Ok -> classifyBody(result.body, target.requiredPaths)
            is HttpResult.ClientError -> PinVerdict.Unavailable("http ${result.statusCode}")
            is HttpResult.ServerError -> PinVerdict.Unavailable("http ${result.statusCode}")
            is HttpResult.RateLimited -> PinVerdict.Unavailable("http 429")
            // Covers an unparseable body as well as a dropped connection: the http client reports
            // a 200 carrying HTML at a JSON route as a network error, and both belong here.
            is HttpResult.NetworkError -> PinVerdict.Unavailable("transport ${result.message}")
        }
    } catch (e: Exception) {
        PinVerdict.Unavailable("transport ${e::class.simpleName}")
    }

/** [PinVerdict.Ok] when every path in [requiredPaths] is present and non-blank in [body]. */
internal fun classifyBody(body: Any, requiredPaths: List<String>): PinVerdict {
    val missing = requiredPaths.filterNot { isPresent(body, it) }
    return if (missing.isEmpty()) PinVerdict.Ok else PinVerdict.Drift(missing)
}

/**
 * Whether [path] resolves to a present, non-blank value in [root].
 *
 * [path] is dot-separated, and a segment may carry `[n]` indices: `data[0].artist.name`,
 * `[0].trackName`, `entities.Q44190.claims.P434[0].mainsnak.datavalue.value`. A segment name is
 * matched literally, so a provider whose keys contain a `.` cannot be pinned by this grammar.
 *
 * Blank counts as absent. A mapper that defaults a missing field to `""` produces exactly the
 * empty answer the pin exists to catch, so the two must not be distinguished here.
 */
internal fun isPresent(root: Any, path: String): Boolean {
    var current: Any = root
    for (segment in path.split('.')) {
        val name = segment.substringBefore('[')
        if (name.isNotEmpty()) {
            val obj = current as? JSONObject ?: return false
            if (!obj.has(name)) return false
            current = obj.get(name)
        }
        for (index in indicesIn(segment)) {
            val array = current as? JSONArray ?: return false
            if (index >= array.length()) return false
            current = array.get(index)
        }
    }
    return !isBlank(current)
}

/** The `[n]` indices in one path segment, in order. */
private fun indicesIn(segment: String): List<Int> =
    INDEX_PATTERN.findAll(segment).map { it.groupValues[1].toInt() }.toList()

private val INDEX_PATTERN = Regex("""\[(\d+)]""")

/** `null`, `""`, `[]` and `{}` — the values a mapper cannot tell from a field that never arrived. */
private fun isBlank(value: Any): Boolean = when (value) {
    JSONObject.NULL -> true
    is String -> value.isBlank()
    is JSONArray -> value.length() == 0
    is JSONObject -> value.length() == 0
    else -> false
}

/**
 * One safe-to-print line per result: the route, its verdict, and — for drift — the paths that
 * moved. Never a value from the body, and never a URL's query string, because three providers put
 * a credential there and this job runs with all of them in scope.
 */
internal fun reportLines(results: List<PinResult>): List<String> = results.map { (target, verdict) ->
    val detail = when (verdict) {
        is PinVerdict.Ok -> "OK"
        is PinVerdict.Drift -> "DRIFT missing=${verdict.missingPaths.joinToString(",")}"
        is PinVerdict.Unavailable -> "UNAVAILABLE ${verdict.kind}"
    }
    "$target ${target.loggableUrl} $detail"
}

/** UNAVAILABLE counts by kind, for reading one run's report. Not a trend: one run is one sample. */
internal fun unavailableCounts(results: List<PinResult>): Map<String, Int> =
    results.map { it.verdict }
        .filterIsInstance<PinVerdict.Unavailable>()
        .groupingBy { it.kind }
        .eachCount()

/**
 * The reasons this run must fail, empty when it passes.
 *
 * Three, and only three:
 *
 *  - **Any drift.** A field a mapper reads has moved, which is the thing being watched for.
 *  - **Nothing scanned.** An empty target list is a check that cannot fail, and a check that
 *    cannot fail passing is worse than no check — it reads as a clean bill of health.
 *  - **Every route unavailable.** The watch is blind, which is our problem rather than a
 *    provider's, the same class as a missing credential.
 *
 * Some routes unavailable is *not* a failure. Measured over 90 live requests, 4.4% carried an
 * UNAVAILABLE, every one a read timeout; at that rate an eleven-request run carries at least one
 * 39% of the time, so failing on it would email about nothing roughly every third day — the alarm
 * this pin replaced, under a new name.
 */
internal fun runFindings(results: List<PinResult>): List<String> {
    if (results.isEmpty()) {
        return listOf(
            "::error::the schema pin scanned no routes, so it proved nothing. A target list that " +
                "resolves to nothing passes silently; fix the target registry rather than this check.",
        )
    }
    val drifted = results.filter { it.verdict is PinVerdict.Drift }.map { (target, verdict) ->
        val paths = (verdict as PinVerdict.Drift).missingPaths.joinToString(", ")
        "::error::${target.provider} moved a field on its ${target.route} route " +
            "(${target.loggableUrl}): $paths. The upstream answered 200 and the answer no longer " +
            "carries these paths, so re-read the response and update both the parse and the " +
            "target list beside it."
    }
    if (results.all { it.verdict is PinVerdict.Unavailable }) {
        val kinds = unavailableCounts(results).entries.sortedBy { it.key }
            .joinToString(", ") { "${it.key} x${it.value}" }
        return drifted + listOf(
            "::error::every one of the ${results.size} pinned routes was unavailable ($kinds), so " +
                "this run watched nothing. That is our problem, not a provider's — check network " +
                "egress and the credentials before reading it as an upstream outage.",
        )
    }
    return drifted
}
