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
 * Throwaway measurement harness for the merger dedup-key A/B. NOT shipped: this file exists on
 * probe branches only. It replays the frozen fixtures through the real provider mappers, calls this
 * branch's `SimilarArtistMerger`, and writes raw per-artist tables to `probe-results/`.
 *
 * ARM_NAME and installArm() are the only two things that differ between branches.
 */
private const val ARM_NAME = "control"

/** Arm-specific wiring. The control and the MBID arm need none; the pool arms install their pool. */
private fun installArm() = Unit

private const val PROVIDER_LASTFM = "lastfm"
private const val PROVIDER_DEEZER = "deezer"
private const val PROVIDER_LABS = "labs"

private val ARTISTS = listOf(
    "radiohead", "aphex-twin", "kendrick-lamar", "fleetwood-mac", "boards-of-canada", "4wheel",
    "changg", "sleep-token", "bjork", "fela-kuti", "tigran-hamasyan", "burial",
)

private fun key(name: String) = name.trim().lowercase()

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

    return mapOf(
        PROVIDER_LASTFM to LastFmMapper.toSimilarArtists(parseLastFm(lastfmJson)).artists,
        PROVIDER_DEEZER to DeezerMapper.toSimilarArtists(parseDeezer(deezerJson)).artists,
        PROVIDER_LABS to ListenBrainzMapper.toSimilarArtists(parseLabs(labsJson)).artists,
    )
}

private data class ArtistData(val slug: String, val byProvider: Map<String, List<SimilarArtist>>) {
    val contributing: Map<String, List<SimilarArtist>> = byProvider.filterValues { it.isNotEmpty() }

    /** Engine registration order: Deezer, then ListenBrainz, then Last.fm. */
    val flattened: List<SimilarArtist> =
        listOf(PROVIDER_DEEZER, PROVIDER_LABS, PROVIDER_LASTFM).flatMap { contributing[it].orEmpty() }
}

private fun mbidOf(artist: SimilarArtist): String? = artist.identifiers.musicBrainzId

private fun providerOf(artist: SimilarArtist): String = artist.sources.firstOrNull() ?: "?"

/** Every group this arm forms that holds more than one distinct name key, as a JSON array. */
private fun unifications(groups: List<List<SimilarArtist>>): JSONArray {
    val out = JSONArray()
    for (group in groups) {
        val names = group.map { it.name }.distinctBy { key(it) }
        if (names.size < 2) continue
        val entry = JSONObject()
        entry.put("names", JSONArray(names))
        entry.put(
            "members",
            JSONArray(group.map { "${providerOf(it)}:${it.name}:${mbidOf(it) ?: "-"}" }),
        )
        entry.put("mbids", JSONArray(group.mapNotNull { mbidOf(it) }.distinct()))
        out.put(entry)
    }
    return out
}

/** Metric 5: groups whose members carry two or more different MBIDs. */
private fun conflictingGroups(groups: List<List<SimilarArtist>>): List<List<SimilarArtist>> =
    groups.filter { group -> group.mapNotNull { mbidOf(it) }.distinct().size > 1 }

/**
 * Metric 4: with one provider present the merged order must equal that provider's own order.
 *
 * The reference order is the provider's raw order deduplicated by this arm's own grouping, because
 * every arm folds duplicates before it sorts and a raw-order comparison would fail on the fold
 * rather than on any arm's change.
 */
private fun soloIdentityFailures(artists: List<ArtistData>): List<String> {
    val failures = mutableListOf<String>()
    for (a in artists) {
        for ((provider, own) in a.contributing) {
            val groups = SimilarArtistMerger.groupArtists(own)
            val reference = groups.map { it.first().name }
            val merged = SimilarArtistMerger.mergeArtists(own).map { it.name }
            if (merged != reference) failures += "${a.slug}/$provider"
        }
    }
    return failures
}

class MergerDedupProbeTest {

    /**
     * Harness-can-fail evidence: two synthetic providers whose merged output is hand-computed.
     *
     * Alpha is named by both providers and sums to 0.9 + 1.0 = 1.9, the merged maximum, so it
     * rescales to 1.0; Beta (0.6) and Gamma (0.5) rescale to 0.6/1.9 and 0.5/1.9. Every arm agrees
     * on this case: no two names in it differ, so no arm's key can group anything the control's
     * does not.
     */
    @Test
    fun `hand computed two-provider case merges to the stated list`() {
        // Given - two providers whose lists share one name and differ on the rest
        installArm()
        val one = listOf(
            SimilarArtist(name = "Alpha", matchScore = 0.9f, sources = listOf("one")),
            SimilarArtist(name = "Beta", matchScore = 0.6f, sources = listOf("one")),
        )
        val two = listOf(
            SimilarArtist(name = "Alpha", matchScore = 1.0f, sources = listOf("two")),
            SimilarArtist(name = "Gamma", matchScore = 0.5f, sources = listOf("two")),
        )

        // When - this arm merges them
        val merged = SimilarArtistMerger.mergeArtists(one + two)

        // Then - the order and every score match the hand computation
        assertEquals(listOf("Alpha", "Beta", "Gamma"), merged.map { it.name })
        assertEquals(1.0f, merged[0].matchScore, 1e-4f)
        assertEquals(0.6f / 1.9f, merged[1].matchScore, 1e-4f)
        assertEquals(0.5f / 1.9f, merged[2].matchScore, 1e-4f)
    }

    @Test
    fun `run probe and write results`() {
        // Given - the frozen twelve-artist workload and this arm's wiring
        installArm()
        val artists = ARTISTS.map { ArtistData(it, loadArtist(it)) }

        // When - each artist's contributors are merged by this arm
        val perArtist = JSONObject()
        val md = StringBuilder()
        md.appendLine("# Arm `$ARM_NAME` - raw per-artist tables")
        md.appendLine()
        var conflicts = 0
        var unified = 0
        for (a in artists) {
            val groups = SimilarArtistMerger.groupArtists(a.flattened)
            val merged = SimilarArtistMerger.mergeArtists(a.flattened)
            val unifiedGroups = unifications(groups)
            unified += unifiedGroups.length()
            conflicts += conflictingGroups(groups).size

            val artistJson = JSONObject()
            artistJson.put("contributors", JSONArray(a.contributing.keys.toList()))
            artistJson.put("entries", merged.size)
            artistJson.put(
                "merged",
                JSONArray(
                    merged.map {
                        JSONObject()
                            .put("name", it.name)
                            .put("score", it.matchScore.toDouble())
                            .put("sources", JSONArray(it.sources))
                            .put("mbid", mbidOf(it) ?: JSONObject.NULL)
                    },
                ),
            )
            artistJson.put("unifications", unifiedGroups)
            artistJson.put(
                "conflictingGroups",
                JSONArray(conflictingGroups(groups).map { g -> JSONArray(g.map { "${it.name}=${mbidOf(it)}" }) }),
            )
            perArtist.put(a.slug, artistJson)

            md.appendLine("## ${a.slug}")
            md.appendLine()
            md.appendLine("Contributors: ${a.contributing.keys.joinToString(", ")}; merged entries: ${merged.size}")
            md.appendLine()
            md.appendLine("| # | name | score | sources |")
            md.appendLine("|---|---|---|---|")
            merged.take(10).forEachIndexed { i, s ->
                md.appendLine("| ${i + 1} | ${s.name} | ${"%.6f".format(s.matchScore)} | ${s.sources.joinToString("+")} |")
            }
            md.appendLine()
            if (unifiedGroups.length() > 0) {
                md.appendLine("Unified groups (more than one distinct name key):")
                for (i in 0 until unifiedGroups.length()) {
                    val g = unifiedGroups.getJSONObject(i)
                    md.appendLine("- ${g.getJSONArray("members")}")
                }
                md.appendLine()
            }
        }

        // Then - the arm's tables and machine-readable results land under probe-results/
        val failures = soloIdentityFailures(artists)
        val summary = JSONObject()
        summary.put("arm", ARM_NAME)
        summary.put("artists", perArtist)
        summary.put("soloIdentityFailures", JSONArray(failures))
        summary.put("unifiedGroupCount", unified)
        summary.put("conflictingGroupCount", conflicts)

        md.appendLine("## Summary")
        md.appendLine()
        md.appendLine("- unified groups (more than one distinct name key): $unified")
        md.appendLine("- groups with conflicting MBIDs (metric 5): $conflicts")
        md.appendLine("- single-contributor identity failures (metric 4): ${failures.size} $failures")

        val outDir = File("../probe-results")
        outDir.mkdirs()
        File(outDir, "$ARM_NAME.md").writeText(md.toString())
        File(outDir, "$ARM_NAME.json").writeText(summary.toString(2))
        assertEquals(ARTISTS.size, perArtist.length())
    }
}
