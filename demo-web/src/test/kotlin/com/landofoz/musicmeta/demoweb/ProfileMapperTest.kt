package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.AlbumProfile
import com.landofoz.musicmeta.ArtistProfile
import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.DiscographyAlbum
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.IdentityMatch
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.TrackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMapperTest {

    private fun resultsOf(vararg entries: Pair<EnrichmentType, EnrichmentData>): EnrichmentResults =
        EnrichmentResults(
            raw = entries.associate { (type, data) ->
                type to EnrichmentResult.Success(type, data, provider = "test", confidence = 1.0f)
            },
            requestedTypes = entries.map { it.first }.toSet(),
            identity = null,
        )

    @Test
    fun `did-you-mean section dedupes candidates that render identically`() {
        // Given - three SUGGESTIONS candidates that differ only by an identifier the row never
        // renders (release MBID), so two of them would otherwise show as indistinguishable dupes.
        val suggestions = listOf(
            SearchCandidate(
                title = "Master of Puppets",
                artist = "Metallica",
                year = "1986",
                country = "US",
                releaseType = "Album",
                score = 90,
                thumbnailUrl = null,
                identifiers = EnrichmentIdentifiers(musicBrainzId = "release-1"),
                provider = "musicbrainz",
            ),
            SearchCandidate(
                title = "Master of Puppets",
                artist = "Metallica",
                year = "1986",
                country = "US",
                releaseType = "Album",
                score = 88,
                thumbnailUrl = null,
                identifiers = EnrichmentIdentifiers(musicBrainzId = "release-2"),
                provider = "musicbrainz",
            ),
            SearchCandidate(
                title = "Master of Puppets (Remastered)",
                artist = "Metallica",
                year = "2017",
                country = "US",
                releaseType = "Album",
                score = 80,
                thumbnailUrl = null,
                identifiers = EnrichmentIdentifiers(musicBrainzId = "release-3"),
                provider = "musicbrainz",
            ),
        )
        val results = EnrichmentResults(
            raw = emptyMap(),
            requestedTypes = emptySet(),
            identity = IdentityResolution(
                identifiers = EnrichmentIdentifiers(),
                match = IdentityMatch.SUGGESTIONS,
                matchScore = null,
                suggestions = suggestions,
            ),
        )
        val profile = AlbumProfile(title = "Master of Pupets", artist = "Metalica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the two identical-looking rows collapse to one, keeping the higher-ranked first
        val section = response.sections.first { it.key == "did_you_mean" }
        assertEquals(2, section.items.size)
        assertEquals("Master of Puppets", section.items[0].primary)
        assertEquals("Master of Puppets (Remastered)", section.items[1].primary)
    }

    @Test
    fun `did-you-mean section absent when no suggestions`() {
        // Given - an album profile whose results carry no identity resolution at all
        val results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = null)
        val profile = AlbumProfile(title = "OK Computer", artist = "Radiohead", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - no "did_you_mean" section is built
        assertNull(response.sections.firstOrNull { it.key == "did_you_mean" })
    }

    @Test
    fun `track details show duration formatted as mm-ss`() {
        // Given - a track with a resolved duration of 391 seconds
        val results = resultsOf(
            EnrichmentType.TRACK_METADATA to EnrichmentData.TrackMetadata(durationMs = 391_000L),
        )
        val profile = TrackProfile(title = "Master of Puppets", artist = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the Duration row is formatted as mm:ss
        val details = response.sections.first { it.key == "details" }
        val duration = details.items.first { it.primary == "Duration" }
        assertEquals("6:31", duration.secondary)
    }

    @Test
    fun `track album row prefers provider-confirmed title over as-entered`() {
        // Given - a track whose provider confirms the album title, requested with a typo'd album name
        val results = resultsOf(
            EnrichmentType.TRACK_METADATA to EnrichmentData.TrackMetadata(albumTitle = "Master of Puppets"),
        )
        val profile = TrackProfile(title = "Battery", artist = "Metallica", results = results)

        // When - mapping to a demo response with the mistyped requested album
        val response = profile.toDemoResponse(elapsedMs = 0, requestedAlbum = "master of puppets (typo)")

        // Then - the Album row shows the provider-confirmed title with no "as entered" meta
        val details = response.sections.first { it.key == "details" }
        val album = details.items.first { it.primary == "Album" }
        assertEquals("Master of Puppets", album.secondary)
        assertNull(album.meta)
    }

    @Test
    fun `track album row falls back to as-entered when nothing confirmed it`() {
        // Given - a track with no provider-confirmed album title
        val results = resultsOf()
        val profile = TrackProfile(title = "Battery", artist = "Metallica", results = results)

        // When - mapping to a demo response with a requested album
        val response = profile.toDemoResponse(elapsedMs = 0, requestedAlbum = "Master of Puppets")

        // Then - the Album row falls back to the requested value, tagged "as entered"
        val details = response.sections.first { it.key == "details" }
        val album = details.items.first { it.primary == "Album" }
        assertEquals("Master of Puppets", album.secondary)
        assertEquals("as entered", album.meta)
    }

    @Test
    fun `track details omit album row when neither confirmed nor entered`() {
        // Given - a track with no provider-confirmed album and no requested album
        val results = resultsOf()
        val profile = TrackProfile(title = "Battery", artist = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the details section has no Album row
        val details = response.sections.firstOrNull { it.key == "details" }
        assertNull(details?.items?.firstOrNull { it.primary == "Album" })
    }

    @Test
    fun `album summary renders description text and source when present`() {
        // Given - an album with a resolved biography text and source
        val results = resultsOf(
            EnrichmentType.ALBUM_DESCRIPTION to EnrichmentData.Biography(
                text = "A landmark thrash metal album.",
                source = "wikipedia",
            ),
        )
        val profile = AlbumProfile(title = "Master of Puppets", artist = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the summary carries the description text and its source
        assertEquals("A landmark thrash metal album.", response.summary.text)
        assertEquals("wikipedia", response.summary.textSource)
    }

    @Test
    fun `album summary text absent when no description resolved`() {
        // Given - an album with no resolved description
        val results = resultsOf()
        val profile = AlbumProfile(title = "Master of Puppets", artist = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the summary text and its source are both null
        assertNull(response.summary.text)
        assertNull(response.summary.textSource)
    }

    @Test
    fun `artist gallery includes photo alternatives labelled by provider`() {
        // Given - an artist photo with two provider alternatives, neither matching the primary URL
        val results = resultsOf(
            EnrichmentType.ARTIST_PHOTO to EnrichmentData.Artwork(
                url = "https://example.com/primary.jpg",
                alternatives = listOf(
                    ArtworkSource(provider = "fanarttv", url = "https://example.com/fanarttv.jpg"),
                    ArtworkSource(provider = "wikidata", url = "https://example.com/wikidata.jpg"),
                ),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - both alternatives appear in the gallery labelled by their provider
        assertTrue(response.gallery.any { it.url == "https://example.com/fanarttv.jpg" && it.label == "fanarttv" })
        assertTrue(response.gallery.any { it.url == "https://example.com/wikidata.jpg" && it.label == "wikidata" })
    }

    @Test
    fun `artist gallery dedupes an alternative that matches the primary photo`() {
        // Given - an artist photo whose fanarttv alternative shares the primary URL
        val results = resultsOf(
            EnrichmentType.ARTIST_PHOTO to EnrichmentData.Artwork(
                url = "https://example.com/primary.jpg",
                alternatives = listOf(
                    ArtworkSource(provider = "fanarttv", url = "https://example.com/primary.jpg"),
                    ArtworkSource(provider = "wikidata", url = "https://example.com/wikidata.jpg"),
                ),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the primary URL is not duplicated in the gallery and the distinct alternative remains
        assertEquals(0, response.gallery.count { it.url == "https://example.com/primary.jpg" })
        assertEquals(1, response.gallery.count { it.url == "https://example.com/wikidata.jpg" })
    }

    @Test
    fun `artist gallery dedupes alternatives that repeat each other and a logo-banner url`() {
        // Given - an artist logo and two photo alternatives that all share the same URL
        val results = resultsOf(
            EnrichmentType.ARTIST_LOGO to EnrichmentData.Artwork(url = "https://example.com/shared.jpg"),
            EnrichmentType.ARTIST_PHOTO to EnrichmentData.Artwork(
                url = "https://example.com/primary.jpg",
                alternatives = listOf(
                    ArtworkSource(provider = "fanarttv", url = "https://example.com/shared.jpg"),
                    ArtworkSource(provider = "wikidata", url = "https://example.com/shared.jpg"),
                ),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the shared URL appears once in the gallery, labelled as the logo
        assertEquals(1, response.gallery.count { it.url == "https://example.com/shared.jpg" })
        assertEquals("Logo", response.gallery.first { it.url == "https://example.com/shared.jpg" }.label)
    }

    @Test
    fun `artist gallery absent when nothing resolved`() {
        // Given - an artist profile with no artwork results
        val results = resultsOf()
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the gallery is empty
        assertTrue(response.gallery.isEmpty())
    }

    @Test
    fun `album gallery includes art alternatives alongside back-booklet-cdart`() {
        // Given - an album with back-cover art and primary art carrying a distinct alternative
        val results = resultsOf(
            EnrichmentType.ALBUM_ART_BACK to EnrichmentData.Artwork(url = "https://example.com/back.jpg"),
            EnrichmentType.ALBUM_ART to EnrichmentData.Artwork(
                url = "https://example.com/primary.jpg",
                alternatives = listOf(
                    ArtworkSource(provider = "coverartarchive", url = "https://example.com/caa.jpg"),
                ),
            ),
        )
        val profile = AlbumProfile(title = "Master of Puppets", artist = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - both the back cover and the art alternative appear in the gallery
        assertTrue(response.gallery.any { it.url == "https://example.com/back.jpg" && it.label == "Back" })
        assertTrue(response.gallery.any { it.url == "https://example.com/caa.jpg" && it.label == "coverartarchive" })
    }

    @Test
    fun `album gallery dedupes an alternative that matches the primary art`() {
        // Given - an album whose only art alternative shares the primary art URL
        val results = resultsOf(
            EnrichmentType.ALBUM_ART to EnrichmentData.Artwork(
                url = "https://example.com/primary.jpg",
                alternatives = listOf(
                    ArtworkSource(provider = "coverartarchive", url = "https://example.com/primary.jpg"),
                ),
            ),
        )
        val profile = AlbumProfile(title = "Master of Puppets", artist = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the gallery is empty since the only alternative duplicated the primary art
        assertTrue(response.gallery.isEmpty())
    }

    @Test
    fun `discography groups qualifier-suffixed editions of the same album into one row`() {
        // Given - three discography entries for the same album that differ only by a qualifier suffix
        val results = resultsOf(
            EnrichmentType.ARTIST_DISCOGRAPHY to EnrichmentData.Discography(
                albums = listOf(
                    DiscographyAlbum(title = "Master Of Puppets", year = "1986", type = "album"),
                    DiscographyAlbum(title = "Master Of Puppets (Remastered)", year = "1986", type = "album"),
                    DiscographyAlbum(
                        title = "Master Of Puppets (Deluxe Box Set / Remastered)",
                        year = "2017",
                        type = "album",
                    ),
                ),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the editions collapse into one row keyed on the shared title and type
        val discography = response.sections.first { it.key == "discography" }
        assertEquals(1, discography.items.size)
        val row = discography.items.first()
        assertEquals("Master Of Puppets", row.primary)
        assertEquals("1986", row.secondary)
        assertEquals("album · 3 editions", row.meta)
    }

    @Test
    fun `discography preserves a genuine parenthetical title as its own row`() {
        // Given - an album and a track whose title has a parenthetical that is part of the real name, not a qualifier
        val results = resultsOf(
            EnrichmentType.ARTIST_DISCOGRAPHY to EnrichmentData.Discography(
                albums = listOf(
                    DiscographyAlbum(title = "Master Of Puppets", year = "1986", type = "album"),
                    DiscographyAlbum(title = "Welcome Home (Sanitarium)", year = "1986", type = "track"),
                ),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - both entries stay as separate rows with their own type
        val discography = response.sections.first { it.key == "discography" }
        assertEquals(2, discography.items.size)
        assertTrue(discography.items.any { it.primary == "Master Of Puppets" })
        assertTrue(discography.items.any { it.primary == "Welcome Home (Sanitarium)" })
        assertTrue(discography.items.all { it.meta == "album" || it.meta == "track" })
    }

    @Test
    fun `discography picks the earliest year across editions`() {
        // Given - three editions of the same album released in different years
        val results = resultsOf(
            EnrichmentType.ARTIST_DISCOGRAPHY to EnrichmentData.Discography(
                albums = listOf(
                    DiscographyAlbum(title = "Load (Deluxe Box Set / Remastered)", year = "2025", type = "album"),
                    DiscographyAlbum(title = "Load (Remastered)", year = "1996", type = "album"),
                    DiscographyAlbum(title = "Load", year = "1996", type = "album"),
                ),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the grouped row's year is the earliest of the three editions
        val row = response.sections.first { it.key == "discography" }.items.first()
        assertEquals("1996", row.secondary)
    }

    @Test
    fun `discography leaves a single-edition album's row unchanged`() {
        // Given - a discography with a single album entry that has an image and no other editions
        val results = resultsOf(
            EnrichmentType.ARTIST_DISCOGRAPHY to EnrichmentData.Discography(
                albums = listOf(
                    DiscographyAlbum(
                        title = "Ride The Lightning",
                        year = "1984",
                        type = "album",
                        thumbnailUrl = "https://example.com/rtl.jpg",
                    ),
                ),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the single edition's row keeps its own title, type, and image unchanged
        val row = response.sections.first { it.key == "discography" }.items.first()
        assertEquals("Ride The Lightning", row.primary)
        assertEquals("album", row.meta)
        assertEquals("https://example.com/rtl.jpg", row.imageUrl)
    }

    @Test
    fun `discography groups case-insensitively and by first occurrence order`() {
        // Given - three album entries where two share a title differing only by case and a qualifier
        val results = resultsOf(
            EnrichmentType.ARTIST_DISCOGRAPHY to EnrichmentData.Discography(
                albums = listOf(
                    DiscographyAlbum(title = "kill 'em all (remastered)", year = "2016", type = "album"),
                    DiscographyAlbum(title = "Ride The Lightning", year = "1984", type = "album"),
                    DiscographyAlbum(title = "Kill 'Em All", year = "1983", type = "album"),
                ),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the case-differing editions group together, keeping the source's first-occurrence order
        val items = response.sections.first { it.key == "discography" }.items
        assertEquals(2, items.size)
        // First occurrence in the source list was "kill 'em all (remastered)", so that group leads.
        assertEquals("kill 'em all", items[0].primary.lowercase())
        assertEquals("album · 2 editions", items[0].meta)
        assertEquals("Ride The Lightning", items[1].primary)
    }

    @Test
    fun `discography groups a no-separator multi-kind qualifier tail like Remastered Deluxe Box Set`() {
        // Given - three editions of the same album with a multi-word qualifier tail and no separator
        val results = resultsOf(
            EnrichmentType.ARTIST_DISCOGRAPHY to EnrichmentData.Discography(
                albums = listOf(
                    DiscographyAlbum(title = "Metallica (Remastered Deluxe Box Set)", year = "2021", type = "album"),
                    DiscographyAlbum(title = "Metallica (Remastered 2021)", year = "1991", type = "album"),
                    DiscographyAlbum(title = "Metallica", year = "1991", type = "album"),
                ),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the multi-kind qualifier tail is stripped and all three editions collapse into one row
        val discography = response.sections.first { it.key == "discography" }
        assertEquals(1, discography.items.size)
        val row = discography.items.first()
        assertEquals("Metallica", row.primary)
        assertEquals("1991", row.secondary)
        assertEquals("album · 3 editions", row.meta)
    }

    @Test
    fun `discography does not strip a qualifier-shaped phrase containing a non-vocabulary word`() {
        // Given - a base album and a variant whose parenthetical mixes a qualifier word with a non-vocabulary word
        val results = resultsOf(
            EnrichmentType.ARTIST_DISCOGRAPHY to EnrichmentData.Discography(
                albums = listOf(
                    DiscographyAlbum(title = "Somewhere", year = "1988", type = "album"),
                    DiscographyAlbum(title = "Somewhere (Live Remastered)", year = "2016", type = "album"),
                ),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the two titles stay as separate rows, since the qualifier phrase is not stripped
        val discography = response.sections.first { it.key == "discography" }
        assertEquals(2, discography.items.size)
        assertTrue(discography.items.any { it.primary == "Somewhere" })
        assertTrue(discography.items.any { it.primary == "Somewhere (Live Remastered)" })
    }

    @Test
    fun `discography does not strip a negated qualifier like Not Remastered`() {
        // Given - a base album and a variant whose parenthetical negates the qualifier word
        val results = resultsOf(
            EnrichmentType.ARTIST_DISCOGRAPHY to EnrichmentData.Discography(
                albums = listOf(
                    DiscographyAlbum(title = "Album", year = "1990", type = "album"),
                    DiscographyAlbum(title = "Album (Not Remastered)", year = "2020", type = "album"),
                ),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the two titles stay as separate rows, since the negated qualifier is not stripped
        val discography = response.sections.first { it.key == "discography" }
        assertEquals(2, discography.items.size)
        assertTrue(discography.items.any { it.primary == "Album" })
        assertTrue(discography.items.any { it.primary == "Album (Not Remastered)" })
    }

    @Test
    fun `discography keeps a same-title different-type release as its own row`() {
        // Given - two releases sharing a title and year but with different release types
        val results = resultsOf(
            EnrichmentType.ARTIST_DISCOGRAPHY to EnrichmentData.Discography(
                albums = listOf(
                    DiscographyAlbum(title = "Load", year = "1996", type = "album"),
                    DiscographyAlbum(title = "Load", year = "1996", type = "single"),
                ),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the two releases stay as separate rows, one per type
        val discography = response.sections.first { it.key == "discography" }
        assertEquals(2, discography.items.size)
        assertEquals(setOf("album", "single"), discography.items.map { it.meta }.toSet())
        assertTrue(discography.items.all { it.primary == "Load" })
    }

    @Test
    fun `discography does not blur the title-type boundary when grouping`() {
        // Given - a "live album" release and a differently-titled "album" release that could be confused as editions
        val results = resultsOf(
            EnrichmentType.ARTIST_DISCOGRAPHY to EnrichmentData.Discography(
                albums = listOf(
                    DiscographyAlbum(title = "Ride", year = "1984", type = "live album"),
                    DiscographyAlbum(title = "Ride Live", year = "1985", type = "album"),
                ),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the two releases stay as separate rows, in their source order
        val discography = response.sections.first { it.key == "discography" }
        assertEquals(2, discography.items.size)
        assertEquals(listOf("Ride", "Ride Live"), discography.items.map { it.primary })
    }

    private fun identityOf(match: IdentityMatch): com.landofoz.musicmeta.IdentityResolution =
        com.landofoz.musicmeta.IdentityResolution(
            identifiers = EnrichmentIdentifiers(),
            match = match,
            matchScore = null,
            suggestions = emptyList(),
        )

    @Test
    fun `artist summary identityResolved false on BEST_EFFORT`() {
        // Given - an artist profile whose identity resolution matched with BEST_EFFORT
        val results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identityOf(IdentityMatch.BEST_EFFORT))
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the summary reports identity as not resolved
        assertEquals(false, response.summary.identityResolved)
    }

    @Test
    fun `artist summary identityResolved false on SUGGESTIONS`() {
        // Given - an artist profile whose identity resolution matched with SUGGESTIONS
        val results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identityOf(IdentityMatch.SUGGESTIONS))
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the summary reports identity as not resolved
        assertEquals(false, response.summary.identityResolved)
    }

    @Test
    fun `artist summary identityResolved false on UNVERIFIED and verdict passes through`() {
        // Given - an artist profile whose identity resolution matched with UNVERIFIED
        val results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identityOf(IdentityMatch.UNVERIFIED))
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the summary reports identity as not resolved and passes the UNVERIFIED verdict through
        assertEquals(false, response.summary.identityResolved)
        assertEquals("UNVERIFIED", response.summary.identityVerdict)
    }

    @Test
    fun `SUGGESTIONS with no usable suggestions still carries identityVerdict, not just section absence`() {
        // Given - identityOf() builds with empty suggestions, so no "did_you_mean" section gets built —
        // the frontend must not infer the verdict from section presence (it would land on
        // "Best-effort match" instead of "No exact match" if it did).
        val results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identityOf(IdentityMatch.SUGGESTIONS))
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the "did_you_mean" section is absent but the verdict and resolved flag are still carried
        assertNull(response.sections.firstOrNull { it.key == "did_you_mean" })
        assertEquals("SUGGESTIONS", response.summary.identityVerdict)
        assertEquals(false, response.summary.identityResolved)
    }

    @Test
    fun `artist summary identityResolved true on RESOLVED and on null identity`() {
        // Given - one artist profile resolved with IdentityMatch.RESOLVED and one with no identity at all
        val resolved = ArtistProfile(
            name = "Metallica",
            results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identityOf(IdentityMatch.RESOLVED)),
        ).toDemoResponse(elapsedMs = 0)
        val noIdentity = ArtistProfile(
            name = "Metallica",
            results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = null),
        ).toDemoResponse(elapsedMs = 0)

        // When - mapping both to demo responses (done above as part of construction)

        // Then - both report identity as resolved
        assertEquals(true, resolved.summary.identityResolved)
        assertEquals(true, noIdentity.summary.identityResolved)
    }

    @Test
    fun `album summary identityResolved false on BEST_EFFORT and SUGGESTIONS`() {
        // Given - one album profile resolved with BEST_EFFORT and one with SUGGESTIONS
        val bestEffort = AlbumProfile(
            title = "Master of Puppets",
            artist = "Metallica",
            results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identityOf(IdentityMatch.BEST_EFFORT)),
        ).toDemoResponse(elapsedMs = 0)
        // When - mapping the BEST_EFFORT and SUGGESTIONS profiles to demo responses
        val suggestions = AlbumProfile(
            title = "Master of Puppets",
            artist = "Metallica",
            results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identityOf(IdentityMatch.SUGGESTIONS)),
        ).toDemoResponse(elapsedMs = 0)

        // Then - both report identity as not resolved
        assertEquals(false, bestEffort.summary.identityResolved)
        assertEquals(false, suggestions.summary.identityResolved)
    }

    @Test
    fun `album summary identityResolved true on RESOLVED and on null identity`() {
        // Given - one album profile resolved with RESOLVED and one with no identity at all
        val resolved = AlbumProfile(
            title = "Master of Puppets",
            artist = "Metallica",
            results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identityOf(IdentityMatch.RESOLVED)),
        ).toDemoResponse(elapsedMs = 0)
        // When - mapping both profiles to demo responses
        val noIdentity = AlbumProfile(
            title = "Master of Puppets",
            artist = "Metallica",
            results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = null),
        ).toDemoResponse(elapsedMs = 0)

        // Then - both report identity as resolved
        assertEquals(true, resolved.summary.identityResolved)
        assertEquals(true, noIdentity.summary.identityResolved)
    }

    @Test
    fun `track summary suppresses preview fields when identity not RESOLVED`() {
        // Given - one track profile resolved with BEST_EFFORT and one with SUGGESTIONS
        val bestEffort = TrackProfile(
            title = "Enter Sandman",
            artist = "Metallica",
            results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identityOf(IdentityMatch.BEST_EFFORT)),
        ).toDemoResponse(elapsedMs = 0)
        // When - mapping both profiles to demo responses
        val suggestions = TrackProfile(
            title = "Enter Sandman",
            artist = "Metallica",
            results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identityOf(IdentityMatch.SUGGESTIONS)),
        ).toDemoResponse(elapsedMs = 0)

        // Then - both suppress the preview title and artist alongside a not-resolved identity
        assertEquals(false, bestEffort.summary.identityResolved)
        assertNull(bestEffort.summary.previewTitle)
        assertNull(bestEffort.summary.previewArtist)
        assertEquals(false, suggestions.summary.identityResolved)
        assertNull(suggestions.summary.previewTitle)
        assertNull(suggestions.summary.previewArtist)
    }

    @Test
    fun `track summary populates preview fields when identity RESOLVED or absent`() {
        // Given - one track profile resolved with RESOLVED and one with no identity at all
        val resolved = TrackProfile(
            title = "Enter Sandman",
            artist = "Metallica",
            results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identityOf(IdentityMatch.RESOLVED)),
        ).toDemoResponse(elapsedMs = 0)
        // When - mapping both profiles to demo responses
        val noIdentity = TrackProfile(
            title = "Enter Sandman",
            artist = "Metallica",
            results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = null),
        ).toDemoResponse(elapsedMs = 0)

        // Then - both populate the preview title and artist alongside a resolved identity
        assertEquals(true, resolved.summary.identityResolved)
        assertEquals("Enter Sandman", resolved.summary.previewTitle)
        assertEquals("Metallica", resolved.summary.previewArtist)
        assertEquals(true, noIdentity.summary.identityResolved)
        assertEquals("Enter Sandman", noIdentity.summary.previewTitle)
        assertEquals("Metallica", noIdentity.summary.previewArtist)
    }

    private fun radioSection(): Section =
        Section("artist_radio", "Radio (from artist)", listOf(SectionItem(primary = "Some Song")))

    @Test
    fun `album drops artist_radio section when identity not resolved`() {
        // Given - an album profile whose identity resolution matched with SUGGESTIONS
        val results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identityOf(IdentityMatch.SUGGESTIONS))
        val profile = AlbumProfile(title = "Master of Puppets", artist = "Metallica", results = results)

        // When - mapping to a demo response with an artist_radio section supplied
        val response = profile.toDemoResponse(elapsedMs = 0, artistRadio = radioSection())

        // Then - the artist_radio section is dropped
        assertNull(response.sections.firstOrNull { it.key == "artist_radio" })
    }

    @Test
    fun `album keeps artist_radio section when identity resolved`() {
        // Given - an album profile whose identity resolution matched with RESOLVED
        val results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identityOf(IdentityMatch.RESOLVED))
        val profile = AlbumProfile(title = "Master of Puppets", artist = "Metallica", results = results)

        // When - mapping to a demo response with an artist_radio section supplied
        val response = profile.toDemoResponse(elapsedMs = 0, artistRadio = radioSection())

        // Then - the artist_radio section is kept
        assertEquals("artist_radio", response.sections.first { it.key == "artist_radio" }.key)
    }

    @Test
    fun `track drops artist_radio section when identity not resolved`() {
        // Given - a track profile whose identity resolution matched with SUGGESTIONS
        val results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identityOf(IdentityMatch.SUGGESTIONS))
        val profile = TrackProfile(title = "Enter Sandman", artist = "Metallica", results = results)

        // When - mapping to a demo response with an artist_radio section supplied
        val response = profile.toDemoResponse(elapsedMs = 0, artistRadio = radioSection())

        // Then - the artist_radio section is dropped
        assertNull(response.sections.firstOrNull { it.key == "artist_radio" })
    }

    @Test
    fun `track keeps artist_radio section when identity resolved`() {
        // Given - a track profile whose identity resolution matched with RESOLVED
        val results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = identityOf(IdentityMatch.RESOLVED))
        val profile = TrackProfile(title = "Enter Sandman", artist = "Metallica", results = results)

        // When - mapping to a demo response with an artist_radio section supplied
        val response = profile.toDemoResponse(elapsedMs = 0, artistRadio = radioSection())

        // Then - the artist_radio section is kept
        assertEquals("artist_radio", response.sections.first { it.key == "artist_radio" }.key)
    }

    @Test
    fun `identifiers surface namespaced ids with the prescribed labels`() {
        // Given - an artist whose identity resolution carries Discogs and Spotify ids alongside the resolved MBID
        val identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-1")
            .with(IdentifierNamespace.DISCOGS_ARTIST, "discogs-1")
            .with(IdentifierNamespace.SPOTIFY_ARTIST, "spotify-1")
        val results = EnrichmentResults(
            raw = emptyMap(),
            requestedTypes = emptySet(),
            identity = com.landofoz.musicmeta.IdentityResolution(
                identifiers = identifiers,
                match = IdentityMatch.RESOLVED,
                matchScore = null,
                suggestions = emptyList(),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the Discogs and Spotify ids appear labelled, alongside the struct-field MBID
        val hits = response.meta.identifiers
        assertTrue(hits.any { it.label == "MBID" && it.value == "mbid-1" })
        assertTrue(hits.any { it.label == "Discogs" && it.value == "discogs-1" })
        assertTrue(hits.any { it.label == "Spotify" && it.value == "spotify-1" })
    }

    @Test
    fun `identifiers dedupe a namespaced id equal to an already-emitted struct field`() {
        // Given - an artist whose resolved MBID is also present under the MUSICBRAINZ_ARTIST namespace with the same value
        val identifiers = EnrichmentIdentifiers(musicBrainzId = "mbid-1")
            .with(IdentifierNamespace.MUSICBRAINZ_ARTIST, "mbid-1")
        val results = EnrichmentResults(
            raw = emptyMap(),
            requestedTypes = emptySet(),
            identity = com.landofoz.musicmeta.IdentityResolution(
                identifiers = identifiers,
                match = IdentityMatch.RESOLVED,
                matchScore = null,
                suggestions = emptyList(),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the duplicate value collapses to the single struct-field entry
        val hits = response.meta.identifiers
        assertEquals(1, hits.count { it.value == "mbid-1" })
    }
}
