package com.landofoz.musicmeta.provider.musicbrainz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Offline, no-API-dependency tests for [MusicBrainzReleaseRanking] — the tie-break ladder that
 * picks a single release out of a search pool that routinely has dozens tied at the top score.
 *
 * Each test pins exactly one tier of the ladder, using the live-pool evidence recorded in
 * `.scratch/musicbrainz-release-ranking/prototypes/report.md` — a tier deleted from the
 * implementation should turn exactly the corresponding test red, not any other.
 */
class MusicBrainzReleaseRankingTest {

    private fun release(
        id: String,
        title: String = "Test Album",
        date: String? = null,
        status: String? = "Official",
        releaseType: String? = "Album",
        secondaryTypes: List<String> = emptyList(),
        disambiguation: String? = null,
        score: Int = 100,
        trackCount: Int? = null,
        releaseGroupDisambiguation: String? = null,
    ): MusicBrainzRelease = MusicBrainzRelease(
        id = id,
        title = title,
        artistCredit = "Test Artist",
        date = date,
        country = null,
        barcode = null,
        tags = emptyList(),
        label = null,
        releaseType = releaseType,
        releaseGroupId = "rg-$id",
        disambiguation = disambiguation,
        score = score,
        status = status,
        secondaryTypes = secondaryTypes,
        trackCount = trackCount,
        releaseGroupDisambiguation = releaseGroupDisambiguation,
    )

    private fun pick(
        candidates: List<MusicBrainzRelease>,
        minMatchScore: Int = 80,
        removedTags: List<MusicBrainzQualifierFallback.QualifierTag> = emptyList(),
    ): MusicBrainzRelease? = MusicBrainzReleaseRanking.pickBestRelease(candidates, minMatchScore, removedTags)

    /** The qualifier tags "… (Remastered)" strips, as the fallback path derives them. */
    private fun remasteredTags() =
        MusicBrainzQualifierFallback.qualifierFallbackCandidates("Master Of Puppets (Remastered)")
            .first { MusicBrainzQualifierFallback.normalize(it.title) == "master of puppets" }
            .removedTags

    // --- identity outranks score (tiers 1-3 above tier 4) ---

    @Test
    fun `identity outranks score - a score-100 Live Bootleg Album loses to a score-97 plain Official Album`() {
        // Given - real case: Prince "Purple Rain", whose only release scoring 100 is an Album+Live
        // Bootleg while every genuine Official soundtrack pressing scores 97
        val bootleg = release(id = "a", score = 100, status = "Bootleg", secondaryTypes = listOf("Live"), date = "1984")
        val official = release(id = "b", score = 97, date = "1984")

        // When
        val result = pick(listOf(bootleg, official))

        // Then
        assertEquals(official, result)
    }

    @Test
    fun `score still decides within one identity class`() {
        // Given
        val lowerScore = release(id = "a", score = 88, date = "1984")
        val higherScore = release(id = "b", score = 100, date = "1984")

        // When
        val result = pick(listOf(lowerScore, higherScore))

        // Then
        assertEquals(higherScore, result)
    }

    @Test
    fun `an all-Live pool still resolves rather than returning null`() {
        // Given - real case: Nirvana "MTV Unplugged in New York", where every candidate carries
        // secondary type Live, so the tier must not eliminate the whole pool
        val earlier = release(id = "a", secondaryTypes = listOf("Live"), date = "1994")
        val later = release(id = "b", secondaryTypes = listOf("Live"), date = "2019")

        // When
        val result = pick(listOf(later, earlier))

        // Then
        assertEquals(earlier, result)
    }

    @Test
    fun `an all-non-Official pool still resolves rather than returning null`() {
        // Given
        val earlier = release(id = "a", status = "Bootleg", date = "1994")
        val later = release(id = "b", status = "Bootleg", date = "2019")

        // When
        val result = pick(listOf(later, earlier))

        // Then
        assertEquals(earlier, result)
    }

    @Test
    fun `a same-titled Single loses to the Album even at equal score`() {
        // Given - real case: Metallica "Master Of Puppets", whose pool holds a 2-track Single tied
        // at score 100 with the 8-track Album releases
        val album = release(id = "album", releaseType = "Album", score = 100, date = "1986", trackCount = 8)
        val single = release(id = "single", releaseType = "Single", score = 100, date = "1986", trackCount = 2)

        // When
        val result = pick(listOf(single, album))

        // Then
        assertEquals(album, result)
    }

    // --- pressingDisambiguation: release-group disambiguation subtracted ---

    @Test
    fun `pressingDisambiguation subtracts an exact release-group disambiguation down to blank`() {
        // Given
        val blue = release(id = "a", disambiguation = "blue album", releaseGroupDisambiguation = "Blue Album")

        // When
        val result = MusicBrainzReleaseRanking.pressingDisambiguation(blue)

        // Then
        assertEquals("", result)
    }

    @Test
    fun `pressingDisambiguation leaves the pressing-specific remainder after subtracting`() {
        // Given
        val deluxe = release(id = "a", disambiguation = "Red Album, deluxe", releaseGroupDisambiguation = "Red Album")

        // When
        val result = MusicBrainzReleaseRanking.pressingDisambiguation(deluxe)

        // Then
        assertEquals("deluxe", result)
    }

    @Test
    fun `a bare-title request over the Weezer pool returns the 1994 Blue Album, not the undisambiguated 2019 Black Album`() {
        // Given - six distinct albums are titled "Weezer", and MB echoes the release group's own
        // identity down onto the release's disambiguation ("blue album"), which a blank-disambiguation
        // tier would otherwise misread as "this is the plain, undecorated pressing"
        val blueAlbum1994 = release(
            id = "blue", date = "1994-05-10", disambiguation = "blue album", releaseGroupDisambiguation = "Blue Album",
        )
        val blackAlbum2019 = release(
            id = "black", date = "2019-03-01", disambiguation = null, releaseGroupDisambiguation = "Black Album",
        )

        // When
        val result = pick(listOf(blackAlbum2019, blueAlbum1994))

        // Then
        assertEquals(blueAlbum1994, result)
    }

    // --- the edition band (tier 5) is symmetric ---

    @Test
    fun `a box set loses even though it ties on score and date`() {
        // Given - real case: "Abbey Road", a 92-track box set against 17-track pressings
        val normal = (1..3).map { release(id = "n$it", trackCount = 17, date = "1969") }
        val boxSet = release(id = "box", trackCount = 92, date = "1969")

        // When
        val result = pick(listOf(boxSet) + normal)

        // Then
        assertNotEquals(boxSet, result)
    }

    @Test
    fun `a partial pressing loses even though it ties on score and date`() {
        // Given - real case: "Kind of Blue", a 3-track pressing against the 5-track original
        val normal = (1..3).map { release(id = "n$it", trackCount = 5, date = "1959") }
        val partial = release(id = "partial", trackCount = 3, date = "1959")

        // When
        val result = pick(listOf(partial) + normal)

        // Then
        assertNotEquals(partial, result)
    }

    // --- modalTrackCount: the band centres on the mode, not the median ---

    @Test
    fun `modalTrackCount centres on the mode, not the median`() {
        // Given - real case: "A Love Supreme", counts [3x10, 4x9, 6, 13x5]. The median (4) would let
        // a 3-track pressing into a naive 0.7-of-median band; the mode (3) is the typical edition.
        val counts = List(10) { 3 } + List(9) { 4 } + listOf(6) + List(5) { 13 }
        val candidates = counts.mapIndexed { index, count -> release(id = "c$index", trackCount = count) }

        // When
        val result = MusicBrainzReleaseRanking.modalTrackCount(candidates)

        // Then
        assertEquals(3, result)
    }

    @Test
    fun `modalTrackCount ties break to the lower count`() {
        // Given
        val candidates = listOf(
            release(id = "a", trackCount = 8),
            release(id = "b", trackCount = 8),
            release(id = "c", trackCount = 20),
            release(id = "d", trackCount = 20),
        )

        // When
        val result = MusicBrainzReleaseRanking.modalTrackCount(candidates)

        // Then
        assertEquals(8, result)
    }

    @Test
    fun `modalTrackCount does not filter by score`() {
        // Given - a low-score candidate that would never survive pickBestRelease's own floor must
        // still contribute to the mode: modalTrackCount takes no minMatchScore parameter at all
        val candidates = listOf(
            release(id = "a", trackCount = 8, score = 5),
            release(id = "b", trackCount = 8, score = 5),
            release(id = "c", trackCount = 92, score = 100),
        )

        // When
        val result = MusicBrainzReleaseRanking.modalTrackCount(candidates)

        // Then
        assertEquals(8, result)
    }

    @Test
    fun `modalTrackCount is null when no candidate carries a track count`() {
        // Given
        val candidates = listOf(release(id = "a", trackCount = null), release(id = "b", trackCount = null))

        // When
        val result = MusicBrainzReleaseRanking.modalTrackCount(candidates)

        // Then
        assertNull(result)
    }

    // --- date (tier 6) outranks the pressing preference (tier 7) ---

    @Test
    fun `date outranks the pressing preference - Pet Sounds' 1966 'duophonic stereo' original beats an unlabelled 1990 reissue`() {
        // Given
        val original = release(id = "original", date = "1966-05-16", disambiguation = "duophonic stereo")
        val reissue = release(id = "reissue", date = "1990-09-17", disambiguation = null)

        // When
        val result = pick(listOf(reissue, original))

        // Then
        assertEquals(original, result)
    }

    @Test
    fun `date outranks the pressing preference - A Love Supreme's 1965 mono original beats an unlabelled 1986 reissue`() {
        // Given
        val original = release(id = "original", date = "1965", disambiguation = "mono")
        val reissue = release(id = "reissue", date = "1986", disambiguation = null)

        // When
        val result = pick(listOf(reissue, original))

        // Then
        assertEquals(original, result)
    }

    @Test
    fun `an undated release never wins by default against a dated one`() {
        // Given
        val undated = release(id = "undated", date = null)
        val dated = release(id = "dated", date = "1986")

        // When
        val result = pick(listOf(undated, dated))

        // Then
        assertEquals(dated, result)
    }

    // --- determinism ---

    @Test
    fun `the pick does not depend on input order`() {
        // Given
        val pool = ('a'..'f').map { release(id = it.toString(), date = "1986") }

        // When
        val forwards = pick(pool)
        val backwards = pick(pool.reversed())

        // Then
        assertEquals(forwards?.id, backwards?.id)
    }

    @Test
    fun `an all-else-equal pool resolves to the lowest id`() {
        // Given - reversed so the expected winner is not already at the head of the list: an
        // implementation that ignored the tie-break entirely would otherwise pass this
        val pool = ('a'..'f').map { release(id = it.toString(), date = "1986") }.reversed()

        // When
        val result = pick(pool)

        // Then
        assertEquals("a", result?.id)
    }

    // --- tiers pinned in isolation, so deleting any one of them turns exactly one test red ---

    @Test
    fun `a secondary-typed release loses to a plain album that is otherwise identical`() {
        // Given - isolates tier 2: same score, same status, same year, so only the secondary type differs
        val live = release(id = "a", secondaryTypes = listOf("Live"), date = "1984")
        val plain = release(id = "b", date = "1984")

        // When
        val result = pick(listOf(live, plain))

        // Then
        assertEquals(plain, result)
    }

    @Test
    fun `a non-Official release loses to an Official one that is otherwise identical`() {
        // Given - isolates tier 3: same score, same type, same year, so only the status differs.
        // Real case: Nirvana "Nevermind", where the previous behaviour returned a Promotion pressing.
        val promo = release(id = "a", status = "Promotion", date = "1991")
        val official = release(id = "b", date = "1991")

        // When
        val result = pick(listOf(promo, official))

        // Then
        assertEquals(official, result)
    }

    @Test
    fun `among same-year pressings the one with no pressing-specific disambiguation wins`() {
        // Given - isolates tier 7, which every other test resolves before reaching: both are the 2008
        // Red Album, so the ladder gets as far as the pressing text. The group's own name is
        // subtracted from both, leaving "deluxe" on one and nothing on the other.
        val deluxe = release(
            id = "a", date = "2008-06-16",
            disambiguation = "Red Album, deluxe", releaseGroupDisambiguation = "Red Album",
        )
        val plain = release(
            id = "b", date = "2008-06-24",
            disambiguation = "Red Album", releaseGroupDisambiguation = "Red Album",
        )

        // When
        val result = pick(listOf(deluxe, plain))

        // Then
        assertEquals(plain, result)
    }

    @Test
    fun `the edition band admits a pressing at its lower edge and rejects one just outside`() {
        // Given - pins the band width itself, not merely its direction: against a modal count of 10,
        // an 8-track pressing is inside (0.8x exactly) and a 7-track one is not
        val modal = (1..3).map { release(id = "modal$it", date = "2000", trackCount = 10) }
        val edge = release(id = "a-edge", date = "1990", trackCount = 8)
        val outside = release(id = "a-outside", date = "1990", trackCount = 7)

        // When
        val atEdge = pick(modal + edge)
        val justOutside = pick(modal + outside)

        // Then
        assertEquals(edge, atEdge)
        assertNotEquals(outside, justOutside)
    }

    // --- tier ORDER pinned: each of these fails if the two tiers it names are swapped ---

    @Test
    fun `an Album carrying a secondary type still outranks a Single that carries none`() {
        // Given - pins tier 1 above tier 2. Each tier alone prefers a different candidate, so this is
        // the only shape that distinguishes their order: a live album is still the album asked for.
        val single = release(id = "a", releaseType = "Single", date = "1984")
        val liveAlbum = release(id = "b", secondaryTypes = listOf("Live"), date = "1984")

        // When
        val result = pick(listOf(single, liveAlbum))

        // Then
        assertEquals(liveAlbum, result)
    }

    @Test
    fun `a plain Bootleg outranks an Official release carrying a secondary type`() {
        // Given - pins tier 2 above tier 3. A bootleg of the studio album is the record asked for; an
        // Official live recording of it is a different record.
        val officialLive = release(id = "a", secondaryTypes = listOf("Live"), date = "1984")
        val plainBootleg = release(id = "b", status = "Bootleg", date = "1984")

        // When
        val result = pick(listOf(officialLive, plainBootleg))

        // Then
        assertEquals(plainBootleg, result)
    }

    @Test
    fun `a higher-scoring box set outranks an in-band pressing that scores lower`() {
        // Given - pins tier 4 above tier 5, the one boundary every other band test leaves open by
        // tying score deliberately. Score is MusicBrainz's own judgement of whether this is the right
        // record at all; the band only distinguishes editions of a record already matched.
        val inBand = (1..3).map { release(id = "n$it", score = 97, trackCount = 10, date = "1969") }
        val boxSet = release(id = "box", score = 100, trackCount = 92, date = "1969")

        // When
        val result = pick(listOf(boxSet) + inBand)

        // Then
        assertEquals(boxSet, result)
    }

    // --- the band's treatment of a missing track count ---

    @Test
    fun `a release with no track count counts as in-band rather than sorting last`() {
        // Given - the opposite default to the date tier one rung down, and deliberate: MB omitting
        // `track-count` from a search hit usually means an under-entered ordinary pressing
        val modal = (1..3).map { release(id = "n$it", trackCount = 10, date = "2000") }
        val untracked = release(id = "a-untracked", trackCount = null, date = "1990")

        // When
        val result = pick(modal + untracked)

        // Then - it wins on its earlier date rather than losing tier 5 for having no count
        assertEquals(untracked, result)
    }

    // --- the score floor filters rather than ranks ---

    @Test
    fun `everything below minMatchScore yields null`() {
        // Given
        val pool = listOf(release(id = "a", score = 50), release(id = "b", score = 60))

        // When
        val result = pick(pool, minMatchScore = 80)

        // Then
        assertNull(result)
    }

    @Test
    fun `minMatchScore is honoured as passed, not hardcoded`() {
        // Given
        val pool = listOf(release(id = "a", score = 65))

        // When
        val belowFloor = pick(pool, minMatchScore = 70)
        val aboveFloor = pick(pool, minMatchScore = 60)

        // Then
        assertNull(belowFloor)
        assertEquals(pool[0], aboveFloor)
    }

    // --- fixture-backed regression: the real "Master Of Puppets" tied pool ---

    @Test
    fun `Master Of Puppets - the real 76-release tied pool resolves to the 1986 original, not an arbitrary tie member`() {
        // Given - the full top-25 window (of 76 total hits) from the live musicbrainz.org search
        // response for release:"Master Of Puppets" AND artist:"Metallica", cached 2026-08 under
        // `.scratch/musicbrainz-release-ranking/prototypes/fixtures/Metallica--Master_Of_Puppets.json`.
        // All 25 score 100. Trimmed to the fields the ranking ladder reads.
        val pool = masterOfPuppetsPool()

        // When
        val result = pick(pool)

        // Then - every 1986-dated Album ties on identity, score, band and blank pressing
        // disambiguation, so the id tiebreak resolves to the lexicographically-lowest of that group
        assertEquals("03e4ebe1-0a44-411c-8e19-78e0768603f8", result?.id)
    }

    @Test
    fun `Master Of Puppets (Remastered) - resolves to the 8-track remaster, not the 137-track box set`() {
        // Given - the qualified query returns 0 hits from MusicBrainz, so the request reaches this
        // pool via the stripped-qualifier fallback. Two releases evidence "remastered"; one is the album.
        val pool = masterOfPuppetsPool()

        // When
        val result = pick(pool, removedTags = remasteredTags())

        // Then
        assertEquals("4c659607-ad3d-46b1-a7d6-cb4a3530deb0", result?.id)
        assertEquals(8, result?.trackCount)
    }

    @Test
    fun `qualifier evidence outranks the earliest-date preference`() {
        // Given - a caller who asked for "(Remastered)" wants the remaster, not the 1986 original
        // that the bare-title ladder would hand them
        val original = release(id = "a", date = "1986", trackCount = 8)
        val remaster = release(id = "b", date = "2017", disambiguation = "remastered", trackCount = 8)

        // When
        val bareTitle = pick(listOf(original, remaster))
        val qualified = pick(listOf(original, remaster), removedTags = remasteredTags())

        // Then
        assertEquals(original, bareTitle)
        assertEquals(remaster, qualified)
    }

    @Test
    fun `qualifier evidence does not outrank the edition band`() {
        // Given - the regression this pins: a 137-track box set whose text says "remastered deluxe
        // version" must not beat a plain 8-track remaster just for containing the keyword
        val boxSet = release(id = "a", date = "2017", disambiguation = "remastered deluxe version", trackCount = 137)
        val remaster = release(id = "b", date = "2017", disambiguation = "remastered", trackCount = 8)
        val filler = (1..3).map { release(id = "f$it", date = "1986", trackCount = 8) }

        // When
        val result = pick(listOf(boxSet, remaster) + filler, removedTags = remasteredTags())

        // Then
        assertEquals(remaster, result)
    }

    @Test
    fun `a box set with stronger qualifier evidence still loses to an in-band pressing`() {
        // Given - pins the ORDER of the band and qualifier tiers, not merely their presence: the box
        // set states the exact requested year (the strongest tag tier) and the in-band remaster
        // states none. Rank the qualifier above the band and the 137-track box wins.
        val tags = MusicBrainzQualifierFallback.qualifierFallbackCandidates("Master Of Puppets (Remastered 2017)")
            .first { MusicBrainzQualifierFallback.normalize(it.title) == "master of puppets" }
            .removedTags
        val boxSet = release(id = "a", date = "2017", disambiguation = "remastered 2017 deluxe", trackCount = 137)
        val remaster = release(id = "b", date = "2017", disambiguation = "remastered", trackCount = 8)
        val filler = (1..3).map { release(id = "f$it", date = "1986", trackCount = 8) }

        // When
        val result = pick(listOf(boxSet, remaster) + filler, removedTags = tags)

        // Then
        assertEquals(remaster, result)
    }

    @Test
    fun `qualifier evidence does not outrank identity`() {
        // Given - tag text is free-form: a single or a bootleg can claim "remastered" as readily as an album
        val single = release(id = "a", releaseType = "Single", date = "2017", disambiguation = "remastered", trackCount = 2)
        val album = release(id = "b", date = "1986", trackCount = 8)

        // When
        val result = pick(listOf(single, album), removedTags = remasteredTags())

        // Then
        assertEquals(album, result)
    }

    private fun masterOfPuppetsPool() = listOf(
        release(id = "4c183eb6-1258-4a9f-b8e4-9f9e94187b6b", date = "1999-07-13", disambiguation = "DCC Compact Classics", trackCount = 8),
        release(id = "d67273c7-3691-394b-9c40-e684d813779e", date = "1986", trackCount = 8),
        release(id = "9ad9f401-9b5e-44d7-8b41-51ef37146f32", date = "1986", releaseType = "Single", trackCount = 2),
        release(id = "882029ac-17b2-3a96-af60-cedc7ca44aaf", date = "2003-11-06", trackCount = 8),
        release(id = "16259d14-2313-32cf-a8b3-289b86885632", date = "1986-06-01", trackCount = 8),
        release(id = "e6a77b3e-1e17-35d3-b7a9-e20d69716476", date = "1986", trackCount = 8),
        release(id = "f1b43f9a-fd3c-3164-b777-74007cef6e71", date = "1989", trackCount = 8),
        release(id = "d2b38008-536d-3daf-b3df-90b985d01789", date = "1989", trackCount = 8),
        release(id = "1f87ac3f-ed28-3dfa-b54d-c778a8711931", date = "1988-09-30", trackCount = 8),
        release(id = "c1f20c2b-07f5-4d06-8514-c14438ae3885", date = "2013", trackCount = 8),
        release(id = "517e4216-50a4-4177-9f45-7056d9c40548", date = "2010-09-22", trackCount = 8),
        release(id = "a00df802-1f29-41c9-9be2-b05447b715e6", date = "2017-11-10", disambiguation = "remastered deluxe version", trackCount = 137),
        release(id = "72547390-68bf-4870-938d-70c5740c3ec7", date = "1988", disambiguation = "CD MFN 60 MPO 01 @ with sticker", trackCount = 8),
        release(id = "4c659607-ad3d-46b1-a7d6-cb4a3530deb0", date = "2017-11-10", disambiguation = "Metallica store / Remastered", trackCount = 8),
        release(id = "d7f74ffe-b91d-42c9-a756-535b9ededb24", date = "2009-06-24", trackCount = 8),
        release(id = "3e19a8f5-fb33-4c95-936e-5451a2550a1f", date = null, trackCount = 8),
        release(id = "197349f8-4c19-437e-9bf2-26693ed5a4d4", date = "1987", trackCount = 8),
        release(id = "36028768-6128-4f7c-87ff-648bafa7b8ac", date = null, disambiguation = "CDMFN 60 ::: ·MASTERED· ·BY NIMBUS·", trackCount = 8),
        release(id = "dcbada7f-2adc-4674-99be-8593066e4023", date = "1986-05-21", trackCount = 8),
        release(id = "f4d4ba9a-bb17-49fd-91de-360c3b8f9a78", date = "2007", trackCount = 8),
        release(id = "47524994-e7d6-49d1-9dc8-834793148859", date = "1999", trackCount = 8),
        release(id = "03e4ebe1-0a44-411c-8e19-78e0768603f8", date = "1986-03", trackCount = 8),
        release(id = "9caac2e1-faeb-4fa4-8e38-bb2ad9a1b184", date = "2017-11-10", trackCount = 8),
        release(id = "435eadc6-6e02-3ba4-ab49-7ed9cc00b420", date = "1989", trackCount = 8),
        release(id = "31de97e3-6c53-4ca6-a00d-152642eb7e4a", date = "1986-06", trackCount = 8),
    )
}
