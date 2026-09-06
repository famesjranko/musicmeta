package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.SimilarArtist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which merged similar artists are worth a MusicBrainz request, and how its answer is applied.
 *
 * Both halves have a wrong answer that is worse than no answer, so both are pinned here rather than
 * only through the engine: asking about the whole list is the cost defect, and joining an answer on
 * anything but the entry's own id is the correctness defect the whole feature exists to avoid.
 */
class SimilarArtistDisambiguationTest {

    private fun artist(
        name: String,
        mbid: String? = null,
        disambiguation: String? = null,
    ) = SimilarArtist(
        name = name,
        identifiers = EnrichmentIdentifiers(musicBrainzId = mbid),
        matchScore = 0.5f,
        sources = listOf("lastfm"),
        disambiguation = disambiguation,
    )

    // --- which entries are worth asking about ---

    @Test
    fun `only an undescribed entry of a same-name pair is asked about`() {
        // Given - a split pair with one side described, plus a uniquely named entry with no text
        val artists = listOf(
            artist("Loathe", LOATHE_UK, "UK experimental metal"),
            artist("Loathe", LOATHE_MT),
            artist("Deftones", DEFTONES),
        )

        // When - the ids a batched lookup would name are computed
        val ids = SimilarArtistDisambiguation.undescribedSplitPairMbids(artists)

        // Then - only the pair's blank side. The uniquely named entry is already told apart by its
        // name, and asking about it is what turns one request per call into a hundred-row query
        assertEquals(listOf(LOATHE_MT), ids)
    }

    @Test
    fun `an entry with no id contributes nothing, and its identified twin is still asked about`() {
        // Given - a same-name pair, one side carrying no MBID at all
        val artists = listOf(artist("Bad Omens", BAD_OMENS_MN), artist("Bad Omens"))

        // When - the ids are computed
        val ids = SimilarArtistDisambiguation.undescribedSplitPairMbids(artists)

        // Then - only the identified side. There is nothing to look an idless entry up by, and the
        // consumer still cannot read the pair, so describing the half that can be described helps
        assertEquals(listOf(BAD_OMENS_MN), ids)
    }

    @Test
    fun `a pair both of whose sides are already described is never asked about`() {
        // Given - a same-name pair a contributor described on both sides
        val artists = listOf(
            artist("Spiritbox", SPIRITBOX_CA, "Canadian metalcore"),
            artist("Spiritbox", SPIRITBOX_NL, "Dutch post-rock"),
        )

        // When - the ids are computed
        val ids = SimilarArtistDisambiguation.undescribedSplitPairMbids(artists)

        // Then - nothing is asked: the free half of this feature already answered it
        assertEquals(emptyList<String>(), ids)
    }

    @Test
    fun `the batch is capped rather than paged`() {
        // Given - more undescribed same-name entries than one query may carry
        val artists = (1..40).flatMap { index ->
            listOf(artist("Act $index", "%08d-0000-0000-0000-000000000001".format(index)),
                artist("Act $index", "%08d-0000-0000-0000-000000000002".format(index)))
        }

        // When - the ids are computed
        val ids = SimilarArtistDisambiguation.undescribedSplitPairMbids(artists)

        // Then - the cap holds, so a labelling change can never cost a second round trip
        assertEquals(SimilarArtistDisambiguation.BATCH_LIMIT, ids.size)
    }

    @Test
    fun `the cap counts ids the query can actually ask about`() {
        // Given - a list whose same-name entries carry 25 ids that are not MusicBrainz ids at all,
        // ahead of one that is. Ids arrive on other providers' answers, so this is their shape to
        // decide, not ours
        val junk = (1..25).flatMap { index ->
            listOf(artist("Act $index", "not-an-mbid-$index"), artist("Act $index", "also-not-$index"))
        }
        val real = listOf(artist("Loathe", LOATHE_UK), artist("Loathe", LOATHE_MT))

        // When - the ids a batched lookup would name are computed
        val ids = SimilarArtistDisambiguation.undescribedSplitPairMbids(junk + real)

        // Then - the real ids are asked about. Capping before the shape check would spend all 25
        // places on ids `MusicBrainzApi` then drops, and ask MusicBrainz about nothing
        assertEquals(listOf(LOATHE_UK, LOATHE_MT), ids)
    }

    // --- how an answer is applied ---

    @Test
    fun `each entry of a pair takes the text filed under its own id`() {
        // Given - the two acts named Loathe, and MusicBrainz's text for each, keyed by id
        val artists = listOf(artist("Loathe", LOATHE_UK), artist("Loathe", LOATHE_MT))
        val texts = mapOf(
            LOATHE_UK to "UK experimental metal",
            LOATHE_MT to "Maltese death metal band",
        )

        // When - the answer is applied
        val described = SimilarArtistDisambiguation.describedWith(artists, texts)

        // Then - each entry wears its own act's description. Joining on the name would hand both of
        // them the same string, which is the wrong answer this feature must never produce
        assertEquals("UK experimental metal", described[0].disambiguation)
        assertEquals("Maltese death metal band", described[1].disambiguation)
    }

    @Test
    fun `an id the answer does not carry stays unknown`() {
        // Given - two asked-about entries and an answer naming only one of them, as a retired id arrives
        val artists = listOf(artist("Loathe", LOATHE_UK), artist("Loathe", LOATHE_MT))
        val texts = mapOf(LOATHE_UK to "UK experimental metal")

        // When - the answer is applied
        val described = SimilarArtistDisambiguation.describedWith(artists, texts)

        // Then - the unanswered entry is blank, never filled from the other row of the same response
        assertEquals("UK experimental metal", described[0].disambiguation)
        assertNull(described[1].disambiguation)
    }

    @Test
    fun `applying an answer changes nothing else about an entry`() {
        // Given - an entry with a score and a source, and a text for it
        val artists = listOf(artist("Loathe", LOATHE_UK))

        // When - the answer is applied
        val described = SimilarArtistDisambiguation.describedWith(artists, mapOf(LOATHE_UK to "UK experimental metal"))

        // Then - sources still names who recommended the artist, and the rank is untouched
        assertEquals(listOf("lastfm"), described.single().sources)
        assertEquals(0.5f, described.single().matchScore, 0.0001f)
    }

    @Test
    fun `an already described entry is left alone`() {
        // Given - an entry a contributor already described, and a different text under its id
        val artists = listOf(artist("Loathe", LOATHE_UK, "UK experimental metal"))

        // When - the answer is applied
        val described = SimilarArtistDisambiguation.describedWith(artists, mapOf(LOATHE_UK to "something else"))

        // Then - the contributor's own text wins: the lookup exists to fill blanks, not to overrule
        assertEquals("UK experimental metal", described.single().disambiguation)
    }

    private companion object {
        const val LOATHE_UK = "56eb02c4-1f16-4613-8bb3-b4a752283fc3"
        const val LOATHE_MT = "e9ea0fbc-ccc7-4e98-9290-0a41aa848fa2"
        const val SPIRITBOX_CA = "9c935736-7530-41e4-b776-1dbcf534c061"
        const val SPIRITBOX_NL = "a39ad456-a697-4f32-aa36-c107f654d318"
        const val BAD_OMENS_MN = "8834d8b5-72a4-4a6e-9d35-3a041b8579fa"
        const val DEFTONES = "7527f6c2-d762-4b88-b5e2-9cc83f8a6a0d"
    }
}
