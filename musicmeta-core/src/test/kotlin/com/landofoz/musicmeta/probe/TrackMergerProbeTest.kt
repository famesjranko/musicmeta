package com.landofoz.musicmeta.probe

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.SimilarTrack
import com.landofoz.musicmeta.engine.SimilarTrackMerger
import com.landofoz.musicmeta.provider.deezer.DeezerMapper
import com.landofoz.musicmeta.provider.deezer.DeezerTopTrack
import com.landofoz.musicmeta.provider.lastfm.LastFmMapper
import com.landofoz.musicmeta.provider.lastfm.LastFmSimilarTrack
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Throwaway measurement harness for the similar-track merger A/B (`tech-debt/09`). NOT shipped:
 * this file exists on `probe/track-merger-arm-*` branches only. It replays the frozen 2026-09-06
 * capture through the real provider mappers, calls this branch's `SimilarTrackMerger`, and writes
 * raw per-seed tables to `probe-results/`.
 *
 * ARM_NAME is the only thing that differs between branches; the arm itself lives in the merger.
 */
private const val ARM_NAME = "identifier"

private const val FIXTURES = "src/test/resources/probe/tracks"

/** `DeezerProvider.RELATED_ARTISTS_LIMIT`. */
private const val RELATED_ARTISTS_LIMIT = 5

/** `DeezerProvider.SIMILAR_TRACKS_LIMIT`. */
private const val SIMILAR_TRACKS_LIMIT = 20

private data class Seed(
    val slug: String,
    val set: String,
    val artist: String,
    val title: String,
    val deezerSeedTrackId: Long,
)

private fun fixture(seed: Seed, file: String): String =
    File("$FIXTURES/${seed.set}/${seed.slug}/$file").readText()

private fun seeds(): List<Seed> {
    val manifest = JSONArray(File("$FIXTURES/manifest.json").readText())
    return (0 until manifest.length()).map { i ->
        val o = manifest.getJSONObject(i)
        Seed(
            slug = o.getString("slug"),
            set = o.getString("set"),
            artist = o.getString("artist"),
            title = o.getString("title"),
            deezerSeedTrackId = o.getLong("deezer_seed_track_id"),
        )
    }
}

/** Mirrors `LastFmApi.parseSimilarTracks`, which is private. */
private fun lastFmTracks(seed: Seed): List<SimilarTrack> {
    val container = JSONObject(fixture(seed, "lastfm-similar.json")).optJSONObject("similartracks")
        ?: return emptyList()
    val array = container.optJSONArray("track") ?: return emptyList()
    val rows = (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        LastFmSimilarTrack(
            title = o.optString("name", ""),
            artist = o.optJSONObject("artist")?.optString("name", "").orEmpty(),
            matchScore = o.optString("match", "0").toFloatOrNull() ?: 0f,
            mbid = o.optString("mbid", "").takeIf { it.isNotBlank() },
        )
    }
    return LastFmMapper.toSimilarTracks(rows).tracks
}

private fun topTracks(seed: Seed, artistId: Long): List<DeezerTopTrack> {
    val data = JSONObject(fixture(seed, "deezer-top-$artistId.json")).optJSONArray("data")
        ?: return emptyList()
    return (0 until data.length()).map { i ->
        val o = data.getJSONObject(i)
        DeezerTopTrack(
            id = o.optLong("id"),
            title = o.optString("title", ""),
            artistName = o.optJSONObject("artist")?.optString("name", "").orEmpty(),
            albumTitle = o.optJSONObject("album")?.optString("title", ""),
            durationSec = o.optInt("duration"),
            rank = o.optInt("rank"),
        )
    }
}

private fun deezerKey(title: String, artist: String): String =
    "${title.trim().lowercase()}|${artist.trim().lowercase()}"

/** Reproduces `DeezerProvider.similarTracksFromRelatedArtists` and `dedupeSimilarTracks`. */
private fun deezerTracks(seed: Seed): List<SimilarTrack> {
    val related = JSONObject(fixture(seed, "deezer-related.json")).optJSONArray("data")
        ?: return emptyList()
    val ids = (0 until related.length()).map { related.getJSONObject(it).getLong("id") }
    val count = ids.size.coerceAtLeast(1)
    val seedKey = deezerKey(seed.title, seed.artist)
    val tracks = ids.take(RELATED_ARTISTS_LIMIT).withIndex().flatMap { (index, artistId) ->
        val artistScore = 1.0f - (index.toFloat() / count) * 0.9f
        topTracks(seed, artistId)
            .filterNot { it.id == seed.deezerSeedTrackId || deezerKey(it.title, it.artistName) == seedKey }
            .map { DeezerMapper.toSimilarTrack(it, artistScore) }
    }
    return tracks
        .groupBy { deezerKey(it.title, it.artist) }
        .map { (_, dupes) -> dupes.maxByOrNull { it.matchScore } ?: dupes.first() }
        .sortedByDescending { it.matchScore }
        .take(SIMILAR_TRACKS_LIMIT)
}

/**
 * The synthetic third contributor `plan.md` declares: Deezer's own list re-emitted verbatim under
 * the source id `synthetic`, so the merge sees two providers agreeing exactly and any tie observed
 * is the clamp's and nothing else's.
 */
private fun syntheticTracks(deezer: List<SimilarTrack>): List<SimilarTrack> =
    deezer.map { it.copy(sources = listOf("synthetic")) }

private fun rowKey(track: SimilarTrack): String =
    "${track.title.trim().lowercase()}:${track.artist.trim().lowercase()}"

private fun idsOf(identifiers: EnrichmentIdentifiers): Map<String, String> =
    buildMap {
        identifiers.musicBrainzId?.takeIf { it.isNotBlank() }?.let { put("musicBrainzId", it.trim().lowercase()) }
        identifiers.extra.forEach { (k, v) -> if (v.isNotBlank()) put(k, v.trim().lowercase()) }
    }

/** Metric 3: entries in the top ten sharing the list's maximum score. */
private fun topTies(merged: List<SimilarTrack>): Int {
    val top = merged.take(10)
    if (top.isEmpty()) return 0
    val max = top.first().matchScore
    return top.count { it.matchScore == max }
}

/**
 * Metric 5: with one contributor present the merged order must equal that provider's own row order
 * deduplicated by this arm's own grouping, because every arm folds duplicates before it sorts and a
 * raw-order comparison is unsatisfiable by any arm that folds anything (`tech-debt/08`).
 */
private fun soloIdentity(rows: List<SimilarTrack>): Pair<Boolean, Int> {
    if (rows.isEmpty()) return true to 0
    val merged = SimilarTrackMerger.mergeTracks(rows)
    val reference = SimilarTrackMerger.groupTracks(rows).map { rowKey(it.first()) }
    return (merged.map { rowKey(it) } == reference) to (merged.size - rows.size)
}

class TrackMergerProbeTest {

    /**
     * Harness-can-fail evidence: two synthetic contributors whose merged output is hand-computed.
     *
     * Alpha carries a Last.fm member, so the `GENUINE_SOURCE` rule gives it that member's 1.0 and
     * not the sum. Beta and Gamma are Deezer-only singletons at 0.6 and 0.5. No two rows in this
     * case share a key and no row carries an identifier, so every arm must agree on it: the control,
     * the identifier guard (nothing to guard) and the rescale (1.0 is already the maximum, so
     * dividing by it changes nothing).
     */
    @Test
    fun `hand computed case merges to the stated list`() {
        // Given - a Last.fm row agreeing with a Deezer row, and two Deezer-only rows
        val tracks = listOf(
            SimilarTrack("Alpha", "A", 1.0f, EnrichmentIdentifiers(), listOf("lastfm")),
            SimilarTrack("Alpha", "A", 0.9f, EnrichmentIdentifiers(), listOf("deezer")),
            SimilarTrack("Beta", "B", 0.6f, EnrichmentIdentifiers(), listOf("deezer")),
            SimilarTrack("Gamma", "C", 0.5f, EnrichmentIdentifiers(), listOf("deezer")),
        )
        // When - this arm merges them
        val merged = SimilarTrackMerger.mergeTracks(tracks)
        // Then - the order and every score match the hand computation
        assertEquals(listOf("Alpha", "Beta", "Gamma"), merged.map { it.title })
        assertEquals(listOf(1.0f, 0.6f, 0.5f), merged.map { it.matchScore })
    }

    @Test
    fun `run probe and write results`() {
        // Given - the frozen 24-seed workload and this arm's merger
        val out = File("probe-results")
        out.mkdirs()
        val report = JSONArray()
        val md = StringBuilder("# Arm `$ARM_NAME` - raw per-seed tables\n")

        // When - each seed's contributors are merged by this arm, with and without the synthetic third
        for (seed in seeds()) {
            val lastfm = lastFmTracks(seed)
            val deezer = deezerTracks(seed)
            val real = lastfm + deezer
            if (real.isEmpty()) continue
            val merged = SimilarTrackMerger.mergeTracks(real)
            val withSynthetic = SimilarTrackMerger.mergeTracks(real + syntheticTracks(deezer))

            val groups = SimilarTrackMerger.groupTracks(real)
            val multi = groups.filter { it.size > 1 }
            val conflicting = groups.filter { group ->
                group.map { idsOf(it.identifiers) }.let { maps ->
                    maps.any { a -> maps.any { b -> a.keys.intersect(b.keys).any { a[it] != b[it] } } }
                }
            }

            val json = JSONObject()
            json.put("slug", seed.slug)
            json.put("set", seed.set)
            json.put("lastfmRows", lastfm.size)
            json.put("deezerRows", deezer.size)
            json.put("entries", merged.size)
            json.put("groupsWithTwoMembers", multi.size)
            json.put("groupsWithConflictingIds", conflicting.size)
            json.put("topTiesWithSynthetic", topTies(withSynthetic))
            json.put("topScore", merged.firstOrNull()?.matchScore ?: 0f)
            json.put("entryKeys", JSONArray(merged.map { rowKey(it) }))
            json.put("top10", JSONArray(merged.take(10).map { "${rowKey(it)}=${it.matchScore}" }))
            json.put("top10Synthetic", JSONArray(withSynthetic.take(10).map { "${rowKey(it)}=${it.matchScore}" }))
            val (lastfmOk, lastfmDelta) = soloIdentity(lastfm)
            val (deezerOk, deezerDelta) = soloIdentity(deezer)
            json.put("soloLastfmOk", lastfmOk)
            json.put("soloLastfmFold", lastfmDelta)
            json.put("soloDeezerOk", deezerOk)
            json.put("soloDeezerFold", deezerDelta)
            json.put(
                "multiMemberGroups",
                JSONArray(
                    multi.map { group ->
                        JSONObject()
                            .put("key", rowKey(group.first()))
                            .put("members", JSONArray(group.map { "${it.sources}:${idsOf(it.identifiers)}" }))
                    },
                ),
            )
            report.put(json)

            md.appendLine("\n## ${seed.set}/${seed.slug} - ${seed.artist} / ${seed.title}")
            md.appendLine("lastfm=${lastfm.size} deezer=${deezer.size} entries=${merged.size} " +
                "multiMemberGroups=${multi.size} conflictingIdGroups=${conflicting.size} " +
                "topScore=${merged.firstOrNull()?.matchScore} tiesWithSynthetic=${topTies(withSynthetic)}")
            merged.take(10).forEachIndexed { i, t ->
                md.appendLine("${i + 1}. ${t.title} - ${t.artist} - ${t.matchScore} - ${t.sources}")
            }
        }

        // Then - the arm's own tables are on disk for the cross-arm comparison to read
        File(out, "$ARM_NAME.json").writeText(report.toString(1))
        File(out, "$ARM_NAME.md").writeText(md.toString())
        assertEquals(24, report.length())
    }
}
