package com.landofoz.musicmeta.engine

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [contradictsSuppliedName] scored against live MusicBrainz captures, because its deciding metric is
 * a rate rather than a case: **a false positive rejects a correct identifier**, which is worse than
 * missing a contradiction. A unit test over three hand-written names cannot see that rate.
 *
 * See `corpora/artist-name-contradiction/provenance.md` for what was captured and what was not.
 */
class ArtistNameContradictionCorpusTest {

    private fun corpus(name: String): List<Row> {
        val text = checkNotNull(javaClass.getResourceAsStream("/corpora/artist-name-contradiction/$name"))
            .bufferedReader().readText()
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val aliases = o.optJSONArray("aliases") ?: JSONArray()
            Row(
                supplied = o.getString("supplied"),
                canonical = o.optString("name", ""),
                aliases = (0 until aliases.length()).map { AlternativeName(aliases.getString(it), official = true) },
            )
        }.filter { it.canonical.isNotEmpty() }
    }

    private fun falsePositives(rows: List<Row>): List<String> =
        rows.filter { contradictsSuppliedName(it.supplied, it.canonical, it.aliases) }
            .map { "${it.supplied} vs ${it.canonical}" }

    @Test
    fun `no correct name and identifier pair is ever called contradictory`() {
        // Given - 99 artists whose name and MBID both come from Last.fm, so every pair is right
        val rows = corpus("names_correct.json")
        assertTrue("corpus did not load", rows.size > 90)

        // When - each is put to the contradiction test
        val wrong = falsePositives(rows)

        // Then - none of them contradicts. A rejection here would destroy a working request.
        assertEquals("false positives: $wrong", emptyList<String>(), wrong)
    }

    @Test
    fun `a name in another script or spelling is not evidence of a different artist`() {
        // Given - cross-script, diacritic, stylised and ampersand spellings a caller might type
        val rows = corpus("names_script.json")
        assertTrue("corpus did not load", rows.size >= 7)

        // When - each is put to the same test
        val wrong = falsePositives(rows)

        // Then - none contradicts: MusicBrainz's own aliases relate them, and where they do not,
        // the rule refuses to judge across scripts rather than guessing
        assertEquals("false positives: $wrong", emptyList<String>(), wrong)
    }

    @Test
    fun `a name paired with another artist's identifier is caught`() {
        // Given - each artist's name against the next artist's MBID, which is the wrong-but-live
        // pairing that returns one artist's data under another's name
        val rows = corpus("names_correct.json")
        val shuffled = rows.indices.map { Row(rows[it].supplied, rows[(it + 1) % rows.size].canonical, rows[(it + 1) % rows.size].aliases) }

        // When - each mismatched pair is put to the test
        val caught = shuffled.count { contradictsSuppliedName(it.supplied, it.canonical, it.aliases) }

        // Then - nearly all are caught: 98 of 99 when this corpus was captured. That is a floor on
        // a synthetic pairing, not a field detection rate — how often a real caller supplies a
        // wrong-but-live identifier is a property of their data, not of MusicBrainz. The one missed
        // shares a token ("Fleetwood Mac" against "Mac DeMarco"), which is the rule declining to
        // convict on ambiguous evidence rather than failing to work.
        assertTrue("caught only $caught of ${shuffled.size}", caught >= shuffled.size - 3)
    }

    private data class Row(val supplied: String, val canonical: String, val aliases: List<AlternativeName>)
}
