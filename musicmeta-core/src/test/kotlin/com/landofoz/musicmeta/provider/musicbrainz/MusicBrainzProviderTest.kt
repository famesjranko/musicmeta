package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MusicBrainzProviderTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: MusicBrainzProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = MusicBrainzProvider(httpClient, RateLimiter(0))
    }

    @Test
    fun `enrich album returns metadata for high-confidence match`() = runTest {
        // Given - MusicBrainz returns a release with score 98
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_HIGH_SCORE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for genre (triggers identity resolution)
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - success with MBID, release-group ID, and high confidence
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("musicbrainz", success.provider)
        assertEquals(0.98f, success.confidence, 0.01f)

        assertTrue(success.data is EnrichmentData.Metadata)
        assertEquals("abc123", success.resolvedIdentifiers?.musicBrainzId)
        assertEquals("group123", success.resolvedIdentifiers?.musicBrainzReleaseGroupId)
    }

    @Test
    fun `enrich album returns NotFound when score below threshold`() = runTest {
        // Given - MusicBrainz returns a low-score result (40)
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_LOW_SCORE)
        val request = EnrichmentRequest.forAlbum("Obscure Album", "Unknown")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - NotFound because score is below minimum threshold
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich artist extracts Wikidata ID`() = runTest {
        // Given - MusicBrainz artist has a Wikidata relation
        httpClient.givenJsonResponse("artist?query", ARTIST_SEARCH_WITH_WIKIDATA)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for genre (triggers identity resolution)
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - Wikidata ID extracted from the relation URL
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertTrue(success.data is EnrichmentData.Metadata)
        assertEquals("Q188451", success.resolvedIdentifiers?.wikidataId)
    }

    @Test
    fun `enrich artist extracts Wikipedia title`() = runTest {
        // Given - MusicBrainz artist has both Wikidata and Wikipedia relations
        httpClient.givenJsonResponse("artist?query", ARTIST_SEARCH_WITH_WIKIPEDIA)
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - Wikipedia title extracted from the en.wikipedia.org URL
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertTrue(success.data is EnrichmentData.Metadata)
        assertEquals("Radiohead", success.resolvedIdentifiers?.wikipediaTitle)
    }

    @Test
    fun `enrich album extracts Wikidata ID and Wikipedia title from release-group relations`() = runTest {
        // Given - release search resolves release-group "group123"; its dedicated url-rels lookup
        // carries both a wikidata and an en.wikipedia.org relation (ALBUM_DESCRIPTION's plumbing)
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_HIGH_SCORE)
        httpClient.givenJsonResponse("release-group/group123", RELEASE_GROUP_WITH_WIKI_RELATIONS)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for genre (triggers identity resolution, which resolves the release)
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - resolvedIdentifiers carries both, for WikipediaProvider's ALBUM_DESCRIPTION to use
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("Q82539", success.resolvedIdentifiers?.wikidataId)
        assertEquals("OK_Computer", success.resolvedIdentifiers?.wikipediaTitle)
    }

    @Test
    fun `enrich album leaves wikidataId and wikipediaTitle null when release-group has no wiki relations`() =
        runTest {
            // Given - release-group lookup returns no relations at all
            httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_HIGH_SCORE)
            httpClient.givenJsonResponse("release-group/group123", """{"id": "group123", "relations": []}""")
            val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

            // When - enriching for genre
            val result = provider.enrich(request, EnrichmentType.GENRE)

            // Then - succeeds, with the wiki identifiers left unset
            assertTrue(result is EnrichmentResult.Success)
            val success = result as EnrichmentResult.Success
            assertEquals(null, success.resolvedIdentifiers?.wikidataId)
            assertEquals(null, success.resolvedIdentifiers?.wikipediaTitle)
        }

    @Test
    fun `a transient on the release-group wiki lookup does not fail GENRE`() = runTest {
        // Given - the release search succeeds, but the release-group's dedicated wiki-links lookup
        // 503s. Before the fix this propagated out of buildAlbumResult and turned GENRE itself into
        // an Error, even though GENRE's own data (genres, label, dates) was already in hand — the
        // live symptom this pins: ALBUM_DESCRIPTION not_found on one run, ok on the identical next.
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_HIGH_SCORE)
        httpClient.givenHttpResult("release-group/group123", HttpResult.ServerError(503))
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - GENRE still succeeds with its own data; the wiki-links transient just leaves
        // wikidataId/wikipediaTitle unresolved for this call rather than masquerading as "this
        // release-group has none" or failing the type that never asked for wiki data.
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertTrue(success.data is EnrichmentData.Metadata)
        assertEquals(null, success.resolvedIdentifiers?.wikidataId)
        assertEquals(null, success.resolvedIdentifiers?.wikipediaTitle)
    }

    @Test
    fun `a timeout on the release-group wiki lookup does not fail LABEL`() = runTest {
        // Given - same shape, a different type and a network-level transient (not an HTTP status)
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_HIGH_SCORE)
        httpClient.givenIoException("release-group/group123")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for label
        val result = provider.enrich(request, EnrichmentType.LABEL)

        // Then - LABEL still succeeds despite the timeout on the side lookup
        assertTrue(result is EnrichmentResult.Success)
    }

    @Test
    fun `a transient release-group wiki lookup is retried, not cached as absence`() = runTest {
        // Given - the first call 503s; the second call (a different type resolving the same
        // release-group, still within this enricher's lifetime) succeeds
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_HIGH_SCORE)
        httpClient.givenHttpResult("release-group/group123", HttpResult.ServerError(503))
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
        provider.enrich(request, EnrichmentType.GENRE)

        // When - the transient clears and LABEL resolves the same album
        httpClient.givenHttpResult(
            "release-group/group123",
            HttpResult.Ok(org.json.JSONObject(RELEASE_GROUP_WITH_WIKI_RELATIONS)),
        )
        val result = provider.enrich(request, EnrichmentType.LABEL)

        // Then - the earlier failure was never cached as "no wiki links", so this call resolves
        // the real relations rather than being stuck with a false negative
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("Q82539", success.resolvedIdentifiers?.wikidataId)
    }

    @Test
    fun `enrich returns NotFound on null response`() = runTest {
        // Given - no canned response configured (API returns null -> emptyList)
        val request = EnrichmentRequest.forAlbum("Test", "Test")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - NotFound because empty results mean no match
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `empty album search results return NotFound`() = runTest {
        // Given - MusicBrainz returns an empty releases array
        httpClient.givenJsonResponse("release?query", """{"releases":[]}""")
        val request = EnrichmentRequest.forAlbum("Nothing", "Nobody")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - NotFound because no releases matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `empty artist search results return NotFound`() = runTest {
        // Given - MusicBrainz returns an empty artists array
        httpClient.givenJsonResponse("artist?query", """{"artists":[]}""")
        val request = EnrichmentRequest.forArtist("Nobody")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - NotFound because no artists matched
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `empty artist search results return NotFound for BAND_MEMBERS, ARTIST_DISCOGRAPHY and ARTIST_LINKS`() = runTest {
        // Given - MusicBrainz returns an empty artists array, requested without an MBID
        httpClient.givenJsonResponse("artist?query", """{"artists":[]}""")
        val request = EnrichmentRequest.forArtist("Nobody")

        // When - enriching for each of the three types that resolve an artist by search
        val bandMembers = provider.enrich(request, EnrichmentType.BAND_MEMBERS)
        val discography = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)
        val links = provider.enrich(request, EnrichmentType.ARTIST_LINKS)

        // Then - NotFound for all three, not an Error from ranking an empty pool
        assertTrue(bandMembers is EnrichmentResult.NotFound)
        assertTrue(discography is EnrichmentResult.NotFound)
        assertTrue(links is EnrichmentResult.NotFound)
    }

    @Test
    fun `empty recording search results return NotFound`() = runTest {
        // Given - MusicBrainz returns an empty recordings array
        httpClient.givenJsonResponse("recording?query", """{"recordings":[]}""")
        val request = EnrichmentRequest.forTrack("Nothing", "Nobody")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - NotFound because no recordings matched, and no suggestions invented from nothing
        assertTrue(result is EnrichmentResult.NotFound)
        assertEquals(null, (result as EnrichmentResult.NotFound).suggestions)
    }

    @Test
    fun `enrich returns Error on malformed JSON`() = runTest {
        // Given - response has a release object missing the required 'id' field
        httpClient.givenJsonResponse(
            "release?query",
            """{"releases":[{"title":"NoId"}]}""",
        )
        val request = EnrichmentRequest.forAlbum("Test", "Test")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - Error with a descriptive message about the parse failure
        assertTrue(result is EnrichmentResult.Error)
        assertNotNull((result as EnrichmentResult.Error).message)
    }

    @Test
    fun `enrich track returns identifier for high-confidence match`() = runTest {
        // Given - MusicBrainz returns a recording match with score 95
        httpClient.givenJsonResponse("recording?query", RECORDING_SEARCH)
        val request = EnrichmentRequest.forTrack("Paranoid Android", "Radiohead")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - recording MBID resolved successfully
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertTrue(success.data is EnrichmentData.Metadata)
        assertEquals("rec1", success.resolvedIdentifiers?.musicBrainzId)
    }

    @Test
    fun `enrich track ranks a wide score-100 pool to the blank-disambiguation exact-title studio recording`() =
        runTest {
            // Given - a 9-candidate pool mirroring live musicbrainz.org "Enter Sandman"/Metallica
            // evidence (2026-08-06): seven non-blank-disambiguation hits first (all score 100,
            // including a "bootleg edited version" that no demo/live/remix/remaster keyword list
            // would catch), then the blank-disambiguation studio original, then a blank-
            // disambiguation per-member cover ("Enter Sandman (Ulrich)") that a title-blind ranking
            // would prefer
            httpClient.givenJsonResponse("recording?query", ENTER_SANDMAN_RANK_POOL)
            val request = EnrichmentRequest.forTrack("Enter Sandman", "Metallica")

            // When - enriching for genre (drives identity resolution through enrichTrack)
            val result = provider.enrich(request, EnrichmentType.GENRE)

            // Then - the blank-disambiguation, exact-title studio recording wins, not a
            // non-blank-disambiguation take (however it's worded) or a blank-disambiguation title
            // variant
            assertTrue(result is EnrichmentResult.Success)
            val success = result as EnrichmentResult.Success
            assertEquals("rec-studio", success.resolvedIdentifiers?.musicBrainzId)
        }

    @Test
    fun `enrich track fills musicBrainzReleaseGroupId from the ranked recording's Official Album`() =
        runTest {
            // Given - the ranked recording (rec-studio) carries an Official Album release-group id
            httpClient.givenJsonResponse("recording?query", ENTER_SANDMAN_RANK_POOL_WITH_GROUP_ID)
            val request = EnrichmentRequest.forTrack("Enter Sandman", "Metallica")

            // When - enriching for genre (drives identity resolution through enrichTrack)
            val result = provider.enrich(request, EnrichmentType.GENRE)

            // Then - resolvedIdentifiers carries the recording id AND the release-group id, so CAA
            // can serve front-cover art for the track without ever treating the recording id as a
            // release id (CoverArtArchiveProvider)
            assertTrue(result is EnrichmentResult.Success)
            val success = result as EnrichmentResult.Success
            assertEquals("rec-studio", success.resolvedIdentifiers?.musicBrainzId)
            assertEquals("rg-studio", success.resolvedIdentifiers?.musicBrainzReleaseGroupId)
        }

    @Test
    fun `enrich track still rejects a pool with no candidate at or above the score threshold`() = runTest {
        // Given - every candidate scores below the default minimum match score (80)
        httpClient.givenJsonResponse(
            "recording?query",
            """{"recordings":[{"id":"rec-low","score":50,"title":"Enter Sandman"}]}""",
        )
        val request = EnrichmentRequest.forTrack("Enter Sandman", "Metallica")

        // When - enriching for genre
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - NotFound; the ranking pass never gets a candidate to choose from, but the raw pool
        // that failed to rank is still carried as a suggestion, same as enrichAlbum/enrichArtist
        assertTrue(result is EnrichmentResult.NotFound)
        val suggestions = (result as EnrichmentResult.NotFound).suggestions
        assertEquals(listOf("Enter Sandman"), suggestions?.map { it.title })
        assertEquals("rec-low", suggestions?.single()?.identifiers?.musicBrainzId)
    }

    @Test
    fun `enrich track identity resolution surfaces suggestions for a gibberish title, mirroring album-artist`() =
        runTest {
            // Given - the strict search returns a non-empty pool that fails to rank, so the
            // suggestions must come from that pool itself; the fuzzy search (stubbed with a decoy)
            // must not run. MusicBrainzTransientFailureTest covers the empty-pool → fuzzy branch.
            httpClient.givenJsonResponse(
                "recording%3A%22Zzxxqwerty12345%22",
                """{"recordings":[{"id":"rec-near","score":60,"title":"Metallica Anthology"}]}""",
            )
            httpClient.givenJsonResponse(
                "recording%3AZzxxqwerty12345%7E",
                """{"recordings":[{"id":"rec-decoy","score":50,"title":"Fuzzy Decoy"}]}""",
            )
            val request = EnrichmentRequest.forTrack("Zzxxqwerty12345", "Metallica")

            // When - resolving identity (what DefaultEnrichmentEngine calls before fan-out)
            val result = provider.resolveIdentity(request)

            // Then - NotFound carrying the strict pool as suggestions, so CanonicalStatus.AMBIGUOUS
            // becomes reachable, and the fuzzy search was never queried
            assertTrue(result is EnrichmentResult.NotFound)
            val suggestions = (result as EnrichmentResult.NotFound).suggestions
            assertEquals(listOf("Metallica Anthology"), suggestions?.map { it.title })
            assertTrue(httpClient.requestedUrls.none { it.contains("Zzxxqwerty12345%7E") })
        }

    @Test
    fun `enrich track sends the release-hinted query when the request carries an album hint`() = runTest {
        // Given - a matching recording behind the release-hinted query
        httpClient.givenJsonResponse("recording?query", RECORDING_SEARCH)
        val request = EnrichmentRequest.forTrack("Paranoid Android", "Radiohead", album = "OK Computer")

        // When - enriching for genre
        provider.enrich(request, EnrichmentType.GENRE)

        // Then - the Lucene query carries the album's release:"..." term
        val url = httpClient.requestedUrls.single { it.contains("recording?query") }
        assertTrue(url.contains("release%3A%22OK+Computer%22"))
    }

    @Test
    fun `enrich track TRACK_METADATA returns duration, album title and disambiguation`() = runTest {
        // Given - a recording carrying length, an art release-group title, and a disambiguation
        httpClient.givenJsonResponse(
            "recording?query",
            """
            {
              "recordings": [{
                "id": "rec-studio",
                "score": 95,
                "title": "Enter Sandman",
                "length": 331000,
                "disambiguation": "",
                "releases": [
                  {"status": "Official", "release-group": {"id": "rg-studio", "primary-type": "Album", "title": "Metallica"}}
                ]
              }]
            }
            """.trimIndent(),
        )
        val request = EnrichmentRequest.forTrack("Enter Sandman", "Metallica")

        // When - enriching for track metadata
        val result = provider.enrich(request, EnrichmentType.TRACK_METADATA)

        // Then - duration, album title, and identifiers are all populated
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val data = success.data as EnrichmentData.TrackMetadata
        assertEquals(331000L, data.durationMs)
        assertEquals("Metallica", data.albumTitle)
        assertEquals("rec-studio", success.resolvedIdentifiers?.musicBrainzId)
        assertEquals("rg-studio", success.resolvedIdentifiers?.musicBrainzReleaseGroupId)
    }

    @Test
    fun `enrich track TRACK_METADATA returns NotFound when no candidate meets the score threshold`() = runTest {
        // Given - the only candidate scores below the default minimum match score (80)
        httpClient.givenJsonResponse(
            "recording?query",
            """{"recordings":[{"id":"rec-low","score":50,"title":"Enter Sandman"}]}""",
        )
        val request = EnrichmentRequest.forTrack("Enter Sandman", "Metallica")

        // When - enriching for track metadata
        val result = provider.enrich(request, EnrichmentType.TRACK_METADATA)

        // Then - NotFound because no candidate meets the threshold
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns BandMembers for artist with members`() = runTest {
        // Given - artist lookup with artist-rels returns band member relations
        httpClient.givenJsonResponse("artist/art1?fmt=json&inc=tags+genres+aliases+ratings+url-rels+artist-rels", ARTIST_LOOKUP_WITH_MEMBERS)
        val request = EnrichmentRequest.forArtist("Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "art1"))

        // When - enriching for BAND_MEMBERS
        val result = provider.enrich(request, EnrichmentType.BAND_MEMBERS)

        // Then - Success with BandMembers data containing member names and roles
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val data = success.data as EnrichmentData.BandMembers
        assertEquals(2, data.members.size)
        assertEquals("Thom Yorke", data.members[0].name)
        assertEquals("lead vocals", data.members[0].role)
    }

    @Test
    fun `the artist memo does not outlive the call that filled it`() = runTest {
        // Given - every artist lookup resolves
        httpClient.givenJsonResponse("artist/", ARTIST_LOOKUP_WITH_MEMBERS)
        suspend fun lookup(mbid: String) = provider.enrich(
            EnrichmentRequest.forArtist("Radiohead")
                .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = mbid)),
            EnrichmentType.BAND_MEMBERS,
        )

        // When - the same MBID is asked for by two separate calls
        lookup("art1")
        lookup("art1")

        // Then - the second call fetched it again rather than answering from the first call's memo,
        // which is what lets a consumer's forceRefresh reach MusicBrainz. The saving the memo does
        // buy — one lookup for every type of a single call — is pinned in ProviderMemoLifetimeTest.
        assertEquals(2, httpClient.requestedUrls.count { it.contains("artist/art1?") })
    }

    @Test
    fun `enrich returns NotFound for BandMembers when no members in relations`() = runTest {
        // Given - artist lookup returns no member-of-band relations
        httpClient.givenJsonResponse("artist/art1?fmt=json&inc=tags+genres+aliases+ratings+url-rels+artist-rels", ARTIST_LOOKUP_NO_MEMBERS)
        val request = EnrichmentRequest.forArtist("Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "art1"))

        // When - enriching for BAND_MEMBERS
        val result = provider.enrich(request, EnrichmentType.BAND_MEMBERS)

        // Then - NotFound because no band members in relations
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Discography for artist`() = runTest {
        // Given - artist search followed by release-group browse
        httpClient.givenJsonResponse("artist?query", ARTIST_SEARCH_WITH_WIKIDATA)
        httpClient.givenJsonResponse("release-group?artist=art1", RELEASE_GROUP_BROWSE)
        val request = EnrichmentRequest.forArtist("Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "art1"))

        // When - enriching for ARTIST_DISCOGRAPHY
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then - Success with Discography data
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val data = success.data as EnrichmentData.Discography
        assertEquals(2, data.albums.size)
        assertEquals("OK Computer", data.albums[0].title)
        assertEquals("1997", data.albums[0].year)
        assertEquals("Album", data.albums[0].type)
    }

    @Test
    fun `enrich returns Tracklist for album`() = runTest {
        // Given - release lookup with media array
        httpClient.givenJsonResponse("release/rel1?fmt=json", RELEASE_LOOKUP_WITH_TRACKS)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "rel1"))

        // When - enriching for ALBUM_TRACKS
        val result = provider.enrich(request, EnrichmentType.ALBUM_TRACKS)

        // Then - Success with Tracklist data
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val data = success.data as EnrichmentData.Tracklist
        assertEquals(2, data.tracks.size)
        assertEquals("Airbag", data.tracks[0].title)
        assertEquals(1, data.tracks[0].position)
        assertEquals(284000L, data.tracks[0].durationMs)
    }

    @Test
    fun `enrich returns ArtistLinks for artist`() = runTest {
        // Given - artist lookup with URL relations
        httpClient.givenJsonResponse("artist/art1?fmt=json&inc=tags+genres+aliases+ratings+url-rels", ARTIST_LOOKUP_WITH_LINKS)
        val request = EnrichmentRequest.forArtist("Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "art1"))

        // When - enriching for ARTIST_LINKS
        val result = provider.enrich(request, EnrichmentType.ARTIST_LINKS)

        // Then - Success with ArtistLinks data, wikidata/wikipedia excluded
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val data = success.data as EnrichmentData.ArtistLinks
        assertEquals(2, data.links.size)
        assertEquals("official homepage", data.links[0].type)
        assertEquals("https://radiohead.com", data.links[0].url)
    }

    @Test
    fun `enrich returns Error with NETWORK ErrorKind when API throws IOException`() = runTest {
        // Given - fetchJsonResult throws IOException simulating a network failure
        httpClient.givenIoException("musicbrainz.org")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for genre (triggers searchReleases which throws)
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - Error with NETWORK ErrorKind
        assertTrue("Expected Error but got $result", result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    @Test
    fun `provider has CREDITS capability at priority 100 with MUSICBRAINZ_ID requirement`() {
        // Given - provider capabilities list
        val capability = provider.capabilities.find { it.type == EnrichmentType.CREDITS }

        // Then - CREDITS capability exists with correct priority and identifier requirement
        assertNotNull(capability)
        assertEquals(100, capability!!.priority)
        assertEquals(com.landofoz.musicmeta.IdentifierRequirement.MUSICBRAINZ_ID, capability.identifierRequirement)
    }

    @Test
    fun `enrich ForTrack CREDITS with MBID returns Success with credits`() = runTest {
        // Given - recording lookup returns artist-rels and work-rels
        httpClient.givenJsonResponse("recording/rec1", RECORDING_WITH_CREDITS_JSON)
        val request = EnrichmentRequest.forTrack("Paranoid Android", "Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "rec1"))

        // When - enriching for CREDITS
        val result = provider.enrich(request, EnrichmentType.CREDITS)

        // Then - Success with Credits data, at least 3 credits, confidence 1.0
        assertTrue("Expected Success but got $result", result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val data = success.data as EnrichmentData.Credits
        assertTrue("Expected >= 3 credits, got ${data.credits.size}", data.credits.size >= 3)
        assertEquals(1.0f, success.confidence, 0.001f)
    }

    @Test
    fun `enrich ForTrack CREDITS returns NotFound when recording has no relations`() = runTest {
        // Given - recording with empty relations
        httpClient.givenJsonResponse("recording/rec1", """{"id":"rec1","title":"Song","relations":[]}""")
        val request = EnrichmentRequest.forTrack("Song", "Artist")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "rec1"))

        // When - enriching for CREDITS
        val result = provider.enrich(request, EnrichmentType.CREDITS)

        // Then - NotFound because no credits parsed
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich ForTrack CREDITS returns NotFound when no MBID`() = runTest {
        // Given - ForTrack request without MBID
        val request = EnrichmentRequest.forTrack("Song", "Artist")

        // When - enriching for CREDITS directly (no MBID)
        val result = provider.enrich(request, EnrichmentType.CREDITS)

        // Then - NotFound because no MBID available for lookup
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `provider has RELEASE_EDITIONS capability at priority 100 with MUSICBRAINZ_RELEASE_GROUP_ID requirement`() {
        // Given - provider capabilities list
        val capability = provider.capabilities.find { it.type == EnrichmentType.RELEASE_EDITIONS }

        // Then - RELEASE_EDITIONS capability exists with correct priority and identifier requirement
        assertNotNull(capability)
        assertEquals(100, capability!!.priority)
        assertEquals(com.landofoz.musicmeta.IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID, capability.identifierRequirement)
    }

    @Test
    fun `enrichAlbumEditions returns Success with ReleaseEditions when release-group has releases`() = runTest {
        // Given - release-group lookup returns JSON with releases
        httpClient.givenJsonResponse("release-group/rg1", RELEASE_GROUP_WITH_RELEASES_JSON)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzReleaseGroupId = "rg1"))

        // When - enriching for RELEASE_EDITIONS
        val result = provider.enrich(request, EnrichmentType.RELEASE_EDITIONS)

        // Then - Success with ReleaseEditions data containing editions
        assertTrue("Expected Success but got $result", result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        val data = success.data as EnrichmentData.ReleaseEditions
        assertTrue("Expected non-empty editions, got ${data.editions.size}", data.editions.isNotEmpty())
        assertEquals(1.0f, success.confidence, 0.001f)
    }

    @Test
    fun `enrichAlbumEditions returns NotFound when release-group MBID missing from identifiers`() = runTest {
        // Given - ForAlbum request without release-group MBID
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for RELEASE_EDITIONS
        val result = provider.enrich(request, EnrichmentType.RELEASE_EDITIONS)

        // Then - NotFound because no release-group MBID available
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrichAlbumEditions returns NotFound when lookupReleaseGroup returns null`() = runTest {
        // Given - no canned response for release-group lookup (returns null)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzReleaseGroupId = "rg1"))

        // When - enriching for RELEASE_EDITIONS (no HTTP response configured)
        val result = provider.enrich(request, EnrichmentType.RELEASE_EDITIONS)

        // Then - NotFound because lookupReleaseGroup returned null
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrichAlbumEditions returns Error with NETWORK ErrorKind on IOException`() = runTest {
        // Given - release-group lookup throws IOException
        httpClient.givenIoException("release-group/")
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzReleaseGroupId = "rg1"))

        // When - enriching for RELEASE_EDITIONS
        val result = provider.enrich(request, EnrichmentType.RELEASE_EDITIONS)

        // Then - Error with NETWORK ErrorKind
        assertTrue("Expected Error but got $result", result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    @Test
    fun `enrich ForTrack CREDITS returns Error with NETWORK ErrorKind on IOException`() = runTest {
        // Given - recording lookup throws IOException
        httpClient.givenIoException("recording/")
        val request = EnrichmentRequest.forTrack("Song", "Artist")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "rec1"))

        // When - enriching for CREDITS
        val result = provider.enrich(request, EnrichmentType.CREDITS)

        // Then - Error with NETWORK ErrorKind
        assertTrue("Expected Error but got $result", result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
    }

    // --- ForAlbum guard for artist-only types ---

    @Test
    fun `enrichAlbum returns NotFound for BAND_MEMBERS`() = runTest {
        // Given - ForAlbum request
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "rel1"))

        // When - enriching for BAND_MEMBERS (artist-only type)
        val result = provider.enrich(request, EnrichmentType.BAND_MEMBERS)

        // Then - NotFound instead of leaking Metadata
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrichAlbum returns NotFound for ARTIST_DISCOGRAPHY`() = runTest {
        // Given - ForAlbum request
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "rel1"))

        // When - enriching for ARTIST_DISCOGRAPHY (artist-only type)
        val result = provider.enrich(request, EnrichmentType.ARTIST_DISCOGRAPHY)

        // Then - NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrichAlbum returns NotFound for ARTIST_LINKS`() = runTest {
        // Given - ForAlbum request
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "rel1"))

        // When - enriching for ARTIST_LINKS (artist-only type)
        val result = provider.enrich(request, EnrichmentType.ARTIST_LINKS)

        // Then - NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrichAlbum returns NotFound for CREDITS`() = runTest {
        // Given - ForAlbum request
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
            .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "rel1"))

        // When - enriching for CREDITS (track-only type)
        val result = provider.enrich(request, EnrichmentType.CREDITS)

        // Then - NotFound
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich album GENRE looks the release up when the search hit has no tags`() = runTest {
        // Given - a high-scoring search hit with neither tags nor a label
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_THIN)
        httpClient.givenJsonResponse("release/thin1", RELEASE_LOOKUP_FULL)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for GENRE
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - the lookup filled the genres, and the search score still sets confidence
        val success = result as EnrichmentResult.Success
        assertEquals(listOf("alternative rock"), (success.data as EnrichmentData.Metadata).genres)
        assertEquals(0.98f, success.confidence, 0.01f)
        assertTrue(httpClient.requestedUrls.any { it.contains("release/thin1") })
    }

    @Test
    fun `the release memo does not outlive the call that filled it`() = runTest {
        // Given - a thin search hit, so GENRE takes the lookup path
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_THIN)
        httpClient.givenJsonResponse("release/", RELEASE_LOOKUP_FULL)

        // When - one call resolves thin1 by search, and a second asks for it by MBID
        provider.enrich(EnrichmentRequest.forAlbum("OK Computer", "Radiohead"), EnrichmentType.GENRE)
        provider.enrich(
            EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
                .withIdentifiers(EnrichmentIdentifiers(musicBrainzId = "thin1")),
            EnrichmentType.GENRE,
        )

        // Then - the second call fetched the release again rather than answering from the first
        // call's memo; the within-a-call saving is pinned in ProviderMemoLifetimeTest.
        assertEquals(2, httpClient.requestedUrls.count { it.contains("release/thin1?") })
    }

    @Test
    fun `enrich album GENRE does not look the release up when the search hit has tags`() = runTest {
        // Given - a search hit that already carries release-group tags and a label
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_HIGH_SCORE)
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        // When - enriching for GENRE
        provider.enrich(request, EnrichmentType.GENRE)

        // Then - the search plus one release-group wiki-links lookup (ALBUM_DESCRIPTION's
        // wikidata/wikipedia resolution, `buildAlbumResult` §MusicBrainzEnricher) — no full release
        // lookup, since the search hit already carried tags.
        assertEquals(2, httpClient.requestedUrls.size)
        assertTrue(httpClient.requestedUrls.any { it.contains("release-group/group123") })
    }

    // --- transient side-lookup must not resolve to a cacheable NotFound ---

    @Test
    fun `a transient release-group wiki lookup reclassifies ALBUM_DESCRIPTION to Error in the same run that resolves GENRE`() = runTest {
        // Given - the release search succeeds and GENRE resolves fine, but the release-group's
        // dedicated wiki-links lookup 503s (transient). ALBUM_DESCRIPTION has no MusicBrainz
        // capability at all — this is the ticket's literal end-to-end scenario, real
        // MusicBrainzProvider + real WikipediaProvider (never actually called — skipped for its
        // unresolved WIKIPEDIA_TITLE requirement) + a Last.fm-shaped fake that has no requirement
        // and returns its own genuine NotFound.
        httpClient.givenJsonResponse("release?query", RELEASE_SEARCH_HIGH_SCORE)
        httpClient.givenHttpResult("release-group/group123", HttpResult.ServerError(503))
        val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")

        val wikipedia = com.landofoz.musicmeta.provider.wikipedia.WikipediaProvider(httpClient, RateLimiter(0))
        val lastfmLike = com.landofoz.musicmeta.testutil.FakeProvider(
            id = "lastfm",
            capabilities = listOf(
                com.landofoz.musicmeta.ProviderCapability(EnrichmentType.ALBUM_DESCRIPTION, 50),
            ),
        ).also {
            it.givenResult(
                EnrichmentType.ALBUM_DESCRIPTION,
                EnrichmentResult.NotFound(EnrichmentType.ALBUM_DESCRIPTION, "lastfm"),
            )
        }
        val engine = com.landofoz.musicmeta.engine.DefaultEnrichmentEngine(
            com.landofoz.musicmeta.engine.ProviderRegistry(listOf(provider, wikipedia, lastfmLike)),
            com.landofoz.musicmeta.testutil.FakeEnrichmentCache(),
            com.landofoz.musicmeta.EnrichmentConfig(enableIdentityResolution = true),
            // GenreMerger excluded: GENRE is IDENTITY_TYPES-eligible and is meant to be served
            // directly from identity resolution's own Success here, not refetched by MBID (which
            // this test doesn't mock a canned response for) via a second resolveAll() call.
            mergers = emptyList(),
        )

        // When - enriching for both GENRE and ALBUM_DESCRIPTION in the same engine run
        val results = engine.enrich(request, setOf(EnrichmentType.GENRE, EnrichmentType.ALBUM_DESCRIPTION))

        // Then - GENRE resolved fine (the unrelated type the transient rode in on)...
        assertTrue(results.raw[EnrichmentType.GENRE] is EnrichmentResult.Success)
        // ...and ALBUM_DESCRIPTION is Error, not a cacheable NotFound
        assertTrue(
            "expected Error, got ${results.raw[EnrichmentType.ALBUM_DESCRIPTION]}",
            results.raw[EnrichmentType.ALBUM_DESCRIPTION] is EnrichmentResult.Error,
        )
    }

    @Test
    fun `a transient artist-relations lookup no longer throws, and marks the run's transient set instead`() = runTest {
        // Given - the artist search hit needs the full relations fetch (no wikidataId/wikipediaTitle
        // on the search result), and that fetch 503s
        httpClient.givenJsonResponse("artist?query", ARTIST_SEARCH_NO_WIKI_RELATIONS)
        httpClient.givenHttpResult("artist/", HttpResult.ServerError(503))
        val request = EnrichmentRequest.forArtist("Radiohead")

        // When - enriching for GENRE, which never asked for wiki data
        val result = provider.enrich(request, EnrichmentType.GENRE)

        // Then - GENRE still succeeds with the search-hit data rather than the enricher throwing
        assertTrue("expected Success, got $result", result is EnrichmentResult.Success)
    }

    companion object {
        private val ARTIST_SEARCH_NO_WIKI_RELATIONS = """
            {
              "artists": [{
                "id": "artist1",
                "name": "Radiohead",
                "score": 100,
                "type": "Group"
              }]
            }
        """.trimIndent()

        private val RELEASE_SEARCH_THIN = """
            {
              "releases": [{
                "id": "thin1",
                "score": 98,
                "title": "OK Computer",
                "artist-credit": [{"artist": {"id": "def456", "name": "Radiohead"}}],
                "date": "1997-06-16",
                "release-group": {"id": "group123", "primary-type": "Album"}
              }]
            }
        """.trimIndent()

        private val RELEASE_LOOKUP_FULL = """
            {
              "id": "thin1",
              "title": "OK Computer",
              "date": "1997-06-16",
              "country": "GB",
              "label-info": [{"label": {"name": "Parlophone"}}],
              "release-group": {
                "id": "group123",
                "primary-type": "Album",
                "tags": [{"name": "alternative rock", "count": 5}]
              }
            }
        """.trimIndent()

        private val RELEASE_SEARCH_HIGH_SCORE = """
            {
              "releases": [{
                "id": "abc123",
                "score": 98,
                "title": "OK Computer",
                "artist-credit": [{"artist": {"id": "def456", "name": "Radiohead"}}],
                "date": "1997-06-16",
                "country": "GB",
                "label-info": [{"label": {"name": "Parlophone"}}],
                "release-group": {
                  "id": "group123",
                  "primary-type": "Album",
                  "tags": [{"name": "alternative rock", "count": 5}]
                },
                "cover-art-archive": {"front": true}
              }]
            }
        """.trimIndent()

        private val RELEASE_GROUP_WITH_WIKI_RELATIONS = """
            {
              "id": "group123",
              "relations": [
                {
                  "type": "wikidata",
                  "url": {"resource": "https://www.wikidata.org/wiki/Q82539"}
                },
                {
                  "type": "wikipedia",
                  "url": {"resource": "https://en.wikipedia.org/wiki/OK_Computer"}
                }
              ]
            }
        """.trimIndent()

        private val RELEASE_SEARCH_LOW_SCORE = """
            {
              "releases": [{
                "id": "low1",
                "score": 40,
                "title": "Something Else",
                "artist-credit": [{"artist": {"name": "Someone"}}]
              }]
            }
        """.trimIndent()

        private val ARTIST_SEARCH_WITH_WIKIDATA = """
            {
              "artists": [{
                "id": "art1",
                "name": "Radiohead",
                "score": 100,
                "type": "Group",
                "country": "GB",
                "tags": [{"name": "alternative rock", "count": 10}],
                "relations": [{
                  "type": "wikidata",
                  "url": {"resource": "https://www.wikidata.org/wiki/Q188451"}
                }]
              }]
            }
        """.trimIndent()

        private val ARTIST_SEARCH_WITH_WIKIPEDIA = """
            {
              "artists": [{
                "id": "art1",
                "name": "Radiohead",
                "score": 100,
                "type": "Group",
                "tags": [{"name": "rock", "count": 5}],
                "relations": [
                  {
                    "type": "wikidata",
                    "url": {"resource": "https://www.wikidata.org/wiki/Q188451"}
                  },
                  {
                    "type": "wikipedia",
                    "url": {"resource": "https://en.wikipedia.org/wiki/Radiohead"}
                  }
                ]
              }]
            }
        """.trimIndent()

        private val ARTIST_LOOKUP_WITH_MEMBERS = """
            {
              "id": "art1",
              "name": "Radiohead",
              "type": "Group",
              "tags": [{"name": "rock", "count": 5}],
              "relations": [
                {
                  "type": "member of band",
                  "direction": "backward",
                  "artist": {"id": "m1", "name": "Thom Yorke"},
                  "attributes": ["lead vocals"],
                  "begin": "1985",
                  "ended": false
                },
                {
                  "type": "member of band",
                  "direction": "backward",
                  "artist": {"id": "m2", "name": "Jonny Greenwood"},
                  "attributes": ["guitar"],
                  "begin": "1985",
                  "ended": false
                }
              ]
            }
        """.trimIndent()

        private val ARTIST_LOOKUP_NO_MEMBERS = """
            {
              "id": "art1",
              "name": "Radiohead",
              "type": "Group",
              "tags": [{"name": "rock", "count": 5}],
              "relations": [
                {
                  "type": "wikidata",
                  "target-type": "url",
                  "url": {"resource": "https://www.wikidata.org/wiki/Q188451"}
                }
              ]
            }
        """.trimIndent()

        private val RELEASE_GROUP_BROWSE = """
            {
              "release-groups": [
                {
                  "id": "rg1",
                  "title": "OK Computer",
                  "primary-type": "Album",
                  "first-release-date": "1997-06-16"
                },
                {
                  "id": "rg2",
                  "title": "The Bends",
                  "primary-type": "Album",
                  "first-release-date": "1995-03-13"
                }
              ]
            }
        """.trimIndent()

        private val RELEASE_LOOKUP_WITH_TRACKS = """
            {
              "id": "rel1",
              "title": "OK Computer",
              "artist-credit": [{"artist": {"name": "Radiohead"}}],
              "release-group": {"id": "rg1", "primary-type": "Album"},
              "media": [{
                "tracks": [
                  {
                    "title": "Airbag",
                    "position": 1,
                    "length": 284000,
                    "recording": {"id": "rec-1"}
                  },
                  {
                    "title": "Paranoid Android",
                    "position": 2,
                    "length": 383000,
                    "recording": {"id": "rec-2"}
                  }
                ]
              }]
            }
        """.trimIndent()

        private val ARTIST_LOOKUP_WITH_LINKS = """
            {
              "id": "art1",
              "name": "Radiohead",
              "type": "Group",
              "tags": [{"name": "rock", "count": 5}],
              "relations": [
                {
                  "type": "official homepage",
                  "target-type": "url",
                  "url": {"resource": "https://radiohead.com"}
                },
                {
                  "type": "bandcamp",
                  "target-type": "url",
                  "url": {"resource": "https://radiohead.bandcamp.com"}
                },
                {
                  "type": "wikidata",
                  "target-type": "url",
                  "url": {"resource": "https://www.wikidata.org/wiki/Q188451"}
                },
                {
                  "type": "wikipedia",
                  "target-type": "url",
                  "url": {"resource": "https://en.wikipedia.org/wiki/Radiohead"}
                }
              ]
            }
        """.trimIndent()

        private val RECORDING_SEARCH = """
            {
              "recordings": [{
                "id": "rec1",
                "score": 95,
                "title": "Paranoid Android",
                "isrcs": ["GBAYE9700100"],
                "tags": [{"name": "alternative rock", "count": 3}]
              }]
            }
        """.trimIndent()

        /**
         * Mirrors live musicbrainz.org "recording:Enter Sandman AND artistname:Metallica" evidence
         * (2026-08-06): seven score-100 candidates carrying a non-blank disambiguation before the
         * studio original, which carries none and an Official Album release, at index 7. The demo
         * and live entries exercise blank-vs-non-blank the same way keyword markers used to; the
         * "bootleg edited version" entry is the live-shaped candidate that a keyword list missed —
         * not "demo"/"live"/"remix"/"remaster", but still non-blank, so blank-preference still
         * ranks it below the studio original. A blank-disambiguation per-member cover ("Enter
         * Sandman (Ulrich)") follows last — blank disambiguation and pool position alone would pick
         * it over the studio original if title were not ranked first, so it still pins
         * [MusicBrainzEnricher.pickBestRecording]'s tier order.
         */
        private val ENTER_SANDMAN_RANK_POOL = """
            {
              "recordings": [
                {
                  "id": "rec-live-1", "score": 100, "title": "Enter Sandman",
                  "disambiguation": "live"
                },
                {
                  "id": "rec-live-2", "score": 100, "title": "Enter Sandman",
                  "disambiguation": "live at Moscow"
                },
                {
                  "id": "rec-live-3", "score": 100, "title": "Enter Sandman",
                  "disambiguation": "live at Seattle"
                },
                {
                  "id": "rec-bootleg-1", "score": 100, "title": "Enter Sandman",
                  "disambiguation": "live bootleg"
                },
                {
                  "id": "rec-bootleg-2", "score": 100, "title": "Enter Sandman",
                  "disambiguation": "live bootleg 1991"
                },
                {
                  "id": "rec-demo", "score": 100, "title": "Enter Sandman",
                  "disambiguation": "demo: 1990-08-13"
                },
                {
                  "id": "rec-bootleg-edited", "score": 100, "title": "Enter Sandman",
                  "disambiguation": "bootleg edited version",
                  "releases": [
                    {"status": "Bootleg", "release-group": {"primary-type": "Album"}}
                  ]
                },
                {
                  "id": "rec-studio", "score": 100, "title": "Enter Sandman",
                  "releases": [
                    {"status": "Official", "release-group": {"primary-type": "Other"}}
                  ]
                },
                {
                  "id": "rec-cover-ulrich", "score": 100, "title": "Enter Sandman (Ulrich)"
                }
              ]
            }
        """.trimIndent()

        /**
         * Single-candidate variant of [ENTER_SANDMAN_RANK_POOL]: the winning recording's Official
         * Album release carries a release-group id, pinning that
         * [MusicBrainzMapper.toTrackIdentifiers] fills `musicBrainzReleaseGroupId` from it.
         */
        private val ENTER_SANDMAN_RANK_POOL_WITH_GROUP_ID = """
            {
              "recordings": [
                {
                  "id": "rec-studio", "score": 100, "title": "Enter Sandman",
                  "releases": [
                    {
                      "status": "Official",
                      "release-group": {"id": "rg-studio", "primary-type": "Album"}
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        private val RELEASE_GROUP_WITH_RELEASES_JSON = """
            {
              "id": "rg1",
              "title": "OK Computer",
              "releases": [
                {
                  "id": "rel1",
                  "title": "OK Computer",
                  "date": "1997-06-16",
                  "country": "GB",
                  "barcode": "BARCODE123",
                  "media": [{"format": "CD", "tracks": []}],
                  "label-info": [
                    {
                      "catalog-number": "PCS 8088",
                      "label": {"name": "Parlophone"}
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        private val RECORDING_WITH_CREDITS_JSON = """
            {
              "id": "rec1",
              "title": "Paranoid Android",
              "relations": [
                {
                  "type": "vocal",
                  "target-type": "artist",
                  "artist": {"id": "ty1", "name": "Thom Yorke"},
                  "attributes": ["lead vocals"]
                },
                {
                  "type": "producer",
                  "target-type": "artist",
                  "artist": {"id": "ng1", "name": "Nigel Godrich"},
                  "attributes": []
                },
                {
                  "type": "performance",
                  "target-type": "work",
                  "work": {
                    "id": "work1",
                    "title": "Paranoid Android",
                    "relations": [
                      {
                        "type": "composer",
                        "target-type": "artist",
                        "artist": {"id": "ty1", "name": "Thom Yorke"},
                        "attributes": []
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
