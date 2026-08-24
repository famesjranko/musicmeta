package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every type that can answer from a caller-supplied identifier must report it when that identifier
 * names a different entity. Each type reaches its entity by its own route, and four of them went
 * unguarded after the first route was fixed — so this asserts the property across the whole surface
 * rather than naming the routes, and a new type joins the matrix by being answerable, not by being
 * added to a list here.
 *
 * The control is what makes it self-maintaining: a type is only held to the rule once the same
 * request answers `Success` under the *correct* identifier. A type that cannot answer these
 * fixtures at all is skipped rather than silently passing, and [assertTrue] on the covered set is
 * what stops the whole matrix quietly emptying out.
 */
class SuppliedIdentifierGuardMatrixTest {

    private fun engine(http: FakeHttpClient) = DefaultEnrichmentEngine(
        ProviderRegistry(listOf(MusicBrainzProvider(http, RateLimiter(0)))),
        FakeEnrichmentCache(),
        EnrichmentConfig(),
        mergers = emptyList(),
    )

    private fun artistHttp() = FakeHttpClient().apply {
        givenJsonResponse("artist/art-coldplay", COLDPLAY)
        givenJsonResponse("artist/art-radiohead", RADIOHEAD)
        givenJsonResponse("artist?query", ARTIST_SEARCH)
        givenJsonResponse("release-group?artist=art-coldplay", COLDPLAY_DISCOGRAPHY)
        givenJsonResponse("release-group?artist=art-radiohead", RADIOHEAD_DISCOGRAPHY)
    }

    @Test
    fun `every artist type answerable from an identifier reports a contradicting one`() = runTest {
        // Given - the artist types MusicBrainz declares, and the same request under the right MBID
        // and a different artist's
        val types = MusicBrainzProvider(artistHttp(), RateLimiter(0)).capabilities.map { it.type }
        val right = EnrichmentRequest.forArtist("Radiohead", mbid = "art-radiohead")
        val wrong = EnrichmentRequest.forArtist("Radiohead", mbid = "art-coldplay")

        // When - each type is enriched on its own under both, so no other type's guard stands in
        val covered = types.filter { type ->
            engine(artistHttp()).enrich(right, setOf(type)).raw[type] is EnrichmentResult.Success
        }
        val statuses = covered.associateWith { type ->
            engine(artistHttp()).enrich(wrong, setOf(type)).identity.status
        }

        // Then - every one of them reports it, over a control set that has not quietly emptied out
        assertTrue(
            "the control request stopped answering these; the matrix would pass by covering nothing: $covered",
            covered.containsAll(
                listOf(
                    EnrichmentType.GENRE,
                    EnrichmentType.BAND_MEMBERS,
                    EnrichmentType.ARTIST_DISCOGRAPHY,
                    EnrichmentType.ARTIST_LINKS,
                    EnrichmentType.ARTIST_POPULARITY,
                ),
            ),
        )
        assertEquals(covered.associateWith { CanonicalStatus.CONTRADICTED }, statuses)
    }

    private fun albumHttp() = FakeHttpClient().apply {
        givenJsonResponse("release/rel-ok", OK_COMPUTER)
        givenJsonResponse("release/rel-parachutes", PARACHUTES)
        givenJsonResponse("release-group/rg-ok", OK_COMPUTER_GROUP)
        givenJsonResponse("release-group/rg-parachutes", PARACHUTES_GROUP)
        givenJsonResponse("release?query", RELEASE_SEARCH)
    }

    @Test
    fun `every album type answerable from an identifier reports a contradicting one`() = runTest {
        // Given - the same matrix one level down, where the caller supplies a release id and a
        // release-group id, and both name a different artist's album
        val types = MusicBrainzProvider(albumHttp(), RateLimiter(0)).capabilities.map { it.type }
        val right = EnrichmentRequest.forAlbum("OK Computer", "Radiohead").withIdentifiers(
            EnrichmentIdentifiers(musicBrainzId = "rel-ok", musicBrainzReleaseGroupId = "rg-ok"),
        )
        val wrong = EnrichmentRequest.forAlbum("OK Computer", "Radiohead").withIdentifiers(
            EnrichmentIdentifiers(musicBrainzId = "rel-parachutes", musicBrainzReleaseGroupId = "rg-parachutes"),
        )

        // When - each type is enriched on its own under both
        val covered = types.filter { type ->
            engine(albumHttp()).enrich(right, setOf(type)).raw[type] is EnrichmentResult.Success
        }
        val statuses = covered.associateWith { type ->
            engine(albumHttp()).enrich(wrong, setOf(type)).identity.status
        }

        // Then - every one of them reports it, over a control set that has not quietly emptied out
        assertTrue(
            "the control request stopped answering these; the matrix would pass by covering nothing: $covered",
            covered.containsAll(
                listOf(
                    EnrichmentType.GENRE,
                    EnrichmentType.ALBUM_TRACKS,
                    EnrichmentType.RELEASE_EDITIONS,
                ),
            ),
        )
        assertEquals(covered.associateWith { CanonicalStatus.CONTRADICTED }, statuses)
    }

    private fun trackHttp() = FakeHttpClient().apply {
        givenJsonResponse("recording/rec-airbag", AIRBAG)
        givenJsonResponse("recording/rec-yellow", YELLOW)
        givenJsonResponse("recording?query", RECORDING_SEARCH)
        givenJsonResponse("release-group/rg-ok", OK_COMPUTER_GROUP)
        givenJsonResponse("release-group/rg-parachutes", PARACHUTES_GROUP)
    }

    @Test
    fun `every track type answerable from an identifier reports a contradicting one`() = runTest {
        // Given - a recording id for a different artist's track. The release-group id beside it is
        // what keeps identity resolution out of the way: with a recording id alone the resolver
        // runs and its own guard answers first, hiding whether each type has one.
        val types = MusicBrainzProvider(trackHttp(), RateLimiter(0)).capabilities.map { it.type }
        val right = EnrichmentRequest.forTrack("Airbag", "Radiohead").withIdentifiers(
            EnrichmentIdentifiers(musicBrainzId = "rec-airbag", musicBrainzReleaseGroupId = "rg-ok"),
        )
        val wrong = EnrichmentRequest.forTrack("Airbag", "Radiohead").withIdentifiers(
            EnrichmentIdentifiers(musicBrainzId = "rec-yellow", musicBrainzReleaseGroupId = "rg-parachutes"),
        )

        // When - each type is enriched on its own under both
        val covered = types.filter { type ->
            engine(trackHttp()).enrich(right, setOf(type)).raw[type] is EnrichmentResult.Success
        }
        val statuses = covered.associateWith { type ->
            engine(trackHttp()).enrich(wrong, setOf(type)).identity.status
        }

        // Then - every one of them reports it, over a control set that has not quietly emptied out
        assertTrue(
            "the control request stopped answering these; the matrix would pass by covering nothing: $covered",
            covered.containsAll(listOf(EnrichmentType.TRACK_METADATA, EnrichmentType.CREDITS)),
        )
        assertEquals(covered.associateWith { CanonicalStatus.CONTRADICTED }, statuses)
    }

    private companion object {
        private fun artist(id: String, name: String, genre: String, member: String, site: String) = """
            {"id":"$id","name":"$name","type":"Group",
             "tags":[{"name":"$genre","count":9}],
             "rating":{"value":4.5,"votes-count":12},
             "relations":[
               {"type":"member of band","direction":"backward","attributes":["vocals"],
                "artist":{"id":"m-$id","name":"$member"}},
               {"type":"official homepage","target-type":"url","url":{"resource":"$site"}}],
             "aliases":[]}
        """.trimIndent()

        val COLDPLAY = artist("art-coldplay", "Coldplay", "britpop", "Chris Martin", "https://coldplay.com")
        val RADIOHEAD = artist("art-radiohead", "Radiohead", "alternative rock", "Thom Yorke", "https://radiohead.com")

        private fun release(id: String, title: String, credit: String, genre: String, track: String) = """
            {"id":"$id","title":"$title","date":"2000-01-01","country":"GB",
             "artist-credit":[{"artist":{"id":"a-$id","name":"$credit"}}],
             "label-info":[{"catalog-number":"CAT1","label":{"name":"Parlophone"}}],
             "media":[{"format":"CD","tracks":[
               {"title":"$track","position":1,"length":200000,"recording":{"id":"rec-$id"}}]}],
             "release-group":{"id":"rg-$id","primary-type":"Album",
               "tags":[{"name":"$genre","count":9}]}}
        """.trimIndent()

        private fun group(id: String, title: String, credit: String, releaseId: String) = """
            {"id":"$id","title":"$title",
             "artist-credit":[{"artist":{"id":"a-$id","name":"$credit"}}],
             "releases":[{"id":"$releaseId","title":"$title","date":"2000-01-01","country":"GB",
               "media":[{"format":"CD"}],
               "label-info":[{"catalog-number":"CAT1","label":{"name":"Parlophone"}}]}]}
        """.trimIndent()

        val OK_COMPUTER = release("rel-ok", "OK Computer", "Radiohead", "alternative rock", "Airbag")
        val PARACHUTES = release("rel-parachutes", "Parachutes", "Coldplay", "britpop", "Yellow")
        val OK_COMPUTER_GROUP = group("rg-ok", "OK Computer", "Radiohead", "rel-ok")
        val PARACHUTES_GROUP = group("rg-parachutes", "Parachutes", "Coldplay", "rel-parachutes")

        val RELEASE_SEARCH = """
            {"releases":[{"id":"rel-ok","score":100,"title":"OK Computer","date":"1997-06-16",
              "artist-credit":[{"artist":{"id":"a-rel-ok","name":"Radiohead"}}],
              "release-group":{"id":"rg-ok","primary-type":"Album",
                "tags":[{"name":"alternative rock","count":9}]}}]}
        """.trimIndent()

        private fun recording(id: String, title: String, credit: String, genre: String, releaseId: String, album: String) = """
            {"id":"$id","title":"$title","length":266000,
             "tags":[{"name":"$genre","count":9}],
             "rating":{"value":4.5,"votes-count":12},
             "artist-credit":[{"artist":{"id":"a-$id","name":"$credit"}}],
             "relations":[{"target-type":"artist","type":"producer","attributes":[],
               "artist":{"id":"prod-$id","name":"A Producer"}}],
             "releases":[{"id":"$releaseId","status":"Official","date":"1997-06-16",
               "release-group":{"id":"rg-$releaseId","title":"$album","primary-type":"Album"}}]}
        """.trimIndent()

        val AIRBAG = recording("rec-airbag", "Airbag", "Radiohead", "alternative rock", "rel-ok", "OK Computer")
        val YELLOW = recording("rec-yellow", "Yellow", "Coldplay", "britpop", "rel-parachutes", "Parachutes")

        val RECORDING_SEARCH = """
            {"recordings":[{"id":"rec-airbag","score":100,"title":"Airbag","length":266000,
              "tags":[{"name":"alternative rock","count":9}],
              "artist-credit":[{"artist":{"id":"a-rec-airbag","name":"Radiohead"}}],
              "releases":[{"id":"rel-ok","status":"Official",
                "release-group":{"id":"rg-ok","title":"OK Computer","primary-type":"Album"}}]}]}
        """.trimIndent()

        val ARTIST_SEARCH = """
            {"artists":[{"id":"art-radiohead","name":"Radiohead","score":100,
              "tags":[{"name":"alternative rock","count":9}]}]}
        """.trimIndent()

        val COLDPLAY_DISCOGRAPHY = """
            {"release-groups":[{"id":"rg-parachutes","title":"Parachutes","primary-type":"Album",
              "first-release-date":"2000-07-10"}]}
        """.trimIndent()

        val RADIOHEAD_DISCOGRAPHY = """
            {"release-groups":[{"id":"rg-ok","title":"OK Computer","primary-type":"Album",
              "first-release-date":"1997-05-21"}]}
        """.trimIndent()
    }
}
