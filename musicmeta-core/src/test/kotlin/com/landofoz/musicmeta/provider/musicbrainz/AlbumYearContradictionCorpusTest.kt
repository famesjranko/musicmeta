package com.landofoz.musicmeta.provider.musicbrainz

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [unlessPredatingFirstRelease]'s rule, scored against real MusicBrainz release groups.
 *
 * The rule was frozen before this corpus was captured — `corpora/album-year-contradiction/spec.md`,
 * written first, holds it and the decision rule it was judged by. What the corpus is for is the
 * false-positive count: a rule that tells a caller their *correct* identifier is wrong is worse than
 * one that misses, because nothing else in the response disagrees with it.
 *
 * The shipped function itself is what runs here, never a copy of its arithmetic: a rule the corpus
 * scored and a rule the engine applies must not be able to drift apart.
 *
 * A track-count rule was frozen and scored beside it, on this same corpus, and is not shipped. The
 * last test keeps that measurement executable, so the reason it was rejected stays a number rather
 * than becoming folklore the next reader has to take on trust.
 *
 * Provenance: `corpora/album-year-contradiction/provenance.md`.
 */
class AlbumYearContradictionCorpusTest {

    private data class Release(val year: Int, val tracks: Int)

    private data class Group(val artist: String, val album: String, val firstYear: Int, val releases: List<Release>)

    private fun corpus(): List<Group> {
        val text = checkNotNull(javaClass.getResourceAsStream("/corpora/album-year-contradiction/groups.json"))
            .bufferedReader().readText()
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            val row = array.getJSONObject(i)
            val releases = row.getJSONArray("releases")
            Group(
                artist = row.getString("artist"),
                album = row.getString("album"),
                firstYear = row.getInt("firstReleaseYear"),
                releases = (0 until releases.length()).map { j ->
                    val r = releases.getJSONObject(j)
                    Release(year = r.getInt("year"), tracks = r.getInt("tracks"))
                },
            )
        }
    }

    /**
     * The shipped rule, asked directly: [unlessPredatingFirstRelease] drops a lookup it contradicts.
     *
     * The release carries nothing but the group's first-release year, because that year and the
     * caller's are the only two things the rule reads.
     */
    private suspend fun yearContradicts(callerYear: Int, firstReleaseYear: Int): Boolean {
        val release = MusicBrainzRelease(
            id = "rel",
            title = "",
            artistCredit = null,
            date = null,
            country = null,
            barcode = null,
            tags = emptyList(),
            label = null,
            releaseType = null,
            releaseGroupId = null,
            disambiguation = null,
            score = 0,
            releaseGroupFirstReleaseDate = firstReleaseYear.toString(),
        )
        val lookup: MusicBrainzLookup<MusicBrainzRelease> = MusicBrainzLookup.Found(release)
        return lookup.unlessPredatingFirstRelease(callerYear) is MusicBrainzLookup.Absent
    }

    /** The rule frozen beside it and rejected: the caller's count against the named release's. */
    private fun trackCountContradicts(callerTracks: Int, releaseTracks: Int): Boolean =
        Math.abs(callerTracks - releaseTracks) > maxOf(2, releaseTracks / 5)

    @Test
    fun `no year a caller could legitimately hold contradicts its own album`() = runTest {
        // Given - every year MusicBrainz holds for a release in each group. A caller owning any of
        // those pressings has the right album, whichever release their identifier happens to name.
        val groups = corpus()

        // When - the rule is applied to each
        val falsePositives = groups.flatMap { g ->
            g.releases.filter { yearContradicts(it.year, g.firstYear) }
                .map { "${g.artist} / ${g.album}: ${it.year} vs ${g.firstYear}" }
        }

        // Then - none, over a corpus big enough for that to mean something
        val releases = groups.sumOf { it.releases.size }
        assertTrue("corpus shrank to $releases releases; zero findings would prove nothing", releases >= 3000)
        assertEquals(emptyList<String>(), falsePositives)
    }

    @Test
    fun `two years before an album's first release is past the slack the rule allows`() = runTest {
        // Given - the same groups, each paired with a year two before its own first release. This
        // pins the slack constant rather than any catch rate: the rule must still fire two years
        // out, for every real first-release year in the corpus, so the leeway is under two years.
        val groups = corpus()

        // When - the shipped rule is applied
        val reported = groups.count { yearContradicts(it.firstYear - 2, it.firstYear) }

        // Then - all of them
        assertEquals(groups.size, reported)
    }

    @Test
    fun `the rejected track-count rule fires on well over a quarter of correct albums`() {
        // Given - every ordered pair of releases within a group: a caller who identified their album
        // correctly, whose local tags came from a different pressing of it
        val groups = corpus()

        // When - the rejected rule is applied to each pair
        var pairs = 0
        var falsePositives = 0
        for (g in groups) {
            for (supplied in g.releases) {
                for (tagged in g.releases) {
                    if (supplied === tagged) continue
                    pairs++
                    if (trackCountContradicts(tagged.tracks, supplied.tracks)) falsePositives++
                }
            }
        }

        // Then - it accuses a correct album on 29% of them, from deluxe editions, bonus discs and
        // region variants. This is why the album check reads years and not counts, and why closing
        // this gap on the title was never attempted.
        assertEquals(109_604, pairs)
        assertTrue("expected the rejected rule to stay demonstrably unsafe, got $falsePositives", falsePositives > 30_000)
    }
}
