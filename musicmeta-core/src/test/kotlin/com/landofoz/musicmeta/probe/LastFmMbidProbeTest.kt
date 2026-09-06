package com.landofoz.musicmeta.probe

import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.engine.SimilarArtistMerger
import com.landofoz.musicmeta.provider.deezer.DeezerMapper
import com.landofoz.musicmeta.provider.deezer.DeezerRelatedArtist
import com.landofoz.musicmeta.provider.lastfm.LastFmMapper
import com.landofoz.musicmeta.provider.lastfm.LastFmSimilarArtist
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzMapper
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzSimilarArtist
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder

/**
 * Throwaway measurement harness for `bugs/34`, the Last.fm similar-artist MBID A/B. NOT shipped:
 * this file exists on `probe/lastfm-mbid-arm-*` branches only. It replays two frozen workloads
 * through the real provider mappers and this branch's `SimilarArtistMerger`, lets the arm rewrite a
 * Last.fm row's MBID before the merge sees it, and writes raw tables to `probe-results/34-mbid/`.
 *
 * `ARM_NAME` and `applyArm` are the only two things that differ between branches.
 */
private const val ARM_NAME = "control"

/**
 * The arm's whole property: what a Last.fm row's MBID becomes, and what asking cost.
 *
 * The control asks nothing and changes nothing.
 */
private fun applyArm(
    rows: List<LastFmRow>,
    others: List<SimilarArtist>,
    mb: MbTable,
    ledger: Ledger,
): List<LastFmRow> = rows

// ---- Shared harness below this line; identical on every branch ------------------------------

private const val PROVIDER_LASTFM = "lastfm"
private const val PROVIDER_DEEZER = "deezer"
private const val PROVIDER_LABS = "labs"

private val FROZEN = listOf(
    "radiohead", "aphex-twin", "kendrick-lamar", "fleetwood-mac", "boards-of-canada", "4wheel",
    "changg", "sleep-token", "bjork", "fela-kuti", "tigran-hamasyan", "burial",
)

private val HELDOUT = listOf(
    "blackpink", "claude-debussy", "a-tribe-called-quest", "the-bad-plus", "trouble", "kino",
    "alison-krauss", "four-tet", "shakira", "the-zombies", "alvvays", "nico",
)

private fun key(name: String) = name.trim().lowercase()

/**
 * A Last.fm similar row as the arm sees it: the fields the parser builds, plus the `url` the
 * shipped parser drops and the row's index among the ones carrying an MBID, which is how ground
 * truth is keyed.
 */
internal data class LastFmRow(
    val artist: LastFmSimilarArtist,
    /** `url` reduced to the artist page it names: host dropped, unescaped, lowercased. */
    val page: String?,
    /** Position among this workload's MBID-carrying rows, or -1. */
    val mbidIndex: Int,
)

/** One arm's request ledger. Every consultation of [MbTable] bills the request it stands in for. */
internal class Ledger {
    val lines = mutableListOf<String>()
    fun bill(route: String, detail: String) {
        lines += "$route $detail"
    }
}

/** The offline MusicBrainz capture the arms consult in place of a live call. */
internal class MbTable(private val root: JSONObject) {
    val capturedOn: String = root.optString("capturedOn")

    fun lastfmPages(mbid: String): List<String> {
        val a = root.getJSONObject("artists").optJSONObject(mbid.lowercase()) ?: return emptyList()
        val arr = a.optJSONArray("lastfmPages") ?: return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun vocabulary(mbid: String): Set<String> {
        val a = root.getJSONObject("artists").optJSONObject(mbid.lowercase()) ?: return emptySet()
        val out = mutableSetOf<String>()
        for (field in listOf("tags", "genres")) {
            val arr = a.optJSONArray(field) ?: continue
            for (i in 0 until arr.length()) out += arr.getString(i)
        }
        return out
    }

    fun isKnown(mbid: String): Boolean = root.getJSONObject("artists").has(mbid.lowercase())

    /** Every same-name MusicBrainz artist, for the names a search was captured for. */
    fun sameNameCandidates(name: String): List<String>? {
        val arr = root.getJSONObject("nameCandidates").optJSONArray(key(name)) ?: return null
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun verdict(workload: String, index: Int): String =
        root.getJSONObject("groundTruth").optJSONObject("$workload|$index")?.optString("verdict")
            ?: "ABSENT"

    fun owner(workload: String, index: Int): String =
        root.getJSONObject("groundTruth").optJSONObject("$workload|$index")?.optString("owner")
            ?: ""
}

// ---- Parsers, mirroring each provider's real API parsing (fixtures are raw HTTP bodies) --------

private fun normalizePage(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val marker = "/music/"
    val i = url.indexOf(marker)
    if (i < 0 || !url.contains("last.fm")) return null
    val tail = url.substring(i + marker.length).substringBefore('/').substringBefore('?')
    return runCatching { URLDecoder.decode(tail, "UTF-8") }.getOrNull()?.trim()?.lowercase()
}

private fun parseLastFm(json: JSONObject): List<LastFmRow> {
    val container = json.optJSONObject("similarartists") ?: return emptyList()
    val array = container.optJSONArray("artist") ?: return emptyList()
    var mbidIndex = 0
    return (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        val mbid = obj.optString("mbid").takeIf { it.isNotBlank() }
        LastFmRow(
            artist = LastFmSimilarArtist(
                name = obj.optString("name", ""),
                matchScore = obj.optString("match", "0").toFloatOrNull() ?: 0f,
                mbid = mbid,
            ),
            page = normalizePage(obj.optString("url").takeIf { it.isNotBlank() }),
            mbidIndex = if (mbid == null) -1 else mbidIndex++,
        )
    }
}

private fun parseDeezer(json: JSONObject): List<DeezerRelatedArtist> {
    val data = json.optJSONArray("data") ?: return emptyList()
    return (0 until data.length()).map { i ->
        val artist = data.getJSONObject(i)
        DeezerRelatedArtist(id = artist.optLong("id"), name = artist.optString("name", ""))
    }
}

private fun parseLabs(json: Any): List<ListenBrainzSimilarArtist> {
    val array = json as? JSONArray ?: return emptyList()
    val results = mutableListOf<ListenBrainzSimilarArtist>()
    for (i in 0 until array.length()) {
        val item = array.getJSONObject(i)
        val mbid = item.optString("artist_mbid").takeIf { it.isNotBlank() } ?: continue
        val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
        results += ListenBrainzSimilarArtist(artistMbid = mbid, name = name, score = item.optInt("score", 0))
    }
    return results
}

private fun fixture(set: String, slug: String, file: String): String =
    File("src/test/resources/probe34/$set/$slug/$file").readText()

private fun table(set: String): MbTable =
    MbTable(JSONObject(File("src/test/resources/probe34/musicbrainz/$set.json").readText()))

private class Workload(val set: String, val slug: String) {
    val lastFm: List<LastFmRow> = parseLastFm(JSONObject(fixture(set, slug, "lastfm.json")))
    val deezer: List<SimilarArtist> =
        DeezerMapper.toSimilarArtists(parseDeezer(JSONObject(fixture(set, slug, "deezer.json")))).artists
    val labs: List<SimilarArtist> = run {
        val text = fixture(set, slug, "labs.json").trim()
        val json: Any = if (text.startsWith("[")) JSONArray(text) else JSONArray()
        ListenBrainzMapper.toSimilarArtists(parseLabs(json)).artists
    }

    /** Engine registration order: Deezer, then ListenBrainz, then Last.fm. */
    fun flattened(rows: List<LastFmRow>): List<SimilarArtist> =
        deezer + labs + LastFmMapper.toSimilarArtists(rows.map { it.artist }).artists
}

private fun topTen(artists: List<SimilarArtist>): List<String> =
    SimilarArtistMerger.mergeArtists(artists).take(10).map { it.name }

// ---- The run ----------------------------------------------------------------------------------

class LastFmMbidProbeTest {

    @Test
    fun `the workload holds exactly the rows the inventory adjudicated`() {
        // Given - inventory.md counted 198 MBID-carrying Last.fm rows over the frozen twelve
        val rows = FROZEN.sumOf { slug -> Workload("frozen", slug).lastFm.count { it.mbidIndex >= 0 } }
        // When - the harness re-derives that count from the same fixtures
        // Then - it is the number every ground-truth key was written against
        assertEquals(198, rows)
    }

    @Test
    fun `every ground-truth mismatch names an owner the capture holds`() {
        // Given - the frozen table, whose ground truth is inventory.md's adjudication
        val mb = table("frozen")
        // When - each mismatched row's named owner is looked for in the same capture
        val missing = FROZEN.flatMap { slug ->
            Workload("frozen", slug).lastFm.filter { it.mbidIndex >= 0 }
                .filter { mb.verdict(slug, it.mbidIndex) == "MISMATCH" }
                .filterNot { mb.isKnown(mb.owner(slug, it.mbidIndex)) }
        }
        // Then - the arms can always reach the id they would rewrite to
        assertTrue("owners absent from the capture: $missing", missing.isEmpty())
    }

    @Test
    fun `run probe and write results`() {
        val out = File("probe-results/34-mbid").also { it.mkdirs() }
        val summary = JSONObject()
        for ((set, slugs) in listOf("frozen" to FROZEN, "heldout" to HELDOUT)) {
            val tableFile = File("src/test/resources/probe34/musicbrainz/$set.json")
            if (!tableFile.exists()) continue
            summary.put(set, runSet(set, slugs, table(set), out))
        }
        File(out, "$ARM_NAME.json").writeText(summary.toString(1))
        println("arm=$ARM_NAME wrote probe-results/34-mbid/$ARM_NAME.json")
    }

    private fun runSet(set: String, slugs: List<String>, mb: MbTable, out: File): JSONObject {
        var corrected = 0
        var wronglyChanged = 0
        var changed = 0
        var flagged = 0
        var flaggedMismatch = 0
        var flaggedMatch = 0
        var mismatchTotal = 0
        var matchTotal = 0
        var movement = 0
        var worstRequests = 0
        var totalRequests = 0
        val perArtist = JSONObject()
        val detail = JSONArray()

        for (slug in slugs) {
            val w = Workload(set, slug)
            val ledger = Ledger()
            val others = w.deezer + w.labs
            val after = applyArm(w.lastFm, others, mb, ledger)
            val before = w.lastFm

            val controlTop = topTen(w.flattened(before))
            val armTop = topTen(w.flattened(after))
            val moved = controlTop.indices.count { controlTop.getOrNull(it) != armTop.getOrNull(it) }
            movement += moved
            totalRequests += ledger.lines.size
            worstRequests = maxOf(worstRequests, ledger.lines.size)

            for ((i, row) in before.withIndex()) {
                if (row.mbidIndex < 0) continue
                val verdict = mb.verdict(slug, row.mbidIndex)
                if (verdict == "MISMATCH") mismatchTotal++
                if (verdict == "MATCH") matchTotal++
                val was = row.artist.mbid?.lowercase()
                val now = after[i].artist.mbid?.lowercase()
                val flag = ARM_FLAGS[Triple(set, slug, row.mbidIndex)] == true
                if (flag) {
                    flagged++
                    if (verdict == "MISMATCH") flaggedMismatch++
                    if (verdict == "MATCH") flaggedMatch++
                }
                if (was == now) continue
                changed++
                val ok = verdict == "MISMATCH" && now == mb.owner(slug, row.mbidIndex).lowercase()
                if (ok) corrected++ else wronglyChanged++
                detail.put(
                    JSONObject()
                        .put("set", set).put("workload", slug).put("row", row.artist.name)
                        .put("verdict", verdict).put("from", was).put("to", now)
                        .put("correct", ok),
                )
            }
            perArtist.put(
                slug,
                JSONObject().put("requests", ledger.lines.size).put("top10Moved", moved)
                    .put("ledger", JSONArray(ledger.lines)),
            )
        }
        return JSONObject()
            .put("arm", ARM_NAME)
            .put("capturedOn", mb.capturedOn)
            .put("mismatchRows", mismatchTotal)
            .put("matchRows", matchTotal)
            .put("rowsChanged", changed)
            .put("metric1_corrected", corrected)
            .put("metric2_wronglyChanged", wronglyChanged)
            .put("metric3_totalRequests", totalRequests)
            .put("metric3_worstArtist", worstRequests)
            .put("metric4_top10Moved", movement)
            .put("metric5_flagged", flagged)
            .put("metric5_flaggedMismatch", flaggedMismatch)
            .put("metric6_flaggedMatch", flaggedMatch)
            .put("perArtist", perArtist)
            .put("changes", detail)
    }
}

/**
 * Rows this arm flagged as suspect without changing them. Only the genre arm writes here; every
 * other arm leaves it empty, so metrics 5 and 6 read zero for them.
 */
internal val ARM_FLAGS = mutableMapOf<Triple<String, String, Int>, Boolean>()
