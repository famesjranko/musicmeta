package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
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
 * An album or track request carries an artist too, and a release or recording lookup returns an
 * artist-credit — so the same contradiction test the artist path uses reaches both, without any
 * title comparison. Titles are deliberately not compared: a remaster, an edition or a localised
 * title differs from what a caller typed while still being the album they meant.
 */
class ContradictedAlbumTrackIdentifierTest {

    private fun engine(http: FakeHttpClient) = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(MusicBrainzProvider(http, RateLimiter(0)))),
        FakeEnrichmentCache(),
        EnrichmentConfig(),
        mergers = emptyList(),
    )

    private fun http() = FakeHttpClient().apply {
        givenJsonResponse("release/rel-parachutes", PARACHUTES)
        givenJsonResponse("release/rel-ok", OK_COMPUTER)
        givenJsonResponse("recording/rec-yellow", YELLOW)
        givenJsonResponse("release?query", RELEASE_SEARCH)
        givenJsonResponse("recording?query", RECORDING_SEARCH)
    }

    private fun genresOf(result: EnrichmentResult?): List<String> =
        ((result as EnrichmentResult.Success).data as EnrichmentData.Metadata).genres.orEmpty()

    private fun trackTitlesOf(result: EnrichmentResult?): List<String> =
        ((result as EnrichmentResult.Success).data as EnrichmentData.Tracklist).tracks.map { it.title }

    @Test
    fun `an album identifier for another artist's release is reported, and the name still answers`() = runTest {
        // Given - the caller's own album and artist beside a healthy MBID for a different artist
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead", mbid = "rel-parachutes")

        // When - a type MusicBrainz answers from the release is enriched
        val results = engine(http()).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - the identifier is reported wrong and the requested album came back regardless
        assertEquals(CanonicalStatus.CONTRADICTED, results.identity.status)
        assertEquals(listOf("alternative rock"), genresOf(results.raw[EnrichmentType.GENRE]))
    }

    @Test
    fun `an album tracklist under another artist's identifier is reported, and the name still answers`() = runTest {
        // Given - the same wrong identifier, and a type that looks the release up on its own path
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead", mbid = "rel-parachutes")

        // When - the tracklist is enriched
        val results = engine(http()).enrich(request, setOf(EnrichmentType.ALBUM_TRACKS))

        // Then - the contradiction is reported and the caller's own tracklist answered, not the
        // identifier's. ALBUM_TRACKS reaches its release through enrichAlbumTracks rather than
        // enrichAlbumByMbid, so guarding one says nothing about the other.
        assertEquals(CanonicalStatus.CONTRADICTED, results.identity.status)
        assertEquals(listOf("Airbag"), trackTitlesOf(results.raw[EnrichmentType.ALBUM_TRACKS]))
    }

    @Test
    fun `a track identifier for another artist's recording is reported, and the name still answers`() = runTest {
        // Given - the same shape one level down
        val request = EnrichmentRequest.forTrack("Creep", "Radiohead", mbid = "rec-yellow")

        // When - the same type is enriched
        val results = engine(http()).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - same outcome: the bad identifier is visible and the answer is the caller's track
        assertEquals(CanonicalStatus.CONTRADICTED, results.identity.status)
        assertEquals(listOf("alternative rock"), genresOf(results.raw[EnrichmentType.GENRE]))
    }

    @Test
    fun `an album identifier for the artist the caller asked for is used`() = runTest {
        // Given - the right release MBID this time
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead", mbid = "rel-ok")

        // When - the same type is enriched
        val results = engine(http()).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - the identifier is honoured at its own provenance and nothing is reported wrong
        val success = results.raw[EnrichmentType.GENRE] as EnrichmentResult.Success
        assertEquals(LookupProvenance.CANONICAL_ID, success.provenance)
        assertTrue(results.identity.status != CanonicalStatus.CONTRADICTED)
    }

    @Test
    fun `a different album by the same artist is deliberately not caught`() = runTest {
        // Given - the caller's title beside another release *by that same artist*. The guard tests
        // the artist and never the title, so this is outside what it can see.
        val http = http()
        http.givenJsonResponse("release/rel-kid-a", KID_A)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead", mbid = "rel-kid-a")

        // When - the same type is enriched
        val results = engine(http).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - no contradiction, and the identifier's own album answers. Pinned so the limit is
        // a stated boundary rather than an untested assumption: closing it needs title evidence
        // (edition, year, track count), which is a separate question from this one.
        assertTrue(results.identity.status != CanonicalStatus.CONTRADICTED)
        assertEquals(listOf("art rock"), genresOf(results.raw[EnrichmentType.GENRE]))
    }

    @Test
    fun `a release credited to nobody is not evidence of a different artist`() = runTest {
        // Given - a release MusicBrainz prints no artist-credit on
        val http = http()
        http.givenJsonResponse("release/rel-uncredited", UNCREDITED)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead", mbid = "rel-uncredited")

        // When - the same type is enriched
        val results = engine(http).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - a missing credit is not a differing credit; only positive disagreement convicts
        assertTrue(results.identity.status != CanonicalStatus.CONTRADICTED)
    }

    @Test
    fun `a combined credit containing the caller's artist is not a contradiction`() = runTest {
        // Given - MusicBrainz's real habit of crediting a release to more than one act, which is
        // how "TV Girl" appears as "Madison Acid and TV Girl" on a release of their own
        val http = http()
        http.givenJsonResponse("release/rel-collab", COLLAB)
        val request = EnrichmentRequest.forAlbum("Split", "TV Girl", mbid = "rel-collab")

        // When - the same type is enriched
        val results = engine(http).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - the caller is named inside the credit, so nothing is contradicted
        assertTrue(results.identity.status != CanonicalStatus.CONTRADICTED)
        assertEquals(listOf("indie pop"), genresOf(results.raw[EnrichmentType.GENRE]))
    }

    @Test
    fun `credits under another artist's recording identifier are reported, not handed back`() = runTest {
        // Given - a caller's own track beside a healthy recording MBID for a different artist, and
        // a release-group id beside it. That second id is what makes this reachable: with only a
        // recording id, needsIdentityResolution runs the resolver, whose own guard fires first.
        val request = EnrichmentRequest.forTrack("Creep", "Radiohead", mbid = "rec-yellow")
            .withIdentifiers(
                EnrichmentIdentifiers(musicBrainzId = "rec-yellow", musicBrainzReleaseGroupId = "rg-parachutes"),
            )

        // When - CREDITS is enriched, the one type with no name route to fall back to
        val results = engine(http()).enrich(request, setOf(EnrichmentType.CREDITS))

        // Then - the contradiction is reported and nothing is answered. CREDITS reaches the
        // recording through enrichTrackCredits, not enrichTrackByMbid, so it went unguarded; the
        // producer on that recording would otherwise be handed back as the caller's own.
        assertEquals(CanonicalStatus.CONTRADICTED, results.identity.status)
        assertTrue(results.raw[EnrichmentType.CREDITS] is EnrichmentResult.NotFound)
    }

    private companion object {
        private fun release(id: String, title: String, credit: String?, genre: String, track: String = title) = """
            {"id":"$id","title":"$title","date":"2000-01-01","country":"GB",
             ${credit?.let { """"artist-credit":[{"artist":{"id":"a-$id","name":"$it"}}],""" }.orEmpty()}
             "media":[{"format":"CD","tracks":[
               {"title":"$track","position":1,"length":200000,"recording":{"id":"rec-$id"}}]}],
             "release-group":{"id":"rg-$id","primary-type":"Album",
               "tags":[{"name":"$genre","count":9}]}}
        """.trimIndent()

        val PARACHUTES = release("rel-parachutes", "Parachutes", "Coldplay", "britpop", track = "Yellow")
        val OK_COMPUTER = release("rel-ok", "OK Computer", "Radiohead", "alternative rock", track = "Airbag")
        val KID_A = release("rel-kid-a", "Kid A", "Radiohead", "art rock")
        val UNCREDITED = release("rel-uncredited", "Untitled", null, "ambient")
        val COLLAB = release("rel-collab", "Split", "Madison Acid and TV Girl", "indie pop")

        // Carries credits of its own on purpose: a recording nobody is credited on would let the
        // CREDITS test below pass with no guard in place at all.
        val YELLOW = """
            {"id":"rec-yellow","title":"Yellow","length":266000,
             "tags":[{"name":"britpop","count":9}],
             "artist-credit":[{"artist":{"id":"art-coldplay","name":"Coldplay"}}],
             "relations":[{"target-type":"artist","type":"producer","attributes":[],
               "artist":{"id":"art-ken","name":"Ken Nelson"}}],
             "releases":[{"id":"rel-parachutes","status":"Official",
               "release-group":{"id":"rg-parachutes","title":"Parachutes","primary-type":"Album"}}]}
        """.trimIndent()

        val RELEASE_SEARCH = """
            {"releases":[{"id":"rel-ok","score":100,"title":"OK Computer","date":"1997-06-16",
              "artist-credit":[{"artist":{"id":"art-radiohead","name":"Radiohead"}}],
              "release-group":{"id":"rg-ok","primary-type":"Album",
                "tags":[{"name":"alternative rock","count":9}]}}]}
        """.trimIndent()

        val RECORDING_SEARCH = """
            {"recordings":[{"id":"rec-creep","score":100,"title":"Creep",
              "tags":[{"name":"alternative rock","count":9}],
              "artist-credit":[{"artist":{"id":"art-radiohead","name":"Radiohead"}}],
              "releases":[{"id":"rel-pablo","status":"Official",
                "release-group":{"id":"rg-pablo","title":"Pablo Honey","primary-type":"Album"}}]}]}
        """.trimIndent()
    }
}
