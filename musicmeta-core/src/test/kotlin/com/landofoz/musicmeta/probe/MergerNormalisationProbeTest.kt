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
import org.junit.Test
import java.io.File

/**
 * Throwaway measurement harness for `.scratch/tech-debt/prototypes/07-merger-normalisation/plan.md`.
 * NOT shipped: this file exists on probe branches only. It replays the frozen fixtures through the
 * real provider mappers, calls this branch's `SimilarArtistMerger`, and writes a raw score/metric
 * table to `probe-results/`.
 *
 * ARM_NAME / mergeArm() are the only two things that differ between branches.
 */
private const val ARM_NAME = "rank-cap"

/**
 * Adapter over this branch's `SimilarArtistMerger.mergeArtists`. Control and Arm A keep the
 * flattened-list signature; Arm B and Arm C move to a per-contributor signature because rank
 * normalisation needs the boundary `mergeArtists` otherwise can't see. Each branch edits this one
 * function to match its own merger's signature — nothing else in this file changes.
 */
private fun mergeArm(providerLists: List<List<SimilarArtist>>): List<SimilarArtist> =
    SimilarArtistMerger.mergeArtists(providerLists)

private const val PROVIDER_LASTFM = "lastfm"
private const val PROVIDER_DEEZER = "deezer"
private const val PROVIDER_LABS = "labs"

private val ARTISTS = listOf(
    "radiohead", "aphex-twin", "kendrick-lamar", "fleetwood-mac", "boards-of-canada", "4wheel",
    "changg", "sleep-token", "bjork", "fela-kuti", "tigran-hamasyan", "burial",
)

/** Name-variant pairs the merger's `name.trim().lowercase()` key does not unify. Metric 2 only. */
private val ADJUDICATION_PAIRS = listOf(
    "antibalas" to "antibalas afrobeat orchestra",
    "fela kuti & afrika 70" to "africa 70",
)

private fun key(name: String) = name.trim().lowercase()

private fun adjudicatedKey(name: String): String {
    val k = key(name)
    for ((a, b) in ADJUDICATION_PAIRS) if (k == a || k == b) return a
    return k
}

// ---- Parsers, mirroring each provider's real API parsing exactly (fixtures are raw HTTP bodies) ----

private fun parseLastFm(json: JSONObject): List<LastFmSimilarArtist> {
    val container = json.optJSONObject("similarartists") ?: return emptyList()
    val array = container.optJSONArray("artist") ?: return emptyList()
    return (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        LastFmSimilarArtist(
            name = obj.optString("name", ""),
            matchScore = obj.optString("match", "0").toFloatOrNull() ?: 0f,
            mbid = obj.optString("mbid").takeIf { it.isNotBlank() },
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

private fun loadFixture(slug: String, file: String): String =
    File("src/test/resources/probe/fixtures/$slug/$file").readText()

private fun loadArtist(slug: String): Map<String, List<SimilarArtist>> {
    val lastfmJson = JSONObject(loadFixture(slug, "lastfm.json"))
    val deezerJson = JSONObject(loadFixture(slug, "deezer.json"))
    val labsText = loadFixture(slug, "labs.json").trim()
    val labsJson: Any = if (labsText.startsWith("[")) JSONArray(labsText) else JSONArray()

    val lastfm = LastFmMapper.toSimilarArtists(parseLastFm(lastfmJson)).artists
    val deezer = DeezerMapper.toSimilarArtists(parseDeezer(deezerJson)).artists
    val labs = ListenBrainzMapper.toSimilarArtists(parseLabs(labsJson)).artists

    return mapOf(PROVIDER_LASTFM to lastfm, PROVIDER_DEEZER to deezer, PROVIDER_LABS to labs)
}

// ---- Metrics ----

private const val EPS = 1e-4f

private data class ArtistData(val slug: String, val byProvider: Map<String, List<SimilarArtist>>) {
    val contributing: Map<String, List<SimilarArtist>> = byProvider.filterValues { it.isNotEmpty() }
}

private fun mergedTop10(contributing: Map<String, List<SimilarArtist>>): List<SimilarArtist> =
    mergeArm(contributing.values.toList()).take(10)

private fun tieCount(top10: List<SimilarArtist>): Int {
    val counts = top10.groupingBy { Math.round(it.matchScore / EPS) }.eachCount()
    return top10.count { (counts[Math.round(it.matchScore / EPS)] ?: 0) > 1 }
}

private fun metric1(artists: List<ArtistData>): Map<String, Any> {
    var total = 0
    var worstArtist = ""
    var worst = 0
    val perArtist = mutableMapOf<String, Int>()
    for (a in artists) {
        val c = tieCount(mergedTop10(a.contributing))
        perArtist[a.slug] = c
        total += c
        if (c > worst) {
            worst = c
            worstArtist = a.slug
        }
    }
    return mapOf("total" to total, "worstArtist" to worstArtist, "worst" to worst, "perArtist" to perArtist)
}

/** Rank (1-indexed position in the merged top10, or null if absent/not top10) of the
 * highest-ranked entry present in every contributing provider's list, by [keyFn]. */
private fun corroborationRank(a: ArtistData, keyFn: (String) -> String): Int? {
    if (a.contributing.size < 2) return null
    val perProviderKeys = a.contributing.values.map { list -> list.map { keyFn(it.name) }.toSet() }
    val unanimous = perProviderKeys.reduce { acc, s -> acc.intersect(s) }
    if (unanimous.isEmpty()) return null
    val top10 = mergedTop10(a.contributing)
    val rank = top10.indexOfFirst { keyFn(it.name) in unanimous }
    return if (rank < 0) null else rank + 1
}

private fun metric2(artists: List<ArtistData>): Map<String, Map<String, Int?>> {
    val measured = artists.associate { it.slug to corroborationRank(it, ::key) }
    val adjudicated = artists.associate { it.slug to corroborationRank(it, ::adjudicatedKey) }
    return mapOf("measured" to measured, "adjudicated" to adjudicated)
}

private fun metric3(artists: List<ArtistData>): Map<String, Pair<Int, Int>> {
    val result = mutableMapOf<String, Pair<Int, Int>>()
    for (provider in listOf(PROVIDER_LASTFM, PROVIDER_DEEZER, PROVIDER_LABS)) {
        var retained = 0
        var possible = 0
        for (a in artists) {
            val own = a.contributing[provider] ?: continue
            val ownTop5 = own.take(5).map { key(it.name) }.toSet()
            possible += ownTop5.size
            val mergedKeys = mergedTop10(a.contributing).map { key(it.name) }.toSet()
            retained += ownTop5.count { it in mergedKeys }
        }
        result[provider] = retained to possible
    }
    return result
}

private fun metric4(artists: List<ArtistData>): Map<String, Double> {
    val avgOverlap = mutableMapOf<String, MutableList<Int>>()
    for (a in artists) {
        val full = mergedTop10(a.contributing).map { key(it.name) }.toSet()
        for (provider in a.contributing.keys) {
            val without = a.contributing.filterKeys { it != provider }
            val reduced = mergedTop10(without).map { key(it.name) }.toSet()
            val overlap = full.intersect(reduced).size
            avgOverlap.getOrPut(provider) { mutableListOf() }.add(overlap)
        }
    }
    return avgOverlap.mapValues { (_, v) -> v.average() }
}

private fun metric5(artists: List<ArtistData>): Pair<Boolean, List<String>> {
    val failures = mutableListOf<String>()
    for (a in artists) {
        for (provider in a.contributing.keys) {
            val own = a.contributing.getValue(provider)
            val ownDedupOrder = own.map { key(it.name) }.distinct()
            val solo = mergeArm(listOf(own)).map { key(it.name) }
            if (solo != ownDedupOrder) {
                failures += "${a.slug}/$provider: own=$ownDedupOrder merged=$solo"
            }
        }
    }
    return (failures.isEmpty()) to failures
}

class MergerNormalisationProbeTest {

    /**
     * Harness-can-fail evidence: a single provider with three known-score entries. For the
     * control/no-cap arms the merged score must equal the raw score (one contributor, no summing
     * needed); for the rank-normalisation arms the merged score is the rank formula this arm's
     * report states, independent of the raw value. This assertion was watched red before the arm's
     * `mergeArm` adapter or the known-good values below were correct — see the report for the
     * failing run this pinned.
     */
    @Test
    fun `known single-provider case matches hand-computed scores`() {
        val hand = listOf(
            SimilarArtist(name = "Alpha", matchScore = 0.9f, sources = listOf("x")),
            SimilarArtist(name = "Beta", matchScore = 0.6f, sources = listOf("x")),
            SimilarArtist(name = "Gamma", matchScore = 0.2f, sources = listOf("x")),
        )
        val merged = mergeArm(listOf(hand))
        val expectedNames = listOf("Alpha", "Beta", "Gamma")
        org.junit.Assert.assertEquals(expectedNames, merged.map { it.name })
        // Arm B (rank-cap): score is the 0-indexed rank formula 1 - i/n (n=3 here), independent of
        // the raw value — a provider's own top pick always scores exactly 1.0.
        org.junit.Assert.assertEquals(1.0f, merged[0].matchScore, EPS)
        org.junit.Assert.assertEquals(2f / 3f, merged[1].matchScore, EPS)
        org.junit.Assert.assertEquals(1f / 3f, merged[2].matchScore, EPS)
    }

    @Test
    fun `run probe and write results`() {
        val artists = ARTISTS.map { ArtistData(it, loadArtist(it)) }

        val m1 = metric1(artists)
        val m2 = metric2(artists)
        val m3 = metric3(artists)
        val m4 = metric4(artists)
        val (m5pass, m5fail) = metric5(artists)

        val sb = StringBuilder()
        sb.appendLine("# Arm: $ARM_NAME")
        sb.appendLine()
        sb.appendLine("## Metric 1 - tie count in top 10")
        sb.appendLine("total=${m1["total"]} worst=${m1["worst"]} (${m1["worstArtist"]})")
        @Suppress("UNCHECKED_CAST")
        for ((slug, c) in m1["perArtist"] as Map<String, Int>) sb.appendLine("- $slug: $c")
        sb.appendLine()
        sb.appendLine("## Metric 2 - corroboration rank (measured / adjudicated)")
        for (slug in ARTISTS) {
            val meas = (m2["measured"] as Map<String, Int?>)[slug]
            val adj = (m2["adjudicated"] as Map<String, Int?>)[slug]
            sb.appendLine("- $slug: measured=$meas adjudicated=$adj")
        }
        sb.appendLine()
        sb.appendLine("## Metric 3 - each provider's top5 retained in merged top10 (retained/possible)")
        for ((provider, pair) in m3) sb.appendLine("- $provider: ${pair.first}/${pair.second}")
        sb.appendLine()
        sb.appendLine("## Metric 4 - leave-one-out overlap with full top10 (avg overlap /10, by provider removed)")
        for ((provider, avg) in m4) sb.appendLine("- $provider removed: $avg")
        sb.appendLine()
        sb.appendLine("## Metric 5 - single-contributor identity")
        sb.appendLine("pass=$m5pass")
        for (f in m5fail) sb.appendLine("- FAIL $f")

        val outDir = File("../probe-results")
        outDir.mkdirs()
        File(outDir, "$ARM_NAME.md").writeText(sb.toString())
        println(sb.toString())
    }
}
