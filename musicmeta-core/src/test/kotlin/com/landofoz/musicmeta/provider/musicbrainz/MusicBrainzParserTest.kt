package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzParserTest {

    @Test
    fun `parseReleases extracts all fields from search response`() {
        // Given — full MusicBrainz release search JSON with all fields populated
        val json = JSONObject(RELEASE_SEARCH_RESPONSE)

        // When — parsing releases
        val releases = MusicBrainzParser.parseReleases(json)

        // Then — all fields extracted: id, title, artist, date, country, label, group, type, score, cover, tags
        assertEquals(1, releases.size)
        val release = releases[0]
        assertEquals("abc123", release.id)
        assertEquals("OK Computer", release.title)
        assertEquals("Radiohead", release.artistCredit)
        assertEquals("1997-06-16", release.date)
        assertEquals("GB", release.country)
        assertEquals("Parlophone", release.label)
        assertEquals("group123", release.releaseGroupId)
        assertEquals("Album", release.releaseType)
        assertEquals(98, release.score)
        assertTrue(release.hasFrontCover)
        assertEquals(listOf("alternative rock"), release.tags)
    }

    @Test
    fun `parseReleases handles missing optional fields`() {
        // Given — minimal release JSON with only id, score, and title
        val json = JSONObject(
            """{"releases":[{"id":"min1","score":70,"title":"Minimal"}]}""",
        )

        // When — parsing releases
        val releases = MusicBrainzParser.parseReleases(json)

        // Then — required fields populated, all optional fields are null/empty
        assertEquals(1, releases.size)
        val release = releases[0]
        assertEquals("min1", release.id)
        assertEquals("Minimal", release.title)
        assertEquals(70, release.score)
        assertNull(release.artistCredit)
        assertNull(release.date)
        assertNull(release.country)
        assertNull(release.label)
        assertNull(release.releaseGroupId)
        assertNull(release.releaseType)
        assertTrue(release.tags.isEmpty())
    }

    @Test
    fun `parseReleases falls back to release-group tags`() {
        // Given — release has no top-level tags, but release-group has tags
        val json = JSONObject(RELEASE_WITH_GROUP_TAGS)

        // When — parsing releases
        val releases = MusicBrainzParser.parseReleases(json)

        // Then — tags sourced from release-group
        assertEquals(1, releases.size)
        assertEquals(listOf("electronic", "ambient"), releases[0].tags)
    }

    @Test
    fun `parseArtists extracts Wikidata ID from URL relations`() {
        // Given — artist JSON with a Wikidata URL relation
        val json = JSONObject(ARTIST_SEARCH_WITH_RELATIONS)

        // When — parsing artists
        val artists = MusicBrainzParser.parseArtists(json)

        // Then — Wikidata ID extracted from the URL path
        assertEquals(1, artists.size)
        assertEquals("Q188451", artists[0].wikidataId)
    }

    @Test
    fun `parseArtists extracts Wikipedia title from URL relations`() {
        // Given — artist JSON with both Wikidata and Wikipedia relations
        val json = JSONObject(ARTIST_WITH_WIKIPEDIA)

        // When — parsing artists
        val artists = MusicBrainzParser.parseArtists(json)

        // Then — Wikipedia title extracted from en.wikipedia.org URL
        assertEquals(1, artists.size)
        assertEquals("Radiohead", artists[0].wikipediaTitle)
    }

    @Test
    fun `parseArtists ignores a non-English Wikipedia URL relation`() {
        // Given — Portishead's real MusicBrainz relations: wikidata plus a fr-only wikipedia link
        val json = JSONObject(ARTIST_WITH_FRENCH_WIKIPEDIA)

        // When — parsing artists
        val artists = MusicBrainzParser.parseArtists(json)

        // Then — no title, so WikipediaProvider falls back to the Wikidata enwiki sitelink
        assertEquals(1, artists.size)
        assertNull(artists[0].wikipediaTitle)
        assertEquals("Q191352", artists[0].wikidataId)
    }

    @Test
    fun `parseArtists prefers the English Wikipedia relation over an earlier non-English one`() {
        // Given — a fr relation listed before the en relation
        val json = JSONObject(ARTIST_WITH_FRENCH_AND_ENGLISH_WIKIPEDIA)

        // When — parsing artists
        val artists = MusicBrainzParser.parseArtists(json)

        // Then — the English title wins regardless of relation order
        assertEquals("Portishead_(band)", artists[0].wikipediaTitle)
    }

    @Test
    fun `parseRecording extracts ISRCs`() {
        // Given — recording JSON with ISRCs array
        val json = JSONObject(RECORDING_SEARCH_RESPONSE)

        // When — parsing recordings
        val recordings = MusicBrainzParser.parseRecordings(json)

        // Then — recording fields and ISRCs extracted
        assertEquals(1, recordings.size)
        assertEquals("rec1", recordings[0].id)
        assertEquals("Paranoid Android", recordings[0].title)
        assertEquals(listOf("GBAYE9700100"), recordings[0].isrcs)
        assertEquals(95, recordings[0].score)
    }

    @Test
    fun `parseRecording fills artReleaseGroupId from an Official Album release, tier 1`() {
        // Given — a recording hit carrying an Official release on an Album release-group with an id
        val json = JSONObject(
            """
            {
              "recordings": [{
                "id": "rec-studio",
                "score": 100,
                "title": "Enter Sandman",
                "releases": [
                  {"status": "Official", "release-group": {"id": "rg-studio", "primary-type": "Album"}}
                ]
              }]
            }
            """.trimIndent(),
        )

        // When — parsing recordings
        val recordings = MusicBrainzParser.parseRecordings(json)

        // Then — both the strict ranking boolean and the (tier-1) art id are filled from this shape
        assertTrue(recordings[0].hasOfficialAlbumRelease)
        assertEquals("rg-studio", recordings[0].artReleaseGroupId)
    }

    @Test
    fun `parseRecording fills artReleaseGroupTitle alongside artReleaseGroupId from the same object`() {
        // Given — same tier-1 shape as above, but with a title on the release-group object
        val json = JSONObject(
            """
            {
              "recordings": [{
                "id": "rec-studio",
                "score": 100,
                "title": "Enter Sandman",
                "releases": [
                  {"status": "Official", "release-group": {"id": "rg-studio", "primary-type": "Album", "title": "Metallica"}}
                ]
              }]
            }
            """.trimIndent(),
        )

        // When
        val recordings = MusicBrainzParser.parseRecordings(json)

        // Then — the title comes from the same release-group object as the id, no extra lookup
        assertEquals("Metallica", recordings[0].artReleaseGroupTitle)
    }

    @Test
    fun `parseRecording fills lengthMs from the recording's own length field`() {
        // Given — a recording search hit carrying its own duration
        val json = JSONObject(
            """
            {
              "recordings": [{
                "id": "rec1",
                "score": 95,
                "title": "Paranoid Android",
                "length": 383000
              }]
            }
            """.trimIndent(),
        )

        // When
        val recordings = MusicBrainzParser.parseRecordings(json)

        // Then
        assertEquals(383000L, recordings[0].lengthMs)
    }

    @Test
    fun `parseRecording leaves lengthMs null when length is absent or zero`() {
        // Given — no length field at all
        val json = JSONObject(
            """
            {
              "recordings": [{
                "id": "rec1",
                "score": 95,
                "title": "Paranoid Android"
              }]
            }
            """.trimIndent(),
        )

        // When
        val recordings = MusicBrainzParser.parseRecordings(json)

        // Then
        assertNull(recordings[0].lengthMs)
    }

    @Test
    fun `parseRecording keeps a tier-2 id from an Official non-Album release, ranking bool false`() {
        // Given — live evidence shape: "Enter Sandman" under the "Metallica" album hint embeds only
        // an Official release on an "Other" (box-set) release-group, not a plain Album
        val json = JSONObject(
            """
            {
              "recordings": [{
                "id": "rec-studio",
                "score": 100,
                "title": "Enter Sandman",
                "releases": [
                  {"status": "Official", "release-group": {"id": "rg-boxset", "primary-type": "Other"}}
                ]
              }]
            }
            """.trimIndent(),
        )

        // When — parsing recordings
        val recordings = MusicBrainzParser.parseRecordings(json)

        // Then — the strict ranking boolean stays false (not a plain Album), but the art id is kept
        // via tier 2 so CAA still has a release-group to try
        assertFalse(recordings[0].hasOfficialAlbumRelease)
        assertEquals("rg-boxset", recordings[0].artReleaseGroupId)
    }

    @Test
    fun `parseRecording keeps a tier-3 id from a non-Official release with a release-group id`() {
        // Given — a recording hit with only a bootleg release, no Official release at all
        val json = JSONObject(
            """
            {
              "recordings": [{
                "id": "rec-live",
                "score": 100,
                "title": "Enter Sandman",
                "releases": [
                  {"status": "Bootleg", "release-group": {"id": "rg-live", "primary-type": "Album"}}
                ]
              }]
            }
            """.trimIndent(),
        )

        // When — parsing recordings
        val recordings = MusicBrainzParser.parseRecordings(json)

        // Then — the ranking boolean is false, but tier 3 still keeps the id: some art beats none
        assertFalse(recordings[0].hasOfficialAlbumRelease)
        assertEquals("rg-live", recordings[0].artReleaseGroupId)
    }

    @Test
    fun `parseRecording leaves artReleaseGroupId null when no release carries a release-group id`() {
        // Given — a release with no release-group object at all, so no tier can match
        val json = JSONObject(
            """
            {
              "recordings": [{
                "id": "rec-no-group",
                "score": 100,
                "title": "Enter Sandman",
                "releases": [
                  {"status": "Bootleg"}
                ]
              }]
            }
            """.trimIndent(),
        )

        // When — parsing recordings
        val recordings = MusicBrainzParser.parseRecordings(json)

        // Then — neither signal fires
        assertFalse(recordings[0].hasOfficialAlbumRelease)
        assertNull(recordings[0].artReleaseGroupId)
    }

    @Test
    fun `parseRecording keeps a tier-2 id for a Single-only recording`() {
        // Given — the only embedded release is Official on a Single release-group, not an Album
        val json = JSONObject(
            """
            {
              "recordings": [{
                "id": "rec-single",
                "score": 100,
                "title": "Enter Sandman",
                "releases": [
                  {"status": "Official", "release-group": {"id": "rg-single", "primary-type": "Single"}}
                ]
              }]
            }
            """.trimIndent(),
        )

        // When — parsing recordings
        val recordings = MusicBrainzParser.parseRecordings(json)

        // Then — not a plain Album, so ranking stays false, but the Official single's group is kept
        assertFalse(recordings[0].hasOfficialAlbumRelease)
        assertEquals("rg-single", recordings[0].artReleaseGroupId)
    }

    @Test
    fun `parseRecording keeps a tier-2 id for a Compilation-only recording`() {
        // Given — the only embedded release is Official on a Compilation release-group
        val json = JSONObject(
            """
            {
              "recordings": [{
                "id": "rec-comp",
                "score": 100,
                "title": "Enter Sandman",
                "releases": [
                  {
                    "status": "Official",
                    "release-group": {"id": "rg-comp", "primary-type": "Compilation"}
                  }
                ]
              }]
            }
            """.trimIndent(),
        )

        // When — parsing recordings
        val recordings = MusicBrainzParser.parseRecordings(json)

        // Then — not a plain Album, so ranking stays false, but the Official compilation's group is kept
        assertFalse(recordings[0].hasOfficialAlbumRelease)
        assertEquals("rg-comp", recordings[0].artReleaseGroupId)
    }

    @Test
    fun `parseRecording prefers the Official release-group over a Pseudo-Release alongside it`() {
        // Given — a Pseudo-Release entry (MB's transliteration duplicate of a real release) listed
        // before the genuine Official release; tier order must not let it win
        val json = JSONObject(
            """
            {
              "recordings": [{
                "id": "rec-studio",
                "score": 100,
                "title": "Enter Sandman",
                "releases": [
                  {"status": "Pseudo-Release", "release-group": {"id": "rg-pseudo", "primary-type": "Album"}},
                  {"status": "Official", "release-group": {"id": "rg-real", "primary-type": "Album"}}
                ]
              }]
            }
            """.trimIndent(),
        )

        // When — parsing recordings
        val recordings = MusicBrainzParser.parseRecordings(json)

        // Then — the genuine Official release's group wins tier 1, not the Pseudo-Release's
        assertTrue(recordings[0].hasOfficialAlbumRelease)
        assertEquals("rg-real", recordings[0].artReleaseGroupId)
    }

    @Test
    fun `parseReleases handles empty search results`() {
        // Given — valid JSON with an empty releases array
        val json = JSONObject("""{"releases":[]}""")

        // When — parsing releases
        val releases = MusicBrainzParser.parseReleases(json)

        // Then — returns empty list, no errors
        assertTrue(releases.isEmpty())
    }

    @Test
    fun `parseReleases handles missing releases key`() {
        // Given — JSON object with no "releases" key at all
        val json = JSONObject("""{}""")

        // When — parsing releases
        val releases = MusicBrainzParser.parseReleases(json)

        // Then — returns empty list gracefully
        assertTrue(releases.isEmpty())
    }

    @Test
    fun `parseLookupRelease parses top-level entity`() {
        // Given — a direct lookup response (not wrapped in a search array)
        val json = JSONObject(LOOKUP_RELEASE)

        // When — parsing as a lookup release
        val release = MusicBrainzParser.parseLookupRelease(json)

        // Then — id, title, and implicit score=100 extracted
        assertEquals("look1", release?.id)
        assertEquals("The Bends", release?.title)
        assertEquals(100, release?.score)
    }

    @Test
    fun `parseLookupArtist parses top-level entity with relations`() {
        // Given — a direct artist lookup response with Wikidata and Wikipedia relations
        val json = JSONObject(LOOKUP_ARTIST)

        // When — parsing as a lookup artist
        val artist = MusicBrainzParser.parseLookupArtist(json)

        // Then — all fields including external IDs extracted
        assertEquals("art1", artist?.id)
        assertEquals("Radiohead", artist?.name)
        assertEquals("Q188451", artist?.wikidataId)
        assertEquals("Radiohead", artist?.wikipediaTitle)
        assertEquals(100, artist?.score)
    }

    @Test
    fun `parseBandMembers extracts members from artist-rels`() {
        // Given -- artist lookup response with member-of-band relations
        val json = JSONObject(ARTIST_WITH_BAND_MEMBERS)

        // When -- parsing band members
        val members = MusicBrainzParser.parseBandMembers(json)

        // Then -- members extracted with name, role, dates
        assertEquals(2, members.size)
        assertEquals("Thom Yorke", members[0].name)
        assertEquals("lead vocals", members[0].role)
        assertEquals("1985", members[0].beginDate)
        assertNull(members[0].endDate)
        assertEquals(false, members[0].ended)
        assertEquals("Jonny Greenwood", members[1].name)
        assertEquals("guitar", members[1].role)
    }

    @Test
    fun `parseBandMembers ignores forward relations for solo artists`() {
        // Given -- a solo artist (like Moby) whose relations point to bands they belong to
        val json = JSONObject("""
            {
              "id": "solo1",
              "name": "Moby",
              "type": "Person",
              "relations": [
                {
                  "type": "member of band",
                  "direction": "forward",
                  "artist": {"id": "b1", "name": "Diamondsnake"},
                  "attributes": [],
                  "begin": "2004",
                  "ended": true
                },
                {
                  "type": "member of band",
                  "direction": "forward",
                  "artist": {"id": "b2", "name": "The Void Pacific Choir"},
                  "attributes": [],
                  "begin": "2016",
                  "ended": false
                }
              ]
            }
        """.trimIndent())

        // When -- parsing band members
        val members = MusicBrainzParser.parseBandMembers(json)

        // Then -- no members returned (these are bands the person belongs to, not members)
        assertTrue(members.isEmpty())
    }

    @Test
    fun `parseReleaseGroups extracts albums from browse response`() {
        // Given -- browse release-groups response
        val json = JSONObject(RELEASE_GROUPS_BROWSE)

        // When -- parsing release groups
        val groups = MusicBrainzParser.parseReleaseGroups(json)

        // Then -- release groups extracted with title, type, date
        assertEquals(2, groups.size)
        assertEquals("OK Computer", groups[0].title)
        assertEquals("Album", groups[0].primaryType)
        assertEquals("1997-06-16", groups[0].firstReleaseDate)
        assertEquals("The Bends", groups[1].title)
    }

    @Test
    fun `parseMedia extracts tracks from release`() {
        // Given -- release lookup with media array
        val json = JSONObject(RELEASE_WITH_MEDIA)

        // When -- parsing media tracks
        val tracks = MusicBrainzParser.parseMedia(json)

        // Then -- tracks extracted with title, position, length, recording id
        assertEquals(2, tracks.size)
        assertEquals("Airbag", tracks[0].title)
        assertEquals(1, tracks[0].position)
        assertEquals(284000L, tracks[0].durationMs)
        assertEquals("rec-1", tracks[0].id)
        assertEquals("Paranoid Android", tracks[1].title)
        assertEquals(2, tracks[1].position)
    }

    // Case matrix for issue: MusicBrainz tracklist flattening bonus video media into the audio
    // tracklist duplicated positions (St. Anger: CD 11 tracks + bonus DVD-Video 11 tracks -> 22).

    @Test
    fun `parseMedia keeps a single CD medium unchanged`() {
        // Given -- one CD medium, no video media in sight
        val json = JSONObject(MEDIA_SINGLE_CD)

        // When -- parsing media tracks
        val tracks = MusicBrainzParser.parseMedia(json)

        // Then -- unchanged, positions 1..n
        assertEquals(2, tracks.size)
        assertEquals(1, tracks[0].position)
        assertEquals(2, tracks[1].position)
    }

    @Test
    fun `parseMedia skips a bonus DVD-Video medium and keeps only the CD`() {
        // Given -- CD (11 tracks) + bonus DVD-Video (11 tracks) with duplicated titles, St. Anger's shape
        val json = JSONObject(MEDIA_CD_PLUS_DVD_VIDEO)

        // When -- parsing media tracks
        val tracks = MusicBrainzParser.parseMedia(json)

        // Then -- the video medium is dropped; 11 tracks, positions 1-11, no duplicates
        assertEquals(11, tracks.size)
        assertEquals((1..11).toList(), tracks.map { it.position })
        assertEquals(1, tracks.count { it.title == "Frantic" })
    }

    @Test
    fun `parseMedia keeps both CDs of a double album with cumulative positions`() {
        // Given -- 2x CD, 11 tracks each
        val json = JSONObject(MEDIA_DOUBLE_CD)

        // When -- parsing media tracks
        val tracks = MusicBrainzParser.parseMedia(json)

        // Then -- both kept, disc 2 track 1 continues at position 12
        assertEquals(22, tracks.size)
        assertEquals((1..22).toList(), tracks.map { it.position })
        assertEquals("Disc 2 Track 1", tracks[11].title)
        assertEquals(12, tracks[11].position)
    }

    @Test
    fun `parseMedia keeps a bonus audio disc with cumulative positions`() {
        // Given -- CD + a second, unlabelled bonus audio disc (deluxe edition shape)
        val json = JSONObject(MEDIA_CD_PLUS_BONUS_AUDIO)

        // When -- parsing media tracks
        val tracks = MusicBrainzParser.parseMedia(json)

        // Then -- bonus audio is content, not noise: both kept, cumulative
        assertEquals(12, tracks.size)
        assertEquals("Bonus Track", tracks[11].title)
        assertEquals(12, tracks[11].position)
    }

    @Test
    fun `parseMedia keeps a DVD-Audio medium, not fooled by the DVD substring`() {
        // Given -- CD + DVD-Audio, an audio format that merely has "DVD" in its name
        val json = JSONObject(MEDIA_CD_PLUS_DVD_AUDIO)

        // When -- parsing media tracks
        val tracks = MusicBrainzParser.parseMedia(json)

        // Then -- both kept, cumulative -- a substring "DVD" filter is the trap this row exists to kill
        assertEquals(22, tracks.size)
        assertEquals((1..22).toList(), tracks.map { it.position })
    }

    @Test
    fun `parseMedia skips a DualDisc's DVD-Video side and keeps its CD side`() {
        // Given -- a DualDisc, listed per side; only the "(DVD-Video side)" name is video
        val json = JSONObject(MEDIA_DUALDISC)

        // When -- parsing media tracks
        val tracks = MusicBrainzParser.parseMedia(json)

        // Then -- the video side is dropped, the audio side kept with unchanged positions
        assertEquals(11, tracks.size)
        assertEquals((1..11).toList(), tracks.map { it.position })
    }

    @Test
    fun `parseMedia treats a sized LaserDisc as video like the bare name`() {
        // Given -- CD + a 12" LaserDisc (MB lists LaserDisc bare and sized; all are video)
        val json = JSONObject(MEDIA_CD_PLUS_SIZED_LASERDISC)

        // When -- parsing media tracks
        val tracks = MusicBrainzParser.parseMedia(json)

        // Then -- the sized LaserDisc is dropped like a bare "LaserDisc" would be
        assertEquals(11, tracks.size)
        assertEquals((1..11).toList(), tracks.map { it.position })
    }

    @Test
    fun `parseMedia treats a bare DVD format as video`() {
        // Given -- CD + a medium whose format is the bare "DVD" (editor didn't specify a child format)
        val json = JSONObject(MEDIA_CD_PLUS_BARE_DVD)

        // When -- parsing media tracks
        val tracks = MusicBrainzParser.parseMedia(json)

        // Then -- documented judgement call: bare "DVD" is treated as video and skipped
        assertEquals(11, tracks.size)
        assertEquals((1..11).toList(), tracks.map { it.position })
    }

    @Test
    fun `parseMedia keeps all media when every medium is video`() {
        // Given -- a video-only release (concert DVD), no audio medium at all
        val json = JSONObject(MEDIA_VIDEO_ONLY)

        // When -- parsing media tracks
        val tracks = MusicBrainzParser.parseMedia(json)

        // Then -- an all-video tracklist beats an empty one
        assertEquals(3, tracks.size)
        assertEquals((1..3).toList(), tracks.map { it.position })
    }

    @Test
    fun `parseMedia keeps a medium with absent format`() {
        // Given -- a medium with no "format" key at all
        val json = JSONObject(MEDIA_ABSENT_FORMAT)

        // When -- parsing media tracks
        val tracks = MusicBrainzParser.parseMedia(json)

        // Then -- dropping unknown media silently truncates albums, so it is kept
        assertEquals(2, tracks.size)
        assertEquals((1..2).toList(), tracks.map { it.position })
    }

    @Test
    fun `parseMedia keeps both discs of a 2x LP vinyl release with cumulative positions`() {
        // Given -- 2x 12" Vinyl
        val json = JSONObject(MEDIA_DOUBLE_VINYL)

        // When -- parsing media tracks
        val tracks = MusicBrainzParser.parseMedia(json)

        // Then -- audio, both kept, cumulative positions
        assertEquals(8, tracks.size)
        assertEquals((1..8).toList(), tracks.map { it.position })
    }

    @Test
    fun `parseUrlRelations extracts external links and excludes wikidata and wikipedia`() {
        // Given -- artist with URL relations including wikidata and wikipedia
        val json = JSONObject(ARTIST_WITH_URL_RELATIONS)

        // When -- parsing URL relations
        val relations = MusicBrainzParser.parseUrlRelations(json)

        // Then -- external links extracted, wikidata and wikipedia excluded
        assertEquals(2, relations.size)
        assertEquals("official homepage", relations[0].type)
        assertEquals("https://radiohead.com", relations[0].url)
        assertEquals("bandcamp", relations[1].type)
        assertEquals("https://radiohead.bandcamp.com", relations[1].url)
    }

    @Test
    fun `extractArtistCredit concatenates names with join phrases`() {
        // Given — artist-credit array with two artists and a "feat." join phrase
        val obj = JSONObject(
            """{"artist-credit":[
                {"artist":{"name":"Massive Attack"},"joinphrase":" feat. "},
                {"artist":{"name":"Tricky"},"joinphrase":""}
            ]}""",
        )

        // When — extracting artist credit
        val credit = MusicBrainzParser.extractArtistCredit(obj)

        // Then — names concatenated with join phrase
        assertEquals("Massive Attack feat. Tricky", credit)
    }

    @Test
    fun `parseRecordingCredits extracts vocal relation with lead vocals roleCategory performance`() {
        // Given -- recording lookup response with a vocal artist-rel
        val json = JSONObject(RECORDING_WITH_VOCAL_REL)

        // When -- parsing recording credits
        val credits = MusicBrainzCreditParser.parseRecordingCredits(json)

        // Then -- vocal credit extracted with role "lead vocals" and roleCategory "performance"
        assertEquals(1, credits.size)
        assertEquals("Thom Yorke", credits[0].name)
        assertEquals("lead vocals", credits[0].role)
        assertEquals("performance", credits[0].roleCategory)
        assertEquals("ty1", credits[0].id)
    }

    @Test
    fun `parseRecordingCredits extracts instrument relation with guitar roleCategory performance`() {
        // Given -- recording with instrument artist-rel with attribute "guitar"
        val json = JSONObject(RECORDING_WITH_INSTRUMENT_REL)

        // When -- parsing recording credits
        val credits = MusicBrainzCreditParser.parseRecordingCredits(json)

        // Then -- instrument credit with role from attribute and roleCategory "performance"
        assertEquals(1, credits.size)
        assertEquals("Jonny Greenwood", credits[0].name)
        assertEquals("guitar", credits[0].role)
        assertEquals("performance", credits[0].roleCategory)
    }

    @Test
    fun `parseRecordingCredits extracts producer relation with roleCategory production`() {
        // Given -- recording with producer artist-rel
        val json = JSONObject(RECORDING_WITH_PRODUCER_REL)

        // When -- parsing recording credits
        val credits = MusicBrainzCreditParser.parseRecordingCredits(json)

        // Then -- producer credit with roleCategory "production"
        assertEquals(1, credits.size)
        assertEquals("Nigel Godrich", credits[0].name)
        assertEquals("producer", credits[0].role)
        assertEquals("production", credits[0].roleCategory)
    }

    @Test
    fun `parseRecordingCredits extracts composer from work-rels with roleCategory songwriting`() {
        // Given -- recording with a work-rel containing a composer relation
        val json = JSONObject(RECORDING_WITH_WORK_REL)

        // When -- parsing recording credits
        val credits = MusicBrainzCreditParser.parseRecordingCredits(json)

        // Then -- composer credit with roleCategory "songwriting"
        val composer = credits.find { it.role == "composer" }
        assertNotNull(composer)
        assertEquals("Thom Yorke", composer!!.name)
        assertEquals("songwriting", composer.roleCategory)
    }

    @Test
    fun `parseRecordingCredits returns empty list when no relations present`() {
        // Given -- recording with no relations
        val json = JSONObject("""{"id":"rec1","title":"Song","relations":[]}""")

        // When -- parsing recording credits
        val credits = MusicBrainzCreditParser.parseRecordingCredits(json)

        // Then -- empty list returned
        assertTrue(credits.isEmpty())
    }

    @Test
    fun `toCredits maps MusicBrainzCredit list to EnrichmentData Credits with correct fields`() {
        // Given -- a list of MusicBrainzCredit DTOs
        val dtos = listOf(
            MusicBrainzCredit(name = "Thom Yorke", id = "ty1", role = "lead vocals", roleCategory = "performance"),
            MusicBrainzCredit(name = "Nigel Godrich", id = "ng1", role = "producer", roleCategory = "production"),
        )

        // When -- mapping to EnrichmentData.Credits
        val result = MusicBrainzMapper.toCredits(dtos)

        // Then -- Credits data class with correct Credit fields
        assertEquals(2, result.credits.size)
        assertEquals("Thom Yorke", result.credits[0].name)
        assertEquals("lead vocals", result.credits[0].role)
        assertEquals("performance", result.credits[0].roleCategory)
        assertEquals(EnrichmentIdentifiers(musicBrainzId = "ty1"), result.credits[0].identifiers)
        assertEquals("Nigel Godrich", result.credits[1].name)
        assertEquals("producer", result.credits[1].role)
        assertEquals("production", result.credits[1].roleCategory)
    }

    @Test
    fun `Credits round-trip serialization works`() {
        // Given -- a Credits instance with mixed credits
        val original = EnrichmentData.Credits(
            credits = listOf(
                com.landofoz.musicmeta.Credit(
                    name = "Thom Yorke",
                    role = "lead vocals",
                    roleCategory = "performance",
                    identifiers = EnrichmentIdentifiers(musicBrainzId = "ty1"),
                ),
            ),
        )

        // When -- serializing and deserializing
        val json = Json.encodeToString(original)
        val restored = Json.decodeFromString<EnrichmentData.Credits>(json)

        // Then -- restored instance matches original
        assertEquals(original, restored)
        assertEquals("Thom Yorke", restored.credits[0].name)
        assertEquals("performance", restored.credits[0].roleCategory)
    }

    @Test
    fun `parseReleaseGroupDetail parses releases array into MusicBrainzReleaseGroupDetail`() {
        // Given -- release-group lookup response with releases array
        val json = JSONObject(RELEASE_GROUP_WITH_RELEASES)

        // When -- parsing release group detail
        val detail = MusicBrainzCreditParser.parseReleaseGroupDetail(json)

        // Then -- detail has correct id, title, and list of releases
        assertEquals("rg1", detail.id)
        assertEquals("OK Computer", detail.title)
        assertEquals(2, detail.releases.size)
    }

    @Test
    fun `parseReleaseGroupDetail extracts all fields from release object`() {
        // Given -- release-group lookup response with fully populated release
        val json = JSONObject(RELEASE_GROUP_WITH_RELEASES)

        // When -- parsing release group detail
        val detail = MusicBrainzCreditParser.parseReleaseGroupDetail(json)

        // Then -- first release has all fields extracted
        val release = detail.releases[0]
        assertEquals("rel1", release.id)
        assertEquals("OK Computer", release.title)
        assertEquals("1997-06-16", release.date)
        assertEquals("GB", release.country)
        assertEquals("BARCODE123", release.barcode)
        assertEquals("CD", release.format)
        assertEquals("Parlophone", release.label)
        assertEquals("PCS 8088", release.catalogNumber)
    }

    @Test
    fun `parseReleaseGroupDetail returns empty releases list when releases array absent`() {
        // Given -- release-group JSON with no releases key
        val json = JSONObject("""{"id":"rg1","title":"No Releases"}""")

        // When -- parsing release group detail
        val detail = MusicBrainzCreditParser.parseReleaseGroupDetail(json)

        // Then -- releases list is empty
        assertEquals("rg1", detail.id)
        assertTrue(detail.releases.isEmpty())
    }

    @Test
    fun `toReleaseEditions maps MusicBrainzEdition list to ReleaseEditions with correct fields`() {
        // Given -- a list of MusicBrainzEdition DTOs
        val editions = listOf(
            MusicBrainzEdition(
                id = "rel1", title = "OK Computer",
                date = "1997-06-16", country = "GB", barcode = "BAR001",
                format = "CD", label = "Parlophone", catalogNumber = "PCS 8088",
            ),
        )

        // When -- mapping to ReleaseEditions
        val result = MusicBrainzMapper.toReleaseEditions(
            MusicBrainzReleaseGroupDetail(id = "rg1", title = "OK Computer", releases = editions),
        )

        // Then -- ReleaseEditions contains correctly mapped ReleaseEdition
        assertEquals(1, result.editions.size)
        val edition = result.editions[0]
        assertEquals("OK Computer", edition.title)
        assertEquals("CD", edition.format)
        assertEquals("GB", edition.country)
        assertEquals(1997, edition.year)
        assertEquals("Parlophone", edition.label)
        assertEquals("PCS 8088", edition.catalogNumber)
        assertEquals("BAR001", edition.barcode)
        assertEquals(com.landofoz.musicmeta.EnrichmentIdentifiers(musicBrainzId = "rel1"), edition.identifiers)
    }

    @Test
    fun `ReleaseEditions round-trip serialization works`() {
        // Given -- a ReleaseEditions instance with one edition
        val original = com.landofoz.musicmeta.EnrichmentData.ReleaseEditions(
            editions = listOf(
                com.landofoz.musicmeta.ReleaseEdition(
                    title = "OK Computer",
                    format = "CD",
                    country = "GB",
                    year = 1997,
                    label = "Parlophone",
                    catalogNumber = "PCS 8088",
                    barcode = "BARCODE123",
                    identifiers = com.landofoz.musicmeta.EnrichmentIdentifiers(musicBrainzId = "rel1"),
                ),
            ),
        )

        // When -- serializing and deserializing
        val json = kotlinx.serialization.json.Json.encodeToString(original)
        val restored = kotlinx.serialization.json.Json.decodeFromString<com.landofoz.musicmeta.EnrichmentData.ReleaseEditions>(json)

        // Then -- restored instance matches original
        assertEquals(original, restored)
        assertEquals("OK Computer", restored.editions[0].title)
        assertEquals(1997, restored.editions[0].year)
    }

    companion object {
        private fun cdTrack(title: String, position: Int, recordingId: String) = """
            {
              "title": "$title",
              "position": $position,
              "length": 200000,
              "recording": {"id": "$recordingId"}
            }
        """.trimIndent()

        private val MEDIA_SINGLE_CD = """
            {
              "media": [{
                "format": "CD",
                "tracks": [
                  ${cdTrack("Track One", 1, "rec-1")},
                  ${cdTrack("Track Two", 2, "rec-2")}
                ]
              }]
            }
        """.trimIndent()

        private val MEDIA_CD_PLUS_DVD_VIDEO = """
            {
              "media": [
                {
                  "format": "CD",
                  "tracks": [${(1..11).joinToString(",") { cdTrack(if (it == 1) "Frantic" else "CD Track $it", it, "cd-rec-$it") }}]
                },
                {
                  "format": "DVD-Video",
                  "tracks": [${(1..11).joinToString(",") { cdTrack(if (it == 1) "Frantic" else "DVD Track $it", it, "dvd-rec-$it") }}]
                }
              ]
            }
        """.trimIndent()

        private val MEDIA_DUALDISC = """
            {
              "media": [
                {
                  "format": "DualDisc (CD side)",
                  "tracks": [${(1..11).joinToString(",") { cdTrack("CD Side Track $it", it, "cds-rec-$it") }}]
                },
                {
                  "format": "DualDisc (DVD-Video side)",
                  "tracks": [${(1..11).joinToString(",") { cdTrack("Video Side Track $it", it, "vs-rec-$it") }}]
                }
              ]
            }
        """.trimIndent()

        private val MEDIA_CD_PLUS_SIZED_LASERDISC = """
            {
              "media": [
                {
                  "format": "CD",
                  "tracks": [${(1..11).joinToString(",") { cdTrack("CD Track $it", it, "cd-rec-$it") }}]
                },
                {
                  "format": "12\" LaserDisc",
                  "tracks": [${(1..11).joinToString(",") { cdTrack("LD Track $it", it, "ld-rec-$it") }}]
                }
              ]
            }
        """.trimIndent()

        private val MEDIA_DOUBLE_CD = """
            {
              "media": [
                {
                  "format": "CD",
                  "tracks": [${(1..11).joinToString(",") { cdTrack("Disc 1 Track $it", it, "d1-rec-$it") }}]
                },
                {
                  "format": "CD",
                  "tracks": [${(1..11).joinToString(",") { cdTrack("Disc 2 Track $it", it, "d2-rec-$it") }}]
                }
              ]
            }
        """.trimIndent()

        private val MEDIA_CD_PLUS_BONUS_AUDIO = """
            {
              "media": [
                {
                  "format": "CD",
                  "tracks": [${(1..11).joinToString(",") { cdTrack("Track $it", it, "cd-rec-$it") }}]
                },
                {
                  "format": "CD",
                  "tracks": [${cdTrack("Bonus Track", 1, "bonus-rec-1")}]
                }
              ]
            }
        """.trimIndent()

        private val MEDIA_CD_PLUS_DVD_AUDIO = """
            {
              "media": [
                {
                  "format": "CD",
                  "tracks": [${(1..11).joinToString(",") { cdTrack("CD Track $it", it, "cd-rec-$it") }}]
                },
                {
                  "format": "DVD-Audio",
                  "tracks": [${(1..11).joinToString(",") { cdTrack("Hi-Res Track $it", it, "hr-rec-$it") }}]
                }
              ]
            }
        """.trimIndent()

        private val MEDIA_CD_PLUS_BARE_DVD = """
            {
              "media": [
                {
                  "format": "CD",
                  "tracks": [${(1..11).joinToString(",") { cdTrack("Track $it", it, "cd-rec-$it") }}]
                },
                {
                  "format": "DVD",
                  "tracks": [${(1..11).joinToString(",") { cdTrack("Bonus DVD Track $it", it, "dvd-rec-$it") }}]
                }
              ]
            }
        """.trimIndent()

        private val MEDIA_VIDEO_ONLY = """
            {
              "media": [{
                "format": "DVD-Video",
                "tracks": [
                  ${cdTrack("Concert Part 1", 1, "v-rec-1")},
                  ${cdTrack("Concert Part 2", 2, "v-rec-2")},
                  ${cdTrack("Concert Part 3", 3, "v-rec-3")}
                ]
              }]
            }
        """.trimIndent()

        private val MEDIA_ABSENT_FORMAT = """
            {
              "media": [{
                "tracks": [
                  ${cdTrack("Track One", 1, "rec-1")},
                  ${cdTrack("Track Two", 2, "rec-2")}
                ]
              }]
            }
        """.trimIndent()

        private val MEDIA_DOUBLE_VINYL = """
            {
              "media": [
                {
                  "format": "12\" Vinyl",
                  "tracks": [${(1..4).joinToString(",") { cdTrack("Side A Track $it", it, "lp1-rec-$it") }}]
                },
                {
                  "format": "12\" Vinyl",
                  "tracks": [${(1..4).joinToString(",") { cdTrack("Side B Track $it", it, "lp2-rec-$it") }}]
                }
              ]
            }
        """.trimIndent()

        private val RECORDING_WITH_VOCAL_REL = """
            {
              "id": "rec1",
              "title": "Paranoid Android",
              "relations": [
                {
                  "type": "vocal",
                  "target-type": "artist",
                  "artist": {"id": "ty1", "name": "Thom Yorke"},
                  "attributes": ["lead vocals"]
                }
              ]
            }
        """.trimIndent()

        private val RECORDING_WITH_INSTRUMENT_REL = """
            {
              "id": "rec1",
              "title": "Paranoid Android",
              "relations": [
                {
                  "type": "instrument",
                  "target-type": "artist",
                  "artist": {"id": "jg1", "name": "Jonny Greenwood"},
                  "attributes": ["guitar"]
                }
              ]
            }
        """.trimIndent()

        private val RECORDING_WITH_PRODUCER_REL = """
            {
              "id": "rec1",
              "title": "Paranoid Android",
              "relations": [
                {
                  "type": "producer",
                  "target-type": "artist",
                  "artist": {"id": "ng1", "name": "Nigel Godrich"},
                  "attributes": []
                }
              ]
            }
        """.trimIndent()

        private val RECORDING_WITH_WORK_REL = """
            {
              "id": "rec1",
              "title": "Paranoid Android",
              "relations": [
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

        // EXISTING FIXTURES BELOW

        private val RELEASE_SEARCH_RESPONSE = """
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

        private val RELEASE_WITH_GROUP_TAGS = """
            {
              "releases": [{
                "id": "rg1",
                "score": 90,
                "title": "Selected Ambient Works",
                "release-group": {
                  "id": "grp1",
                  "primary-type": "Album",
                  "tags": [
                    {"name": "electronic", "count": 10},
                    {"name": "ambient", "count": 8}
                  ]
                }
              }]
            }
        """.trimIndent()

        private val ARTIST_SEARCH_WITH_RELATIONS = """
            {
              "artists": [{
                "id": "art1",
                "name": "Radiohead",
                "score": 100,
                "type": "Group",
                "country": "GB",
                "life-span": {"begin": "1985", "end": null},
                "tags": [{"name": "alternative rock", "count": 10}],
                "relations": [{
                  "type": "wikidata",
                  "url": {"resource": "https://www.wikidata.org/wiki/Q188451"}
                }]
              }]
            }
        """.trimIndent()

        private val ARTIST_WITH_WIKIPEDIA = """
            {
              "artists": [{
                "id": "art1",
                "name": "Radiohead",
                "score": 100,
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

        private val ARTIST_WITH_FRENCH_WIKIPEDIA = """
            {
              "artists": [{
                "id": "8f6bd1e4-fbe1-4f50-aa9b-94c450ec0f11",
                "name": "Portishead",
                "score": 100,
                "relations": [
                  {
                    "type": "wikidata",
                    "url": {"resource": "https://www.wikidata.org/wiki/Q191352"}
                  },
                  {
                    "type": "wikipedia",
                    "url": {"resource": "https://fr.wikipedia.org/wiki/Portishead_(groupe)"}
                  }
                ]
              }]
            }
        """.trimIndent()

        private val ARTIST_WITH_FRENCH_AND_ENGLISH_WIKIPEDIA = """
            {
              "artists": [{
                "id": "8f6bd1e4-fbe1-4f50-aa9b-94c450ec0f11",
                "name": "Portishead",
                "score": 100,
                "relations": [
                  {
                    "type": "wikipedia",
                    "url": {"resource": "https://fr.wikipedia.org/wiki/Portishead_(groupe)"}
                  },
                  {
                    "type": "wikipedia",
                    "url": {"resource": "https://en.wikipedia.org/wiki/Portishead_(band)"}
                  }
                ]
              }]
            }
        """.trimIndent()

        private val RECORDING_SEARCH_RESPONSE = """
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

        private val LOOKUP_RELEASE = """
            {
              "id": "look1",
              "title": "The Bends",
              "artist-credit": [{"artist": {"name": "Radiohead"}}],
              "date": "1995-03-13",
              "country": "GB",
              "release-group": {"id": "rg1", "primary-type": "Album"}
            }
        """.trimIndent()

        private val ARTIST_WITH_BAND_MEMBERS = """
            {
              "id": "art1",
              "name": "Radiohead",
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
                },
                {
                  "type": "wikidata",
                  "target-type": "url",
                  "url": {"resource": "https://www.wikidata.org/wiki/Q188451"}
                }
              ]
            }
        """.trimIndent()

        private val RELEASE_GROUPS_BROWSE = """
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

        private val RELEASE_WITH_MEDIA = """
            {
              "id": "rel1",
              "title": "OK Computer",
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

        private val ARTIST_WITH_URL_RELATIONS = """
            {
              "id": "art1",
              "name": "Radiohead",
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

        private val RELEASE_GROUP_WITH_RELEASES = """
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
                },
                {
                  "id": "rel2",
                  "title": "OK Computer",
                  "date": "1997-07-01",
                  "country": "US"
                }
              ]
            }
        """.trimIndent()

        private val LOOKUP_ARTIST = """
            {
              "id": "art1",
              "name": "Radiohead",
              "type": "Group",
              "country": "GB",
              "tags": [{"name": "alternative rock", "count": 10}],
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
            }
        """.trimIndent()
    }
}
