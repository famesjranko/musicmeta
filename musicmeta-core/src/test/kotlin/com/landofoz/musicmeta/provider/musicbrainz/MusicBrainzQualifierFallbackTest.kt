package com.landofoz.musicmeta.provider.musicbrainz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline, no-API-dependency tests for [MusicBrainzQualifierFallback] — the candidate-generation
 * and ranking primitives behind the qualifier fallback.
 *
 * Live resolution behavior (search-then-fallback against a real/mocked API) is covered separately
 * in [MusicBrainzQualifierFallbackIntegrationTest] — this file only exercises the pure functions.
 */
class MusicBrainzQualifierFallbackTest {

    // --- qualifierFallbackCandidates: whole-group-conformance + progressive stripping ---

    @Test
    fun `original title is always the first candidate, with no removed tags`() {
        // Given — a title carrying one trailing qualifier group
        val title = "Master Of Puppets (Remastered)"

        // When — fallback candidates are generated
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then — the caller's own text is tried first, unstripped
        assertEquals("Master Of Puppets (Remastered)", candidates.first().title)
        assertTrue(candidates.first().removedTags.isEmpty())
    }

    @Test
    fun `a single trailing qualifier group is peeled into a second candidate`() {
        // Given — a title whose parenthetical is entirely qualifier vocabulary
        val title = "Master Of Puppets (Remastered)"

        // When — fallback candidates are generated
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then — the bare title follows, carrying the tag that was stripped to reach it
        assertEquals(2, candidates.size)
        assertEquals("Master Of Puppets", candidates[1].title)
        assertEquals(listOf("remaster"), candidates[1].removedTags.map { it.kind })
    }

    @Test
    fun `two trailing qualifier groups are peeled most-specific-first`() {
        // Given — a title carrying two stackable qualifier groups
        val title = "Ride The Lightning (Deluxe) (Remastered)"

        // When — fallback candidates are generated
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then — each step drops one group, and the tags accumulate as it goes
        assertEquals(
            listOf("Ride The Lightning (Deluxe) (Remastered)", "Ride The Lightning (Deluxe)", "Ride The Lightning"),
            candidates.map { it.title },
        )
        assertEquals(listOf("remaster"), candidates[1].removedTags.map { it.kind })
        assertEquals(listOf("deluxe", "remaster"), candidates[2].removedTags.map { it.kind })
    }

    @Test
    fun `a slash-separated group strips as one group with multiple tags`() {
        // Given — one parenthetical holding two qualifiers separated by a slash
        val title = "Master Of Puppets (Deluxe Box Set / Remastered)"

        // When — fallback candidates are generated
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then — it peels in one step, not two, carrying both tags
        assertEquals(2, candidates.size)
        assertEquals("Master Of Puppets", candidates[1].title)
        assertEquals(listOf("deluxe_box_set", "remaster"), candidates[1].removedTags.map { it.kind })
    }

    @Test
    fun `Album (Not Remastered) is left untouched, not wrongly stripped`() {
        // Given — a negation that contains the qualifier keyword
        val title = "Album (Not Remastered)"

        // When — fallback candidates are generated
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then — nothing is stripped; the negation is part of the record's identity
        assertEquals(1, candidates.size)
    }

    @Test
    fun `Album (Live over Remastered) mixed group is left untouched`() {
        // Given — a group mixing a qualifier with a word that is not one
        val title = "Album (Live / Remastered)"

        // When — fallback candidates are generated
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then — the whole group must conform, so a mixed group never strips
        assertEquals(1, candidates.size)
    }

    @Test
    fun `a qualifier word embedded in a longer non-conforming phrase is left untouched`() {
        // Given — qualifier vocabulary inside a phrase that says more than the qualifier
        val title = "Album (Deluxe Edition soundtrack)"

        // When — fallback candidates are generated
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then — whole-group conformance holds, so the phrase survives intact
        assertEquals(1, candidates.size)
    }

    @Test
    fun `mismatched bracket delimiters are left untouched`() {
        // Given — a title whose brackets do not pair
        val title = "Album (Deluxe]"

        // When — fallback candidates are generated
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then — no group is recognised, so nothing is stripped
        assertEquals(1, candidates.size)
    }

    @Test
    fun `Live is not a recognized qualifier, so no fallback candidate is generated`() {
        // Given — a parenthetical naming a genuinely different recording
        val title = "Master Of Puppets (Live)"

        // When — fallback candidates are generated
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then — stripping it would search for a record the caller did not ask for
        assertEquals(1, candidates.size)
    }

    @Test
    fun `a legitimate parenthetical with no qualifier vocabulary produces no fallback`() {
        // Given — a parenthetical that is part of the actual song title
        val title = "Welcome Home (Sanitarium)"

        // When — fallback candidates are generated
        val candidates = MusicBrainzQualifierFallback.qualifierFallbackCandidates(title)

        // Then — the title is left whole
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
        // Given — "Legacy Edition" was stripped, and one candidate says "special edition" instead
        val tags = tagsFor("Kind of Blue (Legacy Edition)", "Kind of Blue")
        val pool = listOf(
            Mock("A", "Kind of Blue", "special edition"),
            Mock("B", "Kind of Blue", "legacy edition"),
        )

        // When — the pool is ranked on that evidence
        val result = pick(pool, tags)

        // Then — the shared word "edition" does not make the wrong one match
        assertEquals("B", result?.id)
    }

    @Test
    fun `remaster-year specificity - exact year outranks kind-only no-year match`() {
        // Given — the caller asked for the 2021 remaster and both candidates are remasters
        val tags = tagsFor("Enter Sandman (Remastered 2021)", "Enter Sandman")
        val pool = listOf(
            Mock("A", "Enter Sandman", "remastered 2017"),
            Mock("B", "Enter Sandman", "remastered 2021"),
        )

        // When — the pool is ranked on that evidence
        val result = pick(pool, tags)

        // Then — the exact year outranks a kind-only match
        assertEquals("B", result?.id)
    }

    @Test
    fun `combined evidence - two matched tags outrank one`() {
        // Given — two qualifiers were stripped, and one candidate evidences both
        val tags = tagsFor("Ride The Lightning (Deluxe) (Remastered)", "Ride The Lightning")
        val pool = listOf(
            Mock("A", "Ride The Lightning", "deluxe edition"),
            Mock("B", "Ride The Lightning", "remastered"),
            Mock("C", "Ride The Lightning", "deluxe / remastered"),
        )

        // When — the pool is ranked on that evidence
        val result = pick(pool, tags)

        // Then — evidence sums, so matching both beats matching either
        assertEquals("C", result?.id)
    }

    @Test
    fun `no match preserves the pool's existing order`() {
        // Given — neither candidate says anything about the stripped qualifier
        val tags = tagsFor("Master Of Puppets (Remastered)", "Master Of Puppets")
        val pool = listOf(
            Mock("A", "Master of Puppets", "DCC Compact Classics"),
            Mock("B", "Master of Puppets", "45 RPM Series"),
        )

        // When — the pool is ranked on that evidence
        val result = pick(pool, tags)

        // Then — both score 0 and the pool's own order stands
        assertEquals("A", result?.id)
    }

    @Test
    fun `MusicBrainz score is primary, a lower-scoring qualifier match must not beat a higher score`() {
        // Given — the only candidate evidencing the qualifier scores 20 points lower
        val tags = tagsFor("Master Of Puppets (Legacy Edition)", "Master Of Puppets")
        val pool = listOf(
            Mock("A", "Master of Puppets", "no qualifier evidence", score = 100),
            Mock("B", "Master of Puppets", "legacy edition", score = 80),
        )

        // When — the pool is ranked on that evidence
        val result = pick(pool, tags)

        // Then — evidence breaks ties beneath score; it does not override it
        assertEquals("A", result?.id)
    }

    @Test
    fun `unknown year beats an explicitly conflicting year, not a tie with it`() {
        // Given — the caller asked for 2021; one candidate says 2017 and the other says no year
        val tags = tagsFor("Enter Sandman (Remastered 2021)", "Enter Sandman")
        val pool = listOf(
            Mock("A", "Enter Sandman", "remastered 2017"),
            Mock("B", "Enter Sandman", "remastered"),
        )

        // When — the pool is ranked on that evidence
        val result = pick(pool, tags)

        // Then — silence outranks contradiction
        assertEquals("B", result?.id)
    }

    @Test
    fun `not remastered and unremastered must not match the remaster keyword`() {
        // Given — two candidates negate the keyword and one never mentions it
        val tags = tagsFor("Master Of Puppets (Remastered)", "Master Of Puppets")
        val pool = listOf(
            Mock("A", "Master of Puppets", "not remastered"),
            Mock("B", "Master of Puppets", "unremastered mix"),
            Mock("C", "Master of Puppets", "no qualifier evidence at all"),
        )

        // When — the pool is ranked on that evidence
        val result = pick(pool, tags)

        // Then — all three score 0, so the stable order keeps A first
        assertEquals("A", result?.id)
    }

    @Test
    fun `non-deluxe must not match deluxe, but super deluxe may`() {
        // Given — one candidate negates the qualifier and the other intensifies it
        val tags = tagsFor("Ride The Lightning (Deluxe)", "Ride The Lightning")
        val pool = listOf(
            Mock("A", "Ride The Lightning", "non-deluxe pressing"),
            Mock("B", "Ride The Lightning", "super deluxe box"),
        )

        // When — the pool is ranked on that evidence
        val result = pick(pool, tags)

        // Then — an intensifier still evidences the qualifier; a negation does not
        assertEquals("B", result?.id)
    }

    @Test
    fun `an empty pool has no best match`() {
        // Given — nothing survived the caller's own filtering
        val pool = emptyList<Mock>()

        // When — the empty pool is ranked
        val result = pick(pool, emptyList())

        // Then — null, rather than an exception
        assertNull(result)
    }

    @Test
    fun `no removed tags returns the pool's first entry`() {
        // Given — an ordinary title, so nothing was stripped to reach it
        val pool = listOf(Mock("A", "Master of Puppets", "DCC Compact Classics"), Mock("B", "Master of Puppets", null))

        // When — the pool is ranked with no tags at all
        val result = pick(pool, emptyList())

        // Then — every candidate scores 0, which makes the evidence tier inert
        assertEquals("A", result?.id)
    }

    // --- normalize ---

    @Test
    fun `normalize lowercases, collapses whitespace, and trims`() {
        // Given — a title padded and inconsistently spaced
        val raw = "  Master   Of Puppets  "

        // When — it is normalized for comparison
        val result = MusicBrainzQualifierFallback.normalize(raw)

        // Then — case, runs of whitespace and padding are all flattened
        assertEquals("master of puppets", result)
    }

    @Test
    fun `normalize maps curly apostrophe to straight`() {
        // Given — the typographic apostrophe MusicBrainz titles frequently carry
        val raw = "collector’s edition"

        // When — it is normalized for comparison
        val result = MusicBrainzQualifierFallback.normalize(raw)

        // Then — it matches text typed with a straight apostrophe
        assertEquals("collector's edition", result)
    }
}
