package com.landofoz.musicmeta.provider.musicbrainz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline, no-API-dependency tests for [MusicBrainzQualifierFallback] — the candidate-generation
 * and ranking primitives behind the qualifier-fallback fix
 * (`.scratch/mb-search-parenthetical-qualifiers/issues/01-...`).
 *
 * Live resolution behavior (search-then-fallback against a real/mocked API) is covered separately
 * in [MusicBrainzQualifierFallbackIntegrationTest] — this file only exercises the pure functions.
 */
class MusicBrainzQualifierFallbackTest {

    // --- qualifierFallbackCandidates: whole-group-conformance + progressive stripping ---

    @Test
    fun `original title is always the first candidate, with no removed tags`() {
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates("Master Of Puppets (Remastered)")
        assertEquals("Master Of Puppets (Remastered)", candidates.first().title)
        assertTrue(candidates.first().removedTags.isEmpty())
    }

    @Test
    fun `a single trailing qualifier group is peeled into a second candidate`() {
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates("Master Of Puppets (Remastered)")
        assertEquals(2, candidates.size)
        assertEquals("Master Of Puppets", candidates[1].title)
        assertEquals(listOf("remaster"), candidates[1].removedTags.map { it.kind })
    }

    @Test
    fun `two trailing qualifier groups are peeled most-specific-first`() {
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(
            "Ride The Lightning (Deluxe) (Remastered)",
        )
        assertEquals(
            listOf("Ride The Lightning (Deluxe) (Remastered)", "Ride The Lightning (Deluxe)", "Ride The Lightning"),
            candidates.map { it.title },
        )
        // The second candidate has only the (Remastered) tag stripped so far; the third has both.
        assertEquals(listOf("remaster"), candidates[1].removedTags.map { it.kind })
        assertEquals(listOf("deluxe", "remaster"), candidates[2].removedTags.map { it.kind })
    }

    @Test
    fun `a slash-separated group strips as one group with multiple tags`() {
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(
            "Master Of Puppets (Deluxe Box Set / Remastered)",
        )
        assertEquals(2, candidates.size)
        assertEquals("Master Of Puppets", candidates[1].title)
        assertEquals(listOf("deluxe_box_set", "remaster"), candidates[1].removedTags.map { it.kind })
    }

    @Test
    fun `Album (Not Remastered) is left untouched, not wrongly stripped`() {
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates("Album (Not Remastered)")
        assertEquals(1, candidates.size)
    }

    @Test
    fun `Album (Live over Remastered) mixed group is left untouched`() {
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates("Album (Live / Remastered)")
        assertEquals(1, candidates.size)
    }

    @Test
    fun `a qualifier word embedded in a longer non-conforming phrase is left untouched`() {
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(
            "Album (Deluxe Edition soundtrack)",
        )
        assertEquals(1, candidates.size)
    }

    @Test
    fun `mismatched bracket delimiters are left untouched`() {
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates("Album (Deluxe]")
        assertEquals(1, candidates.size)
    }

    @Test
    fun `Live is not a recognized qualifier, so no fallback candidate is generated`() {
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates("Master Of Puppets (Live)")
        assertEquals(1, candidates.size)
    }

    @Test
    fun `a legitimate parenthetical with no qualifier vocabulary produces no fallback`() {
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(
            "Welcome Home (Sanitarium)",
        )
        assertEquals(1, candidates.size)
    }

    // --- pickBestMatch: score-primary, structured kind/year tie-break ---

    private data class Mock(val id: String, val title: String, val disambiguation: String?, val score: Int = 100)

    private fun pick(
        pool: List<Mock>,
        removedTags: List<MusicBrainzQualifierFallback.QualifierTag>,
    ): Mock? = MusicBrainzQualifierFallback.pickBestMatch(
        pool, removedTags,
        scoreOf = { it.score },
        textOf = { "${it.disambiguation.orEmpty()} ${it.title}" },
    )

    private fun tagsFor(title: String, candidateTitle: String) =
        MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)
            .first { MusicBrainzQualifierFallback.normalize(it.title) == MusicBrainzQualifierFallback.normalize(candidateTitle) }
            .removedTags

    @Test
    fun `legacy-vs-special - the generic edition word must not cross-match`() {
        val tags = tagsFor("Kind of Blue (Legacy Edition)", "Kind of Blue")
        val pool = listOf(
            Mock("A", "Kind of Blue", "special edition"),
            Mock("B", "Kind of Blue", "legacy edition"),
        )
        assertEquals("B", pick(pool, tags)?.id)
    }

    @Test
    fun `remaster-year specificity - exact year outranks kind-only no-year match`() {
        val tags = tagsFor("Enter Sandman (Remastered 2021)", "Enter Sandman")
        val pool = listOf(
            Mock("A", "Enter Sandman", "remastered 2017"),
            Mock("B", "Enter Sandman", "remastered 2021"),
        )
        assertEquals("B", pick(pool, tags)?.id)
    }

    @Test
    fun `combined evidence - two matched tags outrank one`() {
        val tags = tagsFor("Ride The Lightning (Deluxe) (Remastered)", "Ride The Lightning")
        val pool = listOf(
            Mock("A", "Ride The Lightning", "deluxe edition"),
            Mock("B", "Ride The Lightning", "remastered"),
            Mock("C", "Ride The Lightning", "deluxe / remastered"),
        )
        assertEquals("C", pick(pool, tags)?.id)
    }

    @Test
    fun `no match preserves the pool's existing order, unchanged from today's behavior`() {
        val tags = tagsFor("Master Of Puppets (Remastered)", "Master Of Puppets")
        val pool = listOf(
            Mock("A", "Master of Puppets", "DCC Compact Classics"),
            Mock("B", "Master of Puppets", "45 RPM Series"),
        )
        assertEquals("A", pick(pool, tags)?.id)
    }

    @Test
    fun `MusicBrainz score is primary, a lower-scoring qualifier match must not beat a higher score`() {
        val tags = tagsFor("Master Of Puppets (Legacy Edition)", "Master Of Puppets")
        val pool = listOf(
            Mock("A", "Master of Puppets", "no qualifier evidence", score = 100),
            Mock("B", "Master of Puppets", "legacy edition", score = 80),
        )
        assertEquals("A", pick(pool, tags)?.id)
    }

    @Test
    fun `unknown year beats an explicitly conflicting year, not a tie with it`() {
        val tags = tagsFor("Enter Sandman (Remastered 2021)", "Enter Sandman")
        val pool = listOf(
            Mock("A", "Enter Sandman", "remastered 2017"),
            Mock("B", "Enter Sandman", "remastered"),
        )
        assertEquals("B", pick(pool, tags)?.id)
    }

    @Test
    fun `not remastered and unremastered must not match the remaster keyword`() {
        val tags = tagsFor("Master Of Puppets (Remastered)", "Master Of Puppets")
        val pool = listOf(
            Mock("A", "Master of Puppets", "not remastered"),
            Mock("B", "Master of Puppets", "unremastered mix"),
            Mock("C", "Master of Puppets", "no qualifier evidence at all"),
        )
        // All three score 0 for the remaster tag -> stable order preserved, A stays first.
        assertEquals("A", pick(pool, tags)?.id)
    }

    @Test
    fun `non-deluxe must not match deluxe, but super deluxe may`() {
        val tags = tagsFor("Ride The Lightning (Deluxe)", "Ride The Lightning")
        val pool = listOf(
            Mock("A", "Ride The Lightning", "non-deluxe pressing"),
            Mock("B", "Ride The Lightning", "super deluxe box"),
        )
        assertEquals("B", pick(pool, tags)?.id)
    }

    @Test
    fun `an empty pool has no best match`() {
        assertNull(pick(emptyList(), emptyList()))
    }

    @Test
    fun `no removed tags returns the pool's first entry, matching firstOrNull today`() {
        val pool = listOf(Mock("A", "Master of Puppets", "DCC Compact Classics"), Mock("B", "Master of Puppets", null))
        assertEquals("A", pick(pool, emptyList())?.id)
    }

    // --- normalize ---

    @Test
    fun `normalize lowercases, collapses whitespace, and trims`() {
        assertEquals("master of puppets", MusicBrainzQualifierFallback.normalize("  Master   Of Puppets  "))
    }

    @Test
    fun `normalize maps curly apostrophe to straight`() {
        assertEquals("collector's edition", MusicBrainzQualifierFallback.normalize("collector’s edition"))
    }
}
