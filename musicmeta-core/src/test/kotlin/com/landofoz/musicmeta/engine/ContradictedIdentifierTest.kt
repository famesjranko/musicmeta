package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A caller-supplied identifier that resolves is not thereby the caller's entity. When it names a
 * confidently different one, the request still has a name to fall back on — so the answer can be
 * both complete and a report that the identifier was wrong.
 */
class ContradictedIdentifierTest {

    private fun engine(http: FakeHttpClient) = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(MusicBrainzProvider(http, RateLimiter(0)))),
        FakeEnrichmentCache(),
        EnrichmentConfig(),
        mergers = emptyList(),
    )

    private fun http() = FakeHttpClient().apply {
        givenJsonResponse("artist/art-coldplay", COLDPLAY)
        givenJsonResponse("artist/art-radiohead", RADIOHEAD)
        givenJsonResponse("artist?query", SEARCH)
    }

    @Test
    fun `an identifier naming a different artist is reported, and the name still answers`() = runTest {
        // Given - the caller's own artist name beside another artist's healthy MBID, which is what
        // a stale scrobble export or a mis-keyed identifier looks like
        val request = EnrichmentRequest.forArtist("Radiohead", mbid = "art-coldplay")

        // When - a type MusicBrainz answers from the artist entity is enriched
        val results = engine(http()).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - the identifier is reported wrong, and the requested artist came back anyway. This
        // exact pair is legitimate and a future reader must not "fix" it: CONTRADICTED describes
        // the supplied evidence, EXACT_NAME describes where the returned data came from.
        assertEquals(CanonicalStatus.CONTRADICTED, results.identity.status)
        val success = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals(LookupProvenance.EXACT_NAME, success.provenance)
        assertEquals(listOf("alternative rock"), (success.data as com.landofoz.musicmeta.EnrichmentData.Metadata).genres)
    }

    @Test
    fun `a successful fallback never clears the contradiction`() = runTest {
        // Given - the same contradicted request, enriched for a type the fallback fully answers
        val request = EnrichmentRequest.forArtist("Radiohead", mbid = "art-coldplay")

        // When - the call completes with every requested type served
        val results = engine(http()).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - a healthy-looking result set does not restore the identifier's good name
        assertTrue(results.raw.values.all { it is EnrichmentResult.Success })
        assertEquals(CanonicalStatus.CONTRADICTED, results.identity.status)
    }

    @Test
    fun `an identifier naming the artist the caller asked for is not contradicted`() = runTest {
        // Given - the same shape of request, with the right MBID this time
        val request = EnrichmentRequest.forArtist("Radiohead", mbid = "art-radiohead")

        // When - the same type is enriched
        val results = engine(http()).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - the identifier is used, at its own provenance, and nothing is reported wrong
        val success = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals(LookupProvenance.CANONICAL_ID, success.provenance)
        assertTrue(results.identity.status != CanonicalStatus.CONTRADICTED)
    }

    @Test
    fun `a name written in another script is not treated as a different artist`() = runTest {
        // Given - a caller who typed the romanised name of an artist MusicBrainz files in Japanese,
        // whose aliases upstream are not consulted for this row at all
        val http = FakeHttpClient().apply { givenJsonResponse("artist/art-jp", JAPANESE_NO_ALIASES) }
        val request = EnrichmentRequest.forArtist("Tokyo Jihen", mbid = "art-jp")

        // When - the same type is enriched
        val results = engine(http).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - no contradiction: a comparison that cannot represent equality cannot prove
        // inequality, so the identifier is used rather than accused
        assertTrue(results.identity.status != CanonicalStatus.CONTRADICTED)
        val success = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals(LookupProvenance.CANONICAL_ID, success.provenance)
    }

    private companion object {
        val COLDPLAY = """
            {"id":"art-coldplay","name":"Coldplay","type":"Group",
             "tags":[{"name":"britpop","count":9}],
             "aliases":[{"name":"Coolplay","type":"Search hint","primary":false}]}
        """.trimIndent()

        val RADIOHEAD = """
            {"id":"art-radiohead","name":"Radiohead","type":"Group",
             "tags":[{"name":"alternative rock","count":9}]}
        """.trimIndent()

        val SEARCH = """
            {"artists":[{"id":"art-radiohead","name":"Radiohead","score":100,
              "tags":[{"name":"alternative rock","count":9}]}]}
        """.trimIndent()

        val JAPANESE_NO_ALIASES = """
            {"id":"art-jp","name":"東京事変","type":"Group",
             "tags":[{"name":"j-rock","count":5}]}
        """.trimIndent()
    }
}
