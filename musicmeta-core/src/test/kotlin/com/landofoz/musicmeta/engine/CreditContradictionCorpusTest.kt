package com.landofoz.musicmeta.engine

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The album and track guards compare a caller's artist against a release or recording's
 * **artist-credit**, which is a bare string: unlike an artist entity lookup it carries no aliases, so
 * the rule runs on weaker evidence there than the artist path measured it on. This scores that
 * weaker surface separately rather than letting it inherit a number it did not earn.
 *
 * A credit is not an artist name. MusicBrainz credits real releases as `Madison Acid and TV Girl`
 * and `X feat. Y`, and a caller holding only `TV Girl` must not have their request destroyed for it.
 *
 * See `corpora/artist-name-contradiction/provenance.md`.
 */
class CreditContradictionCorpusTest {

    private fun corpus(): List<Pair<String, String>> {
        val text = checkNotNull(javaClass.getResourceAsStream("/corpora/artist-name-contradiction/credits.json"))
            .bufferedReader().readText()
        val array = JSONArray(text)
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            o.getString("supplied") to o.optString("credit", "")
        }.filter { it.second.isNotEmpty() }
    }

    @Test
    fun `no real release credit is ever read as a different artist`() {
        // Given - each chart artist beside the credit MusicBrainz prints on a real release of theirs
        val rows = corpus()
        assertTrue("corpus did not load", rows.size > 50)

        // When - each is put to the contradiction test the album and track guards use, with no
        // aliases, exactly as a release or recording lookup supplies it
        val wrong = rows.filter { (supplied, credit) ->
            contradictsSuppliedName(supplied, credit, aliases = emptyList())
        }

        // Then - none contradicts. One of these would mean the credit surface needs its own
        // evidence model rather than the artist rule, since a false positive here rejects a
        // correct identifier on a request that was working.
        assertEquals("false positives: ${wrong.map { "${it.first} vs ${it.second}" }}", emptyList<Pair<String, String>>(), wrong)
    }

    @Test
    fun `a credit naming an entirely different artist is still caught`() {
        // Given - each artist against the next artist's release credit
        val rows = corpus()
        val shuffled = rows.indices.map { rows[it].first to rows[(it + 1) % rows.size].second }

        // When - each mismatched pair is tested
        val caught = shuffled.count { contradictsSuppliedName(it.first, it.second, emptyList()) }

        // Then - the weaker surface still catches nearly all of them: 95 of 96 when this corpus
        // was captured, against the artist surface's 98 of 99 with aliases to help it. A floor on
        // a synthetic pairing, not a field rate: see ArtistNameContradictionCorpusTest for why.
        assertTrue("caught only $caught of ${shuffled.size}", caught >= shuffled.size - 4)
    }
}
