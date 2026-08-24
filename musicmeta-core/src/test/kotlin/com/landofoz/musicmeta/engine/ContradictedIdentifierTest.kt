package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
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

    @Test
    fun `every artist type answered from the identifier reports the contradiction`() = runTest {
        // Given - the same wrong identifier, and the three types that reach the artist entity by a
        // different route than GENRE does
        val types = setOf(
            EnrichmentType.BAND_MEMBERS,
            EnrichmentType.ARTIST_LINKS,
            EnrichmentType.ARTIST_POPULARITY,
        )
        val request = EnrichmentRequest.forArtist("Radiohead", mbid = "art-coldplay")

        // When - each is enriched on its own, so no other type's guard can stand in for its own
        val statuses = types.map { it to engine(http()).enrich(request, setOf(it)).identity.status }

        // Then - each reports it. These three go through lookedUpOrNameResolvedArtist rather than
        // enrichArtist's own lookup, so guarding one route says nothing about the other.
        assertEquals(types.map { it to CanonicalStatus.CONTRADICTED }, statuses)
    }

    @Test
    fun `a contradicted band-members request answers with the caller's own band`() = runTest {
        // Given - the wrong identifier, whose artist has members of its own to hand back
        val request = EnrichmentRequest.forArtist("Radiohead", mbid = "art-coldplay")

        // When - band members are enriched
        val results = engine(http()).enrich(request, setOf(EnrichmentType.BAND_MEMBERS))

        // Then - the name's band, never the identifier's. Without this the identifier's own members
        // would come back looking like a clean answer.
        val success = results.raw[EnrichmentType.BAND_MEMBERS] as EnrichmentResult.Success
        val members = (success.data as EnrichmentData.BandMembers).members.map { it.name }
        assertEquals(listOf("Thom Yorke"), members)
    }

    @Test
    fun `a discography browsed under another artist's identifier is reported, not handed back`() = runTest {
        // Given - the wrong identifier, and a browse response waiting under each artist
        val http = http().apply {
            givenJsonResponse("release-group?artist=art-coldplay", COLDPLAY_DISCOGRAPHY)
            givenJsonResponse("release-group?artist=art-radiohead", RADIOHEAD_DISCOGRAPHY)
        }
        val request = EnrichmentRequest.forArtist("Radiohead", mbid = "art-coldplay")

        // When - the discography is enriched
        val results = engine(http).enrich(request, setOf(EnrichmentType.ARTIST_DISCOGRAPHY))

        // Then - the caller's own discography, and the identifier reported wrong. A browse learns
        // nothing about who it browsed, so without the lookup this hands back the other artist's
        // entire catalogue as the caller's.
        assertEquals(CanonicalStatus.CONTRADICTED, results.identity.status)
        val success = results.raw[EnrichmentType.ARTIST_DISCOGRAPHY] as EnrichmentResult.Success
        val albums = (success.data as EnrichmentData.Discography).albums.map { it.title }
        assertEquals(listOf("OK Computer"), albums)
    }

    private companion object {
        /**
         * Deliberately able to answer every artist type on its own: a fixture that could only
         * answer `NotFound` would let a test pass with no guard in place at all.
         */
        private fun artist(id: String, name: String, genre: String, member: String, site: String) = """
            {"id":"$id","name":"$name","type":"Group",
             "tags":[{"name":"$genre","count":9}],
             "rating":{"value":4.5,"votes-count":12},
             "relations":[
               {"type":"member of band","direction":"backward","attributes":["vocals"],
                "artist":{"id":"m-$id","name":"$member"}},
               {"type":"official homepage","target-type":"url","url":{"resource":"$site"}}],
             ALIASES}
        """.trimIndent()

        val COLDPLAY = artist("art-coldplay", "Coldplay", "britpop", "Chris Martin", "https://coldplay.com")
            .replace("ALIASES", """"aliases":[{"name":"Coolplay","type":"Search hint","primary":false}]""")

        val RADIOHEAD = artist("art-radiohead", "Radiohead", "alternative rock", "Thom Yorke", "https://radiohead.com")
            .replace("ALIASES", """"aliases":[]""")

        val COLDPLAY_DISCOGRAPHY = """
            {"release-groups":[{"id":"rg-parachutes","title":"Parachutes","primary-type":"Album",
              "first-release-date":"2000-07-10"}]}
        """.trimIndent()

        val RADIOHEAD_DISCOGRAPHY = """
            {"release-groups":[{"id":"rg-ok","title":"OK Computer","primary-type":"Album",
              "first-release-date":"1997-05-21"}]}
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
