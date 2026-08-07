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
        // Given
        val title = "Master Of Puppets (Remastered)"

        // When
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then
        assertEquals("Master Of Puppets (Remastered)", candidates.first().title)
        assertTrue(candidates.first().removedTags.isEmpty())
    }

    @Test
    fun `a single trailing qualifier group is peeled into a second candidate`() {
        // Given
        val title = "Master Of Puppets (Remastered)"

        // When
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then
        assertEquals(2, candidates.size)
        assertEquals("Master Of Puppets", candidates[1].title)
        assertEquals(listOf("remaster"), candidates[1].removedTags.map { it.kind })
    }

    @Test
    fun `two trailing qualifier groups are peeled most-specific-first`() {
        // Given
        val title = "Ride The Lightning (Deluxe) (Remastered)"

        // When
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then - the second candidate has only (Remastered) stripped so far; the third has both
        assertEquals(
            listOf("Ride The Lightning (Deluxe) (Remastered)", "Ride The Lightning (Deluxe)", "Ride The Lightning"),
            candidates.map { it.title },
        )
        assertEquals(listOf("remaster"), candidates[1].removedTags.map { it.kind })
        assertEquals(listOf("deluxe", "remaster"), candidates[2].removedTags.map { it.kind })
    }

    @Test
    fun `a slash-separated group strips as one group with multiple tags`() {
        // Given
        val title = "Master Of Puppets (Deluxe Box Set / Remastered)"

        // When
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then
        assertEquals(2, candidates.size)
        assertEquals("Master Of Puppets", candidates[1].title)
        assertEquals(listOf("deluxe_box_set", "remaster"), candidates[1].removedTags.map { it.kind })
    }

    @Test
    fun `Album (Not Remastered) is left untouched, not wrongly stripped`() {
        // Given
        val title = "Album (Not Remastered)"

        // When
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then
        assertEquals(1, candidates.size)
    }

    @Test
    fun `Album (Live over Remastered) mixed group is left untouched`() {
        // Given
        val title = "Album (Live / Remastered)"

        // When
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then
        assertEquals(1, candidates.size)
    }

    @Test
    fun `a qualifier word embedded in a longer non-conforming phrase is left untouched`() {
        // Given
        val title = "Album (Deluxe Edition soundtrack)"

        // When
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then
        assertEquals(1, candidates.size)
    }

    @Test
    fun `mismatched bracket delimiters are left untouched`() {
        // Given
        val title = "Album (Deluxe]"

        // When
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then
        assertEquals(1, candidates.size)
    }

    @Test
    fun `Live is not a recognized qualifier, so no fallback candidate is generated`() {
        // Given
        val title = "Master Of Puppets (Live)"

        // When
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then
        assertEquals(1, candidates.size)
    }

    @Test
    fun `a legitimate parenthetical with no qualifier vocabulary produces no fallback`() {
        // Given
        val title = "Welcome Home (Sanitarium)"

        // When
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then
        assertEquals(1, candidates.size)
    }

    // --- tagEvidence: structured kind/year qualifier evidence, ranked under score ---

    private data class Mock(val id: String, val title: String, val disambiguation: String?, val score: Int = 100)

    /**
     * Ranks [pool] by score, then by qualifier evidence, so these tests pin
     * [MusicBrainzQualifierFallback.tagEvidence]'s own tier semantics in isolation. This is NOT
     * production's ladder: [MusicBrainzReleaseRanking.pickBestRelease] puts identity above score and
     * the edition band between score and evidence.
     */
    private fun pick(
        pool: List<Mock>,
        removedTags: List<MusicBrainzQualifierFallback.QualifierTag>,
    ): Mock? = pool.maxWithOrNull(
        compareBy<Mock> { it.score }.thenBy { candidate ->
            MusicBrainzQualifierFallback.tagEvidence(
                "${candidate.disambiguation.orEmpty()} ${candidate.title}", removedTags,
            )
        },
    )

    private fun tagsFor(title: String, candidateTitle: String) =
        MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)
            .first { MusicBrainzQualifierFallback.normalize(it.title) == MusicBrainzQualifierFallback.normalize(candidateTitle) }
            .removedTags

    @Test
    fun `legacy-vs-special - the generic edition word must not cross-match`() {
        // Given
        val tags = tagsFor("Kind of Blue (Legacy Edition)", "Kind of Blue")
        val pool = listOf(
            Mock("A", "Kind of Blue", "special edition"),
            Mock("B", "Kind of Blue", "legacy edition"),
        )

        // When
        val result = pick(pool, tags)

        // Then
        assertEquals("B", result?.id)
    }

    @Test
    fun `remaster-year specificity - exact year outranks kind-only no-year match`() {
        // Given
        val tags = tagsFor("Enter Sandman (Remastered 2021)", "Enter Sandman")
        val pool = listOf(
            Mock("A", "Enter Sandman", "remastered 2017"),
            Mock("B", "Enter Sandman", "remastered 2021"),
        )

        // When
        val result = pick(pool, tags)

        // Then
        assertEquals("B", result?.id)
    }

    @Test
    fun `combined evidence - two matched tags outrank one`() {
        // Given
        val tags = tagsFor("Ride The Lightning (Deluxe) (Remastered)", "Ride The Lightning")
        val pool = listOf(
            Mock("A", "Ride The Lightning", "deluxe edition"),
            Mock("B", "Ride The Lightning", "remastered"),
            Mock("C", "Ride The Lightning", "deluxe / remastered"),
        )

        // When
        val result = pick(pool, tags)

        // Then
        assertEquals("C", result?.id)
    }

    @Test
    fun `no match preserves the pool's existing order`() {
        // Given
        val tags = tagsFor("Master Of Puppets (Remastered)", "Master Of Puppets")
        val pool = listOf(
            Mock("A", "Master of Puppets", "DCC Compact Classics"),
            Mock("B", "Master of Puppets", "45 RPM Series"),
        )

        // When
        val result = pick(pool, tags)

        // Then
        assertEquals("A", result?.id)
    }

    @Test
    fun `MusicBrainz score is primary, a lower-scoring qualifier match must not beat a higher score`() {
        // Given
        val tags = tagsFor("Master Of Puppets (Legacy Edition)", "Master Of Puppets")
        val pool = listOf(
            Mock("A", "Master of Puppets", "no qualifier evidence", score = 100),
            Mock("B", "Master of Puppets", "legacy edition", score = 80),
        )

        // When
        val result = pick(pool, tags)

        // Then
        assertEquals("A", result?.id)
    }

    @Test
    fun `unknown year beats an explicitly conflicting year, not a tie with it`() {
        // Given
        val tags = tagsFor("Enter Sandman (Remastered 2021)", "Enter Sandman")
        val pool = listOf(
            Mock("A", "Enter Sandman", "remastered 2017"),
            Mock("B", "Enter Sandman", "remastered"),
        )

        // When
        val result = pick(pool, tags)

        // Then
        assertEquals("B", result?.id)
    }

    @Test
    fun `not remastered and unremastered must not match the remaster keyword`() {
        // Given
        val tags = tagsFor("Master Of Puppets (Remastered)", "Master Of Puppets")
        val pool = listOf(
            Mock("A", "Master of Puppets", "not remastered"),
            Mock("B", "Master of Puppets", "unremastered mix"),
            Mock("C", "Master of Puppets", "no qualifier evidence at all"),
        )

        // When
        val result = pick(pool, tags)

        // Then - all three score 0 for the remaster tag, so the stable order preserves A first
        assertEquals("A", result?.id)
    }

    @Test
    fun `non-deluxe must not match deluxe, but super deluxe may`() {
        // Given
        val tags = tagsFor("Ride The Lightning (Deluxe)", "Ride The Lightning")
        val pool = listOf(
            Mock("A", "Ride The Lightning", "non-deluxe pressing"),
            Mock("B", "Ride The Lightning", "super deluxe box"),
        )

        // When
        val result = pick(pool, tags)

        // Then
        assertEquals("B", result?.id)
    }

    @Test
    fun `an empty pool has no best match`() {
        // Given
        val pool = emptyList<Mock>()

        // When
        val result = pick(pool, emptyList())

        // Then
        assertNull(result)
    }

    @Test
    fun `no removed tags returns the pool's first entry`() {
        // Given
        val pool = listOf(Mock("A", "Master of Puppets", "DCC Compact Classics"), Mock("B", "Master of Puppets", null))

        // When
        val result = pick(pool, emptyList())

        // Then
        assertEquals("A", result?.id)
    }

    // --- normalize ---

    @Test
    fun `normalize lowercases, collapses whitespace, and trims`() {
        // Given
        val raw = "  Master   Of Puppets  "

        // When
        val result = MusicBrainzQualifierFallback.normalize(raw)

        // Then
        assertEquals("master of puppets", result)
    }

    @Test
    fun `normalize maps curly apostrophe to straight`() {
        // Given
        val raw = "collector’s edition"

        // When
        val result = MusicBrainzQualifierFallback.normalize(raw)

        // Then
        assertEquals("collector's edition", result)
    }
}
