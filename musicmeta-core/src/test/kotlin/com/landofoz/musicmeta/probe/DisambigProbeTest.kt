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

/**
 * Throwaway measurement harness for the similar-artist disambiguation A/B. NOT shipped: this file
 * exists on probe branches only. It replays the frozen twelve-artist workload through the real
 * provider mappers, groups it with this branch's `SimilarArtistMerger.groupArtists`, and writes the
 * labels this arm would attach to `probe-results/`.
 *
 * ARM_NAME, armLabel() and armLink() are the only three things that differ between branches. No arm
 * touches grouping, scoring or order; the harness asserts that the merged list is unchanged.
 */
// ---- ARM BLOCK START ----
private const val ARM_NAME = "comment"

/**
 * The string this arm would attach to a merged group: the Labs `comment` of the member whose MBID
 * is the group's own.
 *
 * A same-name member carrying no MBID is never asked, because `groupArtists` attached it by
 * contributor order rather than by evidence.
 */
private fun armLabel(
    group: List<SimilarArtist>,
    groupMbid: String?,
    @Suppress("UNUSED_PARAMETER") calls: MutableList<String>,
): String? =
    group.firstOrNull { mbidOf(it) != null && mbidOf(it) == groupMbid }
        ?.disambiguation
        ?.takeIf { it.isNotBlank() }

/** The URL this arm would attach to a merged group. This arm surfaces no link. */
private fun armLink(
    group: List<SimilarArtist>,
    groupMbid: String?,
): String? = null

private fun lastFmRow(obj: JSONObject) = LastFmSimilarArtist(
    name = obj.optString("name", ""),
    matchScore = obj.optString("match", "0").toFloatOrNull() ?: 0f,
    mbid = obj.optString("mbid").takeIf { it.isNotBlank() },
)

private fun deezerRow(obj: JSONObject) =
    DeezerRelatedArtist(id = obj.optLong("id"), name = obj.optString("name", ""))

private fun labsRow(obj: JSONObject) = ListenBrainzSimilarArtist(
    artistMbid = obj.optString("artist_mbid"),
    name = obj.optString("name"),
    score = obj.optInt("score", 0),
    comment = obj.optString("comment").takeIf { it.isNotBlank() },
)
// ---- ARM BLOCK END ----

private const val PROVIDER_LASTFM = "lastfm"
private const val PROVIDER_DEEZER = "deezer"
private const val PROVIDER_LABS = "labs"

private val ARTISTS =
    listOf(
        "radiohead",
        "aphex-twin",
        "kendrick-lamar",
        "fleetwood-mac",
        "boards-of-canada",
        "4wheel",
        "changg",
        "sleep-token",
        "bjork",
        "fela-kuti",
        "tigran-hamasyan",
        "burial",
    )

private fun key(name: String) = name.trim().lowercase()

// ---- Parsers, mirroring each provider's real API parsing exactly (fixtures are raw HTTP bodies) ----

private fun parseLastFm(json: JSONObject): List<LastFmSimilarArtist> {
    val container = json.optJSONObject("similarartists") ?: return emptyList()
    val array = container.optJSONArray("artist") ?: return emptyList()
    return (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        lastFmRow(obj)
    }
}

private fun parseDeezer(json: JSONObject): List<DeezerRelatedArtist> {
    val data = json.optJSONArray("data") ?: return emptyList()
    return (0 until data.length()).map { i ->
        deezerRow(data.getJSONObject(i))
    }
}

private fun parseLabs(json: Any): List<ListenBrainzSimilarArtist> {
    val array = json as? JSONArray ?: return emptyList()
    val results = mutableListOf<ListenBrainzSimilarArtist>()
    for (i in 0 until array.length()) {
        val item = array.getJSONObject(i)
        if (item.optString("artist_mbid").isBlank()) continue
        if (item.optString("name").isBlank()) continue
        results += labsRow(item)
    }
    return results
}

private fun loadFixture(
    slug: String,
    file: String,
): String = File("src/test/resources/probe/fixtures/$slug/$file").readText()

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

private data class ArtistData(
    val slug: String,
    val byProvider: Map<String, List<SimilarArtist>>,
) {
    val contributing: Map<String, List<SimilarArtist>> = byProvider.filterValues { it.isNotEmpty() }

    /** Engine registration order: Deezer, then ListenBrainz, then Last.fm. */
    val flattened: List<SimilarArtist> =
        listOf(PROVIDER_DEEZER, PROVIDER_LABS, PROVIDER_LASTFM).flatMap { contributing[it].orEmpty() }
}

private fun mbidOf(artist: SimilarArtist): String? =
    artist.identifiers.musicBrainzId
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

private fun providerOf(artist: SimilarArtist): String = artist.sources.firstOrNull() ?: "?"

/** The MBID a group carries: the first one any member holds, which is what `groupArtists` keys on. */
private fun groupMbidOf(group: List<SimilarArtist>): String? = group.firstNotNullOfOrNull { mbidOf(it) }

class DisambigProbeTest {
    /**
     * Harness-can-fail evidence: the four split pairs are named up front, and the probe asserts it
     * finds exactly those and no others. A grouping or fixture change that loses one turns this red.
     */
    @Test
    fun `the workload holds exactly the four known split pairs`() {
        // Given - the frozen twelve-artist workload
        val artists = ARTISTS.map { ArtistData(it, loadArtist(it)) }

        // When - each artist's contributors are grouped by this branch's merger
        val found = mutableListOf<String>()
        for (a in artists) {
            val groups = SimilarArtistMerger.groupArtists(a.flattened)
            groups
                .groupBy { key(it.first().name) }
                .filterValues { it.size > 1 }
                .keys
                .forEach { found += "${a.slug}/$it" }
        }

        // Then - the four pairs bugs/30 named are the only name-key collisions
        assertEquals(
            listOf(
                "sleep-token/bad omens",
                "sleep-token/loathe",
                "sleep-token/spiritbox",
                "tigran-hamasyan/sungazer",
            ),
            found.sorted(),
        )
    }

    @Test
    fun `run probe and write results`() {
        // Given - the frozen workload and this arm's labelling
        val artists = ARTISTS.map { ArtistData(it, loadArtist(it)) }
        val calls = mutableListOf<String>()
        val perArtist = JSONArray()
        val splitPairs = JSONArray()
        var groupsTotal = 0
        var groupsLabelled = 0
        var groupsLinked = 0

        // When - every merged group is labelled by this arm
        for (a in artists) {
            val groups = SimilarArtistMerger.groupArtists(a.flattened)
            val merged = SimilarArtistMerger.mergeArtists(a.flattened)
            val artistCallsBefore = calls.size
            val labelled =
                groups.map { group ->
                    val gm = groupMbidOf(group)
                    Triple(group, armLabel(group, gm, calls), armLink(group, gm))
                }
            groupsTotal += groups.size
            groupsLabelled += labelled.count { !it.second.isNullOrBlank() }
            groupsLinked += labelled.count { !it.third.isNullOrBlank() }

            val byName = labelled.groupBy { key(it.first.first().name) }.filterValues { it.size > 1 }
            for ((name, pair) in byName) {
                val entry = JSONObject()
                entry.put("artist", a.slug)
                entry.put("name", name)
                entry.put(
                    "entries",
                    JSONArray(
                        pair.map { (group, label, link) ->
                            JSONObject()
                                .put("mbid", groupMbidOf(group) ?: JSONObject.NULL)
                                .put("contributors", JSONArray(group.map { providerOf(it) }))
                                .put("label", label ?: JSONObject.NULL)
                                .put("link", link ?: JSONObject.NULL)
                        },
                    ),
                )
                splitPairs.put(entry)
            }

            perArtist.put(
                JSONObject()
                    .put("artist", a.slug)
                    .put("groups", groups.size)
                    .put("labelled", labelled.count { !it.second.isNullOrBlank() })
                    .put("linked", labelled.count { !it.third.isNullOrBlank() })
                    .put("extraCalls", calls.size - artistCallsBefore)
                    .put(
                        "mergedTop10",
                        JSONArray(merged.take(10).map { "${it.name}|${"%.4f".format(it.matchScore)}" }),
                    ),
            )
        }

        // Then - the arm's tables and machine-readable results land under probe-results/
        val summary =
            JSONObject()
                .put("arm", ARM_NAME)
                .put("groupsTotal", groupsTotal)
                .put("groupsLabelled", groupsLabelled)
                .put("groupsLinked", groupsLinked)
                .put("extraUpstreamCalls", calls.size)
                .put("extraUpstreamCallDetail", JSONArray(calls))
                .put("splitPairs", splitPairs)
                .put("perArtist", perArtist)

        val outDir = File("../probe-results")
        outDir.mkdirs()
        File(outDir, "$ARM_NAME.json").writeText(summary.toString(2))

        val md = StringBuilder()
        md.appendLine("# Arm `$ARM_NAME`")
        md.appendLine()
        md.appendLine("- merged groups across the workload: $groupsTotal")
        md.appendLine("- groups carrying a non-blank label: $groupsLabelled")
        md.appendLine("- groups carrying a non-blank link: $groupsLinked")
        md.appendLine("- extra upstream calls over the whole workload: ${calls.size}")
        md.appendLine()
        md.appendLine("## Split pairs")
        md.appendLine()
        md.appendLine("| artist | name | mbid | contributors | label | link |")
        md.appendLine("|---|---|---|---|---|---|")
        for (i in 0 until splitPairs.length()) {
            val p = splitPairs.getJSONObject(i)
            val entries = p.getJSONArray("entries")
            for (j in 0 until entries.length()) {
                val e = entries.getJSONObject(j)
                md.appendLine(
                    "| ${p.getString("artist")} | ${p.getString("name")} | ${e.opt("mbid")} | " +
                        "${e.getJSONArray("contributors")} | ${e.opt("label")} | ${e.opt("link")} |",
                )
            }
        }
        File(outDir, "$ARM_NAME.md").writeText(md.toString())

        assertEquals(ARTISTS.size, perArtist.length())
        assertEquals(4, splitPairs.length())
        assertTrue(outDir.resolve("$ARM_NAME.json").exists())
    }
}
