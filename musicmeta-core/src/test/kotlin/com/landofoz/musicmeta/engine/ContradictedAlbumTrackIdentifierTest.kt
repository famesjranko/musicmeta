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
import com.landofoz.musicmeta.testkit.UpstreamPools
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
    fun `the contradicted identifier is still on the resolution's identifiers`() = runTest {
        // Given - the same wrong identifier, on a request whose name recovers the album by search
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead", mbid = "rel-parachutes")

        // When - a type MusicBrainz answers from the release is enriched
        val results = engine(http()).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - the id the status just disowned is still the one on identifiers, because the name
        // route resolved nothing to replace it with. A caller reading identifiers alone would carry
        // the bad id onward, which is what the status is there to stop.
        assertEquals(CanonicalStatus.CONTRADICTED, results.identity.status)
        assertEquals("rel-parachutes", results.identity.identifiers.musicBrainzId)
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
    fun `a different album by the same artist, with no evidence beside it, is not caught`() = runTest {
        // Given - the caller's title beside another release *by that same artist*. The guard tests
        // the artist and never the title, so this is outside what it can see.
        val http = http()
        http.givenJsonResponse("release/rel-kid-a", KID_A)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead", mbid = "rel-kid-a")

        // When - the same type is enriched
        val results = engine(http).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - no contradiction, and the identifier's own album answers. The year check closes
        // part of this gap, but only for a caller who supplied a year and got it wrong in the one
        // direction that proves something; a request carrying no evidence beside the identifier is
        // still outside what anything here can see, and this is what pins that.
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

    @Test
    fun `an album the caller dates before its own first release is reported`() = runTest {
        // Given - the caller's year against a release group MusicBrainz first released in 2000. An
        // album cannot predate its own first release, so this is positive evidence of a different
        // album by the same artist - the case the artist check provably cannot see.
        val http = http()
        http.givenJsonResponse("release/rel-kid-a", KID_A)
        val request = EnrichmentRequest.ForAlbum(
            EnrichmentIdentifiers(musicBrainzId = "rel-kid-a"),
            title = "OK Computer", artist = "Radiohead", year = 1997,
        )

        // When - the album is enriched
        val results = engine(http).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - reported, and the caller's own album answered from its name
        assertEquals(CanonicalStatus.CONTRADICTED, results.identity.status)
        assertEquals(listOf("alternative rock"), genresOf(results.raw[EnrichmentType.GENRE]))
    }

    @Test
    fun `a caller's later year is not evidence of anything`() = runTest {
        // Given - the same release group, and a caller whose year is *after* its first release
        val http = http()
        http.givenJsonResponse("release/rel-kid-a", KID_A)
        val request = EnrichmentRequest.ForAlbum(
            EnrichmentIdentifiers(musicBrainzId = "rel-kid-a"),
            title = "Kid A", artist = "Radiohead", year = 2009,
        )

        // When - the album is enriched
        val results = engine(http).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - silent. A later year is any reissue, remaster or region pressing, and the rule is
        // one-sided on purpose: it gives up roughly half its catch rate rather than guess here.
        assertTrue(results.identity.status != CanonicalStatus.CONTRADICTED)
        assertEquals(listOf("art rock"), genresOf(results.raw[EnrichmentType.GENRE]))
    }

    @Test
    fun `a year out by one is slack, not disagreement`() = runTest {
        // Given - a caller one year before a 2000 first release, which is region and calendar-
        // boundary sloppiness in the caller's tag and in MusicBrainz's own partial dates alike
        val http = http()
        http.givenJsonResponse("release/rel-kid-a", KID_A)
        val request = EnrichmentRequest.ForAlbum(
            EnrichmentIdentifiers(musicBrainzId = "rel-kid-a"),
            title = "Kid A", artist = "Radiohead", year = 1999,
        )

        // When - the album is enriched
        val results = engine(http).enrich(request, setOf(EnrichmentType.GENRE))

        // Then - not reported: the rule needs two clear years, not one
        assertTrue(results.identity.status != CanonicalStatus.CONTRADICTED)
    }

    @Test
    fun `a release-group id the caller dates before its own first release is reported`() = runTest {
        // Given - RELEASE_EDITIONS, which reaches its album by the release-group id and so shares
        // neither the release lookup nor the year check chained onto it. The group's own
        // first-release-date is 2003, three years after the year the caller supplies with it.
        val http = UpstreamPools.load(EDITIONS_POOL)
        val request = EnrichmentRequest.ForAlbum(
            EnrichmentIdentifiers(musicBrainzReleaseGroupId = HAIL_TO_THE_THIEF_GROUP),
            title = "Hail to the Thief", artist = "Radiohead", year = 2000,
        )

        // When - the editions are enriched
        val results = engine(http).enrich(request, setOf(EnrichmentType.RELEASE_EDITIONS))

        // Then - reported, and nothing answered: editions are the whole answer here and there is no
        // name route to recover them by, so the contradicting identifier returns nothing.
        assertEquals(CanonicalStatus.CONTRADICTED, results.identity.status)
        assertTrue(results.raw[EnrichmentType.RELEASE_EDITIONS] !is EnrichmentResult.Success)
    }

    @Test
    fun `a release-group id the caller dates correctly still answers`() = runTest {
        // Given - the same group and the same request, with the caller's year matching its own
        // first release. Without this the test above would pass against a route that never answers.
        val http = UpstreamPools.load(EDITIONS_POOL)
        val request = EnrichmentRequest.ForAlbum(
            EnrichmentIdentifiers(musicBrainzReleaseGroupId = HAIL_TO_THE_THIEF_GROUP),
            title = "Hail to the Thief", artist = "Radiohead", year = 2003,
        )

        // When - the editions are enriched
        val results = engine(http).enrich(request, setOf(EnrichmentType.RELEASE_EDITIONS))

        // Then - silent, and the editions come back
        assertTrue(results.identity.status != CanonicalStatus.CONTRADICTED)
        assertTrue(results.raw[EnrichmentType.RELEASE_EDITIONS] is EnrichmentResult.Success)
    }

    private companion object {
        const val EDITIONS_POOL = "musicbrainz-release-group-editions"
        const val HAIL_TO_THE_THIEF_GROUP = "5c14fd50-a2f1-3672-9537-b0dad91bea2f"

        private fun release(
            id: String,
            title: String,
            credit: String?,
            genre: String,
            track: String = title,
            firstReleased: String = "2000-01-01",
        ) = """
            {"id":"$id","title":"$title","date":"2000-01-01","country":"GB",
             ${credit?.let { """"artist-credit":[{"artist":{"id":"a-$id","name":"$it"}}],""" }.orEmpty()}
             "media":[{"format":"CD","tracks":[
               {"title":"$track","position":1,"length":200000,"recording":{"id":"rec-$id"}}]}],
             "release-group":{"id":"rg-$id","primary-type":"Album","first-release-date":"$firstReleased",
               "tags":[{"name":"$genre","count":9}]}}
        """.trimIndent()

        val PARACHUTES = release("rel-parachutes", "Parachutes", "Coldplay", "britpop", track = "Yellow")
        val OK_COMPUTER = release("rel-ok", "OK Computer", "Radiohead", "alternative rock", track = "Airbag")
        val KID_A = release("rel-kid-a", "Kid A", "Radiohead", "art rock", firstReleased = "2000-10-02")
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
