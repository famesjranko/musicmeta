package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.ProviderInfo
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What one startup request to a provider's host told this instance. It describes where the demo
 * runs, not the library: the engine's own results are never read to fill it, and it changes none of
 * them. A page uses it only to explain a `not_found` that a refused host would explain.
 */
@Serializable
enum class ReachabilityVerdict {
    /** The probe has not returned, or this provider is not probed (a keyed one). */
    UNCHECKED,

    /** Any HTTP answer except 401 and 403, including 404, 429 and 5xx: throttling is not refusal. */
    REACHABLE,

    /** The host answered 401 or 403: the upstream turned this host away. */
    REFUSED,

    /** No HTTP answer: the connection failed or the 5 second timeout passed. */
    UNREACHABLE,
}

/** The `reachability` object on a `/api/providers` row. Every field but [verdict] is null until checked. */
@Serializable
data class ReachabilityRow(
    val verdict: ReachabilityVerdict,
    val httpStatus: Int? = null,
    val host: String? = null,
    /** UTC instant of the probe, ISO-8601 to the second. */
    val checkedAt: String? = null,
) {
    companion object {
        val UNCHECKED = ReachabilityRow(ReachabilityVerdict.UNCHECKED)
    }
}

/**
 * The route each keyless provider is probed on: one real, cheap GET taken from the URLs the library
 * already calls, so a verdict is about the same host and edge the enrichment uses. Keyed by catalog
 * id; a keyless provider with no entry here stays [ReachabilityVerdict.UNCHECKED].
 */
internal val KEYLESS_PROBE_URLS: Map<String, String> = mapOf(
    // The artist lookup path MusicBrainzApi uses; one MBID (Radiohead) is a single indexed read.
    "musicbrainz" to "https://musicbrainz.org/ws/2/artist/a74b1b7f-71a5-4011-9441-d0b5e4122711?fmt=json",
    // The release index CoverArtArchiveApi reads; it answers with a redirect, which counts as an answer.
    "coverartarchive" to "https://coverartarchive.org/release/76df3287-6cda-33eb-8e9a-044b5e15ffdd",
    // WikidataApi's wbgetentities call, for one small item.
    "wikidata" to "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=Q1&props=labels&format=json",
    // WikipediaApi's action API on the same host as its page lookups.
    "wikipedia" to "https://en.wikipedia.org/w/api.php?action=query&titles=Radiohead&format=json",
    // DeezerApi's artist lookup (BASE_URL/artist/{id}); the host whose edge answers 403 to Cloud Run.
    "deezer" to "https://api.deezer.com/artist/399",
    // SimilarAlbumsProvider calls the same host through its album route; probed on its own so its
    // row has a verdict of its own.
    "deezer-similar-albums" to "https://api.deezer.com/album/302127",
    // ITunesApi's lookup route.
    "itunes" to "https://itunes.apple.com/lookup?id=909253&entity=album&limit=1",
    // ListenBrainzApi's popularity route for one artist MBID.
    "listenbrainz" to
        "https://api.listenbrainz.org/1/popularity/top-release-groups-for-artist/a74b1b7f-71a5-4011-9441-d0b5e4122711",
    // LrcLibApi's get route.
    "lrclib" to "https://lrclib.net/api/get?artist_name=Radiohead&track_name=Creep",
)

/**
 * Probes each keyless provider's host once at startup and keeps the verdicts for the life of the
 * instance. [targets] maps provider id to the URL to GET; the default is none, which is what every
 * test and any caller that does not ask for a check gets.
 *
 * The probe is its own `java.net.http.HttpClient` on its own daemon threads. It does not use the
 * library's `HttpClient` seam, so it never takes a per-host rate limiter slot or touches a circuit
 * breaker, and nothing an enrichment returns can change a verdict.
 *
 * [probeAll] runs the probes before the server listens and waits at most [startupWaitMs] for them.
 * A host that gives CPU only while a request is in flight (Cloud Run) starves a thread started
 * after the port opens until the first request arrives, by which time its 5 s timeout has already
 * run out; before the port opens the process still has CPU. One line per probed provider goes to
 * [log] so a hosted instance can be read from its logs.
 */
class HostReachability(
    private val targets: Map<String, String> = emptyMap(),
    private val userAgent: String = EnrichmentConfig.DEFAULT_USER_AGENT,
    timeoutMs: Long = PROBE_TIMEOUT_MS,
    private val clock: Clock = Clock.systemUTC(),
    // Longer than the request timeout by a grace, so a probe that times out at the limit is
    // recorded as the UNREACHABLE it is rather than racing the cap.
    private val startupWaitMs: Long = timeoutMs + STARTUP_GRACE_MS,
    private val log: (String) -> Unit = ::println,
) {
    private val verdicts = ConcurrentHashMap<String, ReachabilityRow>()
    private val timeout = Duration.ofMillis(timeoutMs)

    // Set once the startup wait is over. A probe that returns after that is dropped, so a verdict
    // never changes after the instance has started serving.
    private var settled = false

    // Redirects are not followed: the first answer is the verdict, and a redirect target on a
    // second host would be a verdict about that host.
    private val client = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun verdictFor(providerId: String): ReachabilityRow = verdicts[providerId] ?: ReachabilityRow.UNCHECKED

    /**
     * Probes every registered provider that needs no key and has a target, all at once on daemon
     * threads, and returns when they have all answered or [startupWaitMs] has passed, whichever
     * comes first. A provider still out at the cap keeps [ReachabilityVerdict.UNCHECKED] for good.
     * A keyed provider is never probed, so a configured key is never sent anywhere but the
     * engine's own requests.
     */
    fun probeAll(providers: List<ProviderInfo>) {
        val probed = providers
            .filterNot { it.requiresApiKey }
            .mapNotNull { provider -> targets[provider.id]?.let { provider.id to it } }
        val done = CountDownLatch(probed.size)
        val details = ConcurrentHashMap<String, String>()
        probed.forEach { (id, url) ->
            Thread({
                try {
                    val outcome = probeDetailed(url)
                    details[id] = outcome.detail
                    synchronized(this) { if (!settled) verdicts[id] = outcome.row }
                } finally {
                    done.countDown()
                }
            }, "host-reachability-$id")
                .apply { isDaemon = true }
                .start()
        }
        done.await(startupWaitMs, TimeUnit.MILLISECONDS)
        synchronized(this) { settled = true }
        probed.forEach { (id, _) ->
            val row = verdictFor(id)
            val detail = if (row.verdict == ReachabilityVerdict.UNCHECKED) {
                "no answer within ${startupWaitMs}ms"
            } else {
                details[id]
            }
            log("host reachability: $id ${row.verdict} ($detail)")
        }
    }

    internal fun probe(url: String): ReachabilityRow = probeDetailed(url).row

    private class Probed(val row: ReachabilityRow, val detail: String)

    private fun probeDetailed(url: String): Probed {
        val uri = URI.create(url)
        val request = HttpRequest.newBuilder(uri)
            .GET()
            .header("User-Agent", userAgent)
            .timeout(timeout)
            .build()
        var failure: IOException? = null
        val status = try {
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        } catch (e: IOException) {
            failure = e
            null
        }
        val row = ReachabilityRow(
            verdict = classify(status),
            httpStatus = status,
            host = uri.host,
            checkedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString(),
        )
        val detail = if (failure == null) {
            "HTTP $status from ${uri.host}"
        } else {
            listOfNotNull(failure.javaClass.simpleName, failure.message).joinToString(": ")
        }
        return Probed(row, detail)
    }

    internal companion object {
        const val PROBE_TIMEOUT_MS = 5_000L
        const val STARTUP_GRACE_MS = 250L

        /** 401 and 403 are a refusal; every other answer, throttling and server errors included, is not. */
        fun classify(httpStatus: Int?): ReachabilityVerdict = when (httpStatus) {
            null -> ReachabilityVerdict.UNREACHABLE
            401, 403 -> ReachabilityVerdict.REFUSED
            else -> ReachabilityVerdict.REACHABLE
        }
    }
}
