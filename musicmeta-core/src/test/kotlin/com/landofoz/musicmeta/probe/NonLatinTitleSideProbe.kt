package com.landofoz.musicmeta.probe

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.engine.AlternativeName
import com.landofoz.musicmeta.engine.ProbeTrace
import com.landofoz.musicmeta.engine.ResolvedEntityNames
import com.landofoz.musicmeta.engine.nameMatchTier
import com.landofoz.musicmeta.http.DefaultHttpClient
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.deezer.DeezerProvider
import com.landofoz.musicmeta.provider.discogs.DiscogsProvider
import com.landofoz.musicmeta.provider.itunes.ITunesProvider
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzApi
import com.landofoz.musicmeta.provider.musicbrainz.alternativeNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Attributes each rejection on the frozen non-Latin workload to the artist side or the title side,
 * at the shipped alias-pool behaviour. Live upstreams, gated behind `-Dinclude.probe=true`.
 *
 * Run: `./gradlew :musicmeta-core:test -Dinclude.probe=true -Ddiscogs.token=… --tests "*NonLatinTitleSideProbe*"`
 */
class NonLatinTitleSideProbe {

    @Test
    fun `where the non-latin workload is rejected`() = runBlocking {
        // Given - the probe is enabled and the fixed workload is frozen in this file
        assumeTrue(System.getProperty("include.probe") == "true")
        val discogsToken = System.getProperty("discogs.token").orEmpty()
        check(discogsToken.isNotBlank()) { "discogs.token missing; the Discogs cells would run keyless" }

        val counter = CountingHttpClient(DefaultHttpClient(USER_AGENT))
        val mbApi = MusicBrainzApi(counter, RateLimiter(MB_RATE_LIMIT_MS))
        val deezer = DeezerProvider(counter, RateLimiter(DEEZER_RATE_LIMIT_MS))
        val itunes = ITunesProvider(counter, RateLimiter(ITUNES_RATE_LIMIT_MS))
        val discogs = DiscogsProvider(discogsToken, counter, RateLimiter(DISCOGS_RATE_LIMIT_MS))
        ProbeTrace.enabled = true

        val rows = mutableListOf(HEADER)

        // When - every workload item is put to every provider that serves its entity kind
        for (item in WORKLOAD) {
            val identity = retrying { resolveIdentity(mbApi, item) }
            for ((provider, type) in providersFor(item, deezer, itunes, discogs)) {
                val request = requestOf(item)
                ProbeTrace.reset()
                val before = counter.count(providerHost(provider.id))
                val startedAt = System.currentTimeMillis()
                val result = retrying { withPool(identity?.pool.orEmpty()) { provider.enrich(request, type) } }
                    ?: EnrichmentResult.Error(type, provider.id, "probe transport failure")
                val elapsed = System.currentTimeMillis() - startedAt
                val requests = counter.count(providerHost(provider.id)) - before
                rows += row(item, provider.id, type, result, elapsed, requests)
            }
        }

        // Then - the per-cell table is on disk and on stdout, for the report to read
        rows += "TOTALS\t${counter.total()}\t${counter.byHost()}"
        val out = File("build/probe-nonlatin-titleside.tsv")
        out.parentFile.mkdirs()
        out.writeText(rows.joinToString("\n") + "\n")
        rows.forEach { println("PROBE|$it") }
    }

    /**
     * Artist-side when nothing in any pool cleared the artist filter, title-side when something did
     * and nothing cleared the title filter, and `BOTH-OK` when both sides had a survivor and the
     * rejection is downstream of matching.
     */
    private fun classify(): String {
        val sifts = ProbeTrace.sifts
        if (sifts.isEmpty()) return "NO-POOL"
        val artistOk = sifts.any { it.artistOk > 0 }
        val titleSites = sifts.filter { it.titleOk >= 0 }
        val titleOk = titleSites.any { it.titleOk > 0 }
        return when {
            titleSites.isEmpty() -> if (artistOk) "BOTH-OK" else "ARTIST-SIDE"
            artistOk && !titleOk -> "TITLE-SIDE"
            !artistOk && titleOk -> "ARTIST-SIDE"
            !artistOk -> "ARTIST-SIDE"
            else -> "BOTH-OK"
        }
    }

    private fun row(
        item: Item,
        providerId: String,
        type: EnrichmentType,
        result: EnrichmentResult,
        elapsedMs: Long,
        requests: Int,
    ): String {
        val pick = ProbeTrace.picks.lastOrNull()
        val outcome = when {
            result is EnrichmentResult.Success && pick == null -> "MATCH-UNTRACED"
            result is EnrichmentResult.Success && isKnownName(pick!!.artistName, item) -> "MATCH"
            result is EnrichmentResult.Success -> "WRONG"
            result is EnrichmentResult.NotFound -> "REJECT"
            else -> "ERROR"
        }
        val side = if (outcome == "REJECT") classify() else ""
        val detail = when (result) {
            is EnrichmentResult.Success -> "conf=${result.confidence}"
            is EnrichmentResult.Error -> "${result.errorKind}:${result.message}"
            else -> result::class.simpleName.orEmpty()
        }
        return listOf(
            item.id,
            item.kind,
            providerId,
            type.name,
            outcome,
            side,
            pick?.artistName.orEmpty(),
            ProbeTrace.sifts.joinToString(";") { "${it.site}:${it.poolSize}/a${it.artistOk}/t${it.titleOk}[${it.names.joinToString("|")}]" },
            requests.toString(),
            elapsedMs.toString(),
            detail.take(DETAIL_CHARS).replace('\t', ' ').replace('\n', ' '),
        ).joinToString("\t")
    }

    /**
     * A picked name is the item's artist, or names them among the artists sharing one credit.
     *
     * Discogs' structured `artists[].join` for master 583829 is literally `","`, so a shared credit
     * arrives as `Χάρις Αλεξίου, Αντώνης Βαρδής` and folding it whole matches no truth name however
     * right the pick is. Only the *scoring* of a shared credit changes: an unrelated artist still
     * matches no part.
     */
    private fun isKnownName(candidate: String, item: Item): Boolean =
        candidate.split(",").any { part -> item.truthNames.any { fold(it) == fold(part) } }

    private fun providersFor(
        item: Item,
        deezer: DeezerProvider,
        itunes: ITunesProvider,
        discogs: DiscogsProvider,
    ): List<Pair<EnrichmentProvider, EnrichmentType>> = when (item.kind) {
        "artist" -> listOf(
            deezer to EnrichmentType.ARTIST_PHOTO,
            itunes to EnrichmentType.ARTIST_DISCOGRAPHY,
            discogs to EnrichmentType.ARTIST_PHOTO,
        )

        "album" -> listOf(
            deezer to EnrichmentType.ALBUM_ART,
            itunes to EnrichmentType.ALBUM_ART,
            discogs to EnrichmentType.ALBUM_ART,
        )

        else -> listOf(deezer to EnrichmentType.TRACK_METADATA)
    }

    private fun requestOf(item: Item): EnrichmentRequest = when (item.kind) {
        "artist" -> EnrichmentRequest.ForArtist(EnrichmentIdentifiers(), item.artist)
        "album" -> EnrichmentRequest.ForAlbum(EnrichmentIdentifiers(), item.title.orEmpty(), item.artist)
        else -> EnrichmentRequest.ForTrack(EnrichmentIdentifiers(), item.title.orEmpty(), item.artist)
    }

    /** MusicBrainz's own artist search, ranked the way the enricher ranks it, and its alias pool. */
    private suspend fun resolveIdentity(api: MusicBrainzApi, item: Item): Identity? {
        val candidates = api.searchArtists(item.artist, limit = MB_SEARCH_LIMIT)
        val best = candidates.maxWithOrNull(
            compareBy(
                { -nameMatchTier(item.artist, it.name, it.alternativeNames()).ordinal },
                { it.tags.isNotEmpty() },
            ),
        ) ?: return null
        return Identity(
            mbid = best.id,
            pool = listOf(AlternativeName(best.name, official = true)) + best.alternativeNames(),
        )
    }

    /** A live upstream drops a connection now and then; a cell lost to that is noise, not a result. */
    private suspend fun <T> retrying(attempts: Int = 3, body: suspend () -> T): T? {
        repeat(attempts) { attempt ->
            try {
                return body()
            } catch (e: java.io.IOException) {
                println("PROBE-RETRY|attempt=$attempt|${e.message}")
                Thread.sleep(RETRY_PAUSE_MS)
            }
        }
        return null
    }

    private fun fold(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private suspend fun <T> withPool(pool: List<AlternativeName>, body: suspend () -> T): T {
        val names = ResolvedEntityNames(CoroutineScope(currentCoroutineContext().minusKey(Job)))
        names.offerAliases { pool }
        return withContext(names) { body() }
    }

    private data class Identity(val mbid: String, val pool: List<AlternativeName>)

    /** Counts upstream requests per host, so each cell's cost is attributable. */
    private class CountingHttpClient(private val delegate: HttpClient) : HttpClient {
        private val counts = mutableMapOf<String, AtomicInteger>()

        fun count(host: String): Int = counts[host]?.get() ?: 0
        fun total(): Int = counts.values.sumOf { it.get() }
        fun byHost(): String = counts.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value.get()}" }

        private fun tick(url: String) {
            val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
            counts.getOrPut(host) { AtomicInteger() }.incrementAndGet()
        }

        override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> {
            tick(url)
            return delegate.fetchJsonResult(url)
        }

        override suspend fun fetchJsonResult(url: String, headers: Map<String, String>): HttpResult<JSONObject> {
            tick(url)
            return delegate.fetchJsonResult(url, headers)
        }

        override suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray> {
            tick(url)
            return delegate.fetchJsonArrayResult(url)
        }

        override suspend fun fetchRedirectUrlResult(url: String): HttpResult<String> {
            tick(url)
            return delegate.fetchRedirectUrlResult(url)
        }

        override suspend fun postJsonResult(url: String, body: String): HttpResult<JSONObject> {
            tick(url)
            return delegate.postJsonResult(url, body)
        }

        override suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray> {
            tick(url)
            return delegate.postJsonArrayResult(url, body)
        }
    }

    private fun providerHost(providerId: String): String = when (providerId) {
        "deezer" -> "api.deezer.com"
        "itunes" -> "itunes.apple.com"
        else -> "api.discogs.com"
    }

    /** One request of the frozen workload, with the artist name set a wrong match is judged against. */
    data class Item(
        val id: String,
        val kind: String,
        val artist: String,
        val title: String?,
        val mbid: String,
        val artistMbid: String,
        val truthNames: List<String>,
    )

    private companion object {
        const val USER_AGENT = "MusicMetaProbe/1.0 (musicmeta-probe@example.com)"
        const val RETRY_PAUSE_MS = 3000L
        const val MB_RATE_LIMIT_MS = 1100L
        const val DEEZER_RATE_LIMIT_MS = 200L
        const val ITUNES_RATE_LIMIT_MS = 3000L
        const val DISCOGS_RATE_LIMIT_MS = 1200L
        const val MB_SEARCH_LIMIT = 5
        const val DETAIL_CHARS = 200

        const val HEADER =
            "item\tkind\tprovider\ttype\toutcome\tside\tpickedArtist\tsifts\trequests\tms\tdetail"

        val TOKYO_JIHEN = listOf("東京事変", "Tokyo Incidents", "Tokyo Jihen", "东京事变", "東京事變")
        val IU = listOf("IU", "Lee Ji-eun", "아이유", "이지은")
        val KINO = listOf("Кино", "Gruppa Kino", "Kino", "Êèíî", "Абсолютное кино", "Кіно")
        val ALEXIOU = listOf(
            "Χάρις Αλεξίου", "Charis Alexiou", "Charoula Alexiou", "Haris Alexiou", "Kharis Alexiou",
            "Több elõadó", "Xaris Alexiou", "Xaroula Aleksiou", "×Üñéò Áëåîßïõ", "ΧΑΡΙΣ ΑΛΕΞΙΟΥ",
            "Χαρούλα Αλεξίου",
        )
        val FAIRUZ = listOf("فيروز", "Fairouz", "Fairuz", "Fayrouz", "Feyruz")

        val WORKLOAD = listOf(
            Item("A1", "artist", "東京事変", null, "b3d0f168-cb34-47c6-8529-fc05d1fce3ee", "b3d0f168-cb34-47c6-8529-fc05d1fce3ee", TOKYO_JIHEN),
            Item("A2", "artist", "아이유", null, "b9545342-1e6d-4dae-84ac-013374ad8d7c", "b9545342-1e6d-4dae-84ac-013374ad8d7c", IU),
            Item("A3", "artist", "Кино", null, "064db6e8-fdfb-4acb-a327-fc2de75b37de", "064db6e8-fdfb-4acb-a327-fc2de75b37de", KINO),
            Item("A4", "artist", "Χάρις Αλεξίου", null, "2dc67a2d-5914-4064-bf7b-8c8ea77bfab6", "2dc67a2d-5914-4064-bf7b-8c8ea77bfab6", ALEXIOU),
            Item("A5", "artist", "فيروز", null, "338bbb53-5b96-447a-9444-8906844a0790", "338bbb53-5b96-447a-9444-8906844a0790", FAIRUZ),
            Item("B1", "album", "東京事変", "教育", "3583d952-c5ec-3d69-b4e0-6f757d7480eb", "b3d0f168-cb34-47c6-8529-fc05d1fce3ee", TOKYO_JIHEN),
            Item("B2", "album", "아이유", "Palette", "8409ea9b-5f9d-4954-88b7-a9ae77a1814c", "b9545342-1e6d-4dae-84ac-013374ad8d7c", IU),
            Item("B3", "album", "Кино", "Группа крови", "7ff1eff0-a8c8-37dc-807e-eea5a9e173b5", "064db6e8-fdfb-4acb-a327-fc2de75b37de", KINO),
            Item("B4", "album", "Χάρις Αλεξίου", "Ξημερώνει", "40f9488d-b116-4e04-9984-2048a3cf52ba", "2dc67a2d-5914-4064-bf7b-8c8ea77bfab6", ALEXIOU),
            Item("B5", "album", "فيروز", "راجعون", "2e59333c-10c8-4796-9102-d5ca979157a2", "338bbb53-5b96-447a-9444-8906844a0790", FAIRUZ),
            Item("C1", "track", "東京事変", "群青日和", "f149fdcc-3b5d-41ee-9815-66a736728860", "b3d0f168-cb34-47c6-8529-fc05d1fce3ee", TOKYO_JIHEN),
            Item("C2", "track", "아이유", "좋은 날", "6ba67b5a-895a-4fbb-9efe-108f767fdf11", "b9545342-1e6d-4dae-84ac-013374ad8d7c", IU),
            Item("C3", "track", "Кино", "Группа крови", "18accada-fc63-40a6-9bea-40d599ffcc02", "064db6e8-fdfb-4acb-a327-fc2de75b37de", KINO),
            Item("C4", "track", "Χάρις Αλεξίου", "Μια πίστα από φώσφορο", "58a1504d-9ee8-41c5-9710-7f1868281c33", "2dc67a2d-5914-4064-bf7b-8c8ea77bfab6", ALEXIOU),
            Item("C5", "track", "فيروز", "كيفك إنت", "38cb6113-a25e-4bc7-8a74-2ee492f209aa", "338bbb53-5b96-447a-9444-8906844a0790", FAIRUZ),
            Item("L1", "album", "Radiohead", "OK Computer", "b1392450-e666-3926-a536-22c65f834433", "a74b1b7f-71a5-4011-9441-d0b5e4122711", listOf("Radiohead")),
        )
    }
}
