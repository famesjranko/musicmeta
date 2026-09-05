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
import org.junit.Test
import java.io.File

/**
 * Falsification probe for a claimed tie-break confound in metric 3 of
 * `.scratch/tech-debt/prototypes/07-merger-normalisation/report.md`. CONTROL arm only — this file
 * does not touch Arm A/B/C's committed merger code or results. See
 * `.scratch/tech-debt/prototypes/07-merger-normalisation/addendum-tie-break.md` for the write-up.
 *
 * Everything below is a standalone re-derivation of Control's own grouping/sum/clamp/sort so the
 * pre-sort order can be varied deliberately (production's `mergeArtists` doesn't expose a hook for
 * that). `known tie-break case matches hand-computed order` checks this re-derivation against a
 * hand-worked example, and the main probe cross-checks it against the *real*
 * `SimilarArtistMerger.mergeArtists` output before trusting it for anything else.
 */

private const val EPS = 1e-4f
private const val PROVIDER_LASTFM = "lastfm"
private const val PROVIDER_DEEZER = "deezer"
private const val PROVIDER_LABS = "labs"

private val ARTISTS = listOf(
    "radiohead", "aphex-twin", "kendrick-lamar", "fleetwood-mac", "boards-of-canada", "4wheel",
    "changg", "sleep-token", "bjork", "fela-kuti", "tigran-hamasyan", "burial",
)

private fun key(name: String) = name.trim().lowercase()

// ---- Parsers, identical to MergerNormalisationProbeTest.kt (duplicated so this branch's probe
// stands alone and does not touch the already-committed control-arm test file). ----

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

/** The same flatten order the original probe/report used: Last.fm, Deezer, then Labs. */
private fun flatten(byProvider: Map<String, List<SimilarArtist>>): List<SimilarArtist> =
    (byProvider[PROVIDER_LASTFM].orEmpty()) +
        (byProvider[PROVIDER_DEEZER].orEmpty()) +
        (byProvider[PROVIDER_LABS].orEmpty())

/**
 * Re-derives Control's grouping/sum/clamp exactly (see `SimilarArtistMerger.mergeArtists`), but
 * stops short of the final sort, returning the grouped list in first-occurrence order instead —
 * this is the "pre-sort order" the claim is about, and the only thing the three tie-break variants
 * below are allowed to touch.
 */
private fun groupSumClamp(flattened: List<SimilarArtist>): List<SimilarArtist> {
    val grouped = LinkedHashMap<String, MutableList<SimilarArtist>>()
    for (artist in flattened) grouped.getOrPut(key(artist.name)) { mutableListOf() }.add(artist)
    return grouped.values.map { group ->
        val first = group.first()
        val total = group.map { it.matchScore }.fold(0f) { acc, s -> acc + s }.coerceAtMost(1.0f)
        SimilarArtist(name = first.name, matchScore = total, sources = group.flatMap { it.sources }.distinct())
    }
}

/** Kotlin's `sortedByDescending` is a stable sort: ties keep [preSortOrder]'s relative order. */
private fun stableSortDescending(preSortOrder: List<SimilarArtist>): List<SimilarArtist> =
    preSortOrder.sortedByDescending { it.matchScore }

private fun top5Retained(top10: List<SimilarArtist>, ownTop5: Set<String>): Int {
    val mergedKeys = top10.map { key(it.name) }.toSet()
    return ownTop5.count { it in mergedKeys }
}

private data class RetentionRow(val provider: String, var retained: Int, var possible: Int)

private fun metric3ForOrder(
    artists: List<Pair<String, Map<String, List<SimilarArtist>>>>,
    top10BySlug: Map<String, List<SimilarArtist>>,
): Map<String, RetentionRow> {
    val rows = listOf(PROVIDER_LASTFM, PROVIDER_DEEZER, PROVIDER_LABS)
        .associateWith { RetentionRow(it, 0, 0) }
    for ((slug, byProvider) in artists) {
        for (provider in listOf(PROVIDER_LASTFM, PROVIDER_DEEZER, PROVIDER_LABS)) {
            val own = byProvider[provider]?.takeIf { it.isNotEmpty() } ?: continue
            val ownTop5 = own.take(5).map { key(it.name) }.toSet()
            val row = rows.getValue(provider)
            row.possible += ownTop5.size
            row.retained += top5Retained(top10BySlug.getValue(slug), ownTop5)
        }
    }
    return rows
}

class TieBreakConfoundProbeTest {

    /**
     * Harness-can-fail evidence: three artists hand-picked to clamp at 1.0 in a known flatten
     * order. Alpha and Beta each get contributions summing to exactly 1.0 (a two-way tie), Gamma is
     * unique at 0.4 and must always sort last. Reversing the pre-sort order must swap Alpha and
     * Beta's relative position; a shuffle that reduces to the identity permutation (seed chosen so
     * `shuffled` returns the same order — verified by trial) must match the baseline.
     */
    @Test
    fun `known tie-break case matches hand-computed order`() {
        val lastfm = listOf(SimilarArtist("Alpha", matchScore = 0.6f, sources = listOf("lastfm")))
        val deezer = listOf(
            SimilarArtist("Alpha", matchScore = 0.4f, sources = listOf("deezer")),
            SimilarArtist("Beta", matchScore = 1.0f, sources = listOf("deezer")),
            SimilarArtist("Gamma", matchScore = 0.4f, sources = listOf("deezer")),
        )
        val flattened = lastfm + deezer
        val grouped = groupSumClamp(flattened)

        // Given - Alpha (0.6+0.4=1.0) is first-seen before Beta (1.0) in the flattened list
        assertEquals(listOf("Alpha", "Beta", "Gamma"), grouped.map { it.name })

        // When - baseline stable sort
        val baseline = stableSortDescending(grouped)
        // Then - the tie keeps flatten order: Alpha before Beta, both before Gamma
        assertEquals(listOf("Alpha", "Beta", "Gamma"), baseline.map { it.name })

        // When - the pre-sort order is reversed
        val reversed = stableSortDescending(grouped.reversed())
        // Then - the tie flips: Beta now sorts before Alpha
        assertEquals(listOf("Beta", "Alpha", "Gamma"), reversed.map { it.name })
    }

    @Test
    fun `mechanism and tie-break sensitivity probe`() {
        val artists = ARTISTS.map { it to loadArtist(it) }

        val sb = StringBuilder()
        sb.appendLine("# Control tie-break confound probe")
        sb.appendLine()
        sb.appendLine("## Part 1 - mechanism: clamped-at-1.0 entries in the real top10")
        sb.appendLine("flatten order used: lastfm, deezer, labs (the order the original harness/report used)")
        sb.appendLine()

        var clampedTotal = 0
        var clampedInFirstOccurrenceOrder = 0
        for ((slug, byProvider) in artists) {
            val flattened = flatten(byProvider)
            val real = SimilarArtistMerger.mergeArtists(flattened)
            val myGrouped = groupSumClamp(flattened)
            val mySorted = stableSortDescending(myGrouped)
            // Cross-check: my re-derivation must match the real production merger exactly, or
            // nothing downstream in this probe can be trusted.
            assertEquals(real.map { it.name }, mySorted.map { it.name })
            for (i in real.indices) assertEquals(real[i].matchScore, mySorted[i].matchScore, EPS)

            val top10 = real.take(10)
            val firstOccurrence = flattened.mapIndexedNotNull { idx, a -> key(a.name) to idx }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.min() }
            val clamped = top10.filter { it.matchScore >= 1.0f - EPS }
            clampedTotal += clamped.size
            val expectedOrderByFirstOccurrence = clamped.sortedBy { firstOccurrence[key(it.name)] }
            val matchesFirstOccurrenceOrder = clamped.map { key(it.name) } == expectedOrderByFirstOccurrence.map { key(it.name) }
            if (matchesFirstOccurrenceOrder) clampedInFirstOccurrenceOrder += clamped.size

            sb.appendLine("- $slug: ${clamped.size} entries at 1.0 in top10, order-matches-first-occurrence=$matchesFirstOccurrenceOrder")
            for (c in clamped) {
                sb.appendLine(
                    "  - ${c.name} sources=${c.sources} firstOccurrenceIndex=${firstOccurrence[key(c.name)]}",
                )
            }
        }
        sb.appendLine()
        sb.appendLine(
            "Total clamped-at-1.0 top10 entries across 12 artists: $clampedTotal; " +
                "artists where clamped-entry order matches ascending first-occurrence index: " +
                "counted per-artist above (an artist counts if ALL its clamped entries are in that order)",
        )
        sb.appendLine()
        sb.appendLine(
            "**Production-order caveat**: this probe's flatten order (lastfm, deezer, labs) matches " +
                "the original report's harness, but NOT musicmeta-core's real registration order. " +
                "`EnrichmentEngine.kt` registers Deezer, then ListenBrainz, then Last.fm last (and " +
                "only if an API key is configured) — see `addProvider` calls around line 371-388. " +
                "If provider priority order determines eligible-list order end to end, real " +
                "production's first-seen provider for a clamped tie is Deezer, not Last.fm, which " +
                "is the opposite of what both the original report and this section's baseline " +
                "assumed. Not verified further here (would need reading `ProviderChain`'s async " +
                "collection all the way through to confirm registration order survives to " +
                "`awaitAll()`'s result order) - flagged, not chased down.",
        )

        sb.appendLine()
        sb.appendLine("## Part 2 - metric 3 under three tie-break orders, same scores throughout")

        val baselineTop10 = artists.associate { (slug, byProvider) ->
            slug to SimilarArtistMerger.mergeArtists(flatten(byProvider)).take(10)
        }
        val reversedTop10 = artists.associate { (slug, byProvider) ->
            slug to stableSortDescending(groupSumClamp(flatten(byProvider)).reversed()).take(10)
        }
        fun shuffledTop10(seed: Long) = artists.associate { (slug, byProvider) ->
            slug to stableSortDescending(
                groupSumClamp(flatten(byProvider)).shuffled(kotlin.random.Random(seed)),
            ).take(10)
        }

        val orders = linkedMapOf(
            "(a) baseline (HEAD order)" to baselineTop10,
            "(b) reversed flatten order" to reversedTop10,
            "(c) shuffle seed=42" to shuffledTop10(42),
            "(c) shuffle seed=7" to shuffledTop10(7),
            "(c) shuffle seed=123" to shuffledTop10(123),
        )

        val allRows = mutableMapOf<String, Map<String, RetentionRow>>()
        for ((label, top10ByArtist) in orders) {
            val rows = metric3ForOrder(artists, top10ByArtist)
            allRows[label] = rows
            sb.appendLine()
            sb.appendLine("### $label")
            for (provider in listOf(PROVIDER_LASTFM, PROVIDER_DEEZER, PROVIDER_LABS)) {
                val row = rows.getValue(provider)
                sb.appendLine("- $provider: ${row.retained}/${row.possible}")
            }
        }

        sb.appendLine()
        sb.appendLine("## Part 3 - range across tie-break orders, per provider")
        for (provider in listOf(PROVIDER_LASTFM, PROVIDER_DEEZER, PROVIDER_LABS)) {
            val values = allRows.values.map { it.getValue(provider).retained }
            sb.appendLine("- $provider: min=${values.min()} max=${values.max()} range=${values.max() - values.min()}")
        }

        val outDir = File("../probe-results")
        outDir.mkdirs()
        File(outDir, "tiebreak-confound.md").writeText(sb.toString())
        println(sb.toString())
    }
}
