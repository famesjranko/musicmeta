package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.AlbumProfile
import com.landofoz.musicmeta.ArtistProfile
import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
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
        // Given — three SUGGESTIONS candidates that differ only by an identifier the row never
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

        // When
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then — the two identical-looking rows collapse to one, keeping the higher-ranked first
        val section = response.sections.first { it.key == "did_you_mean" }
        assertEquals(2, section.items.size)
        assertEquals("Master of Puppets", section.items[0].primary)
        assertEquals("Master of Puppets (Remastered)", section.items[1].primary)
    }

    @Test
    fun `did-you-mean section absent when no suggestions`() {
        val results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = null)
        val profile = AlbumProfile(title = "OK Computer", artist = "Radiohead", results = results)

        val response = profile.toDemoResponse(elapsedMs = 0)

        assertNull(response.sections.firstOrNull { it.key == "did_you_mean" })
    }

    @Test
    fun `track details show duration formatted as mm-ss`() {
        val results = resultsOf(
            EnrichmentType.TRACK_METADATA to EnrichmentData.TrackMetadata(durationMs = 391_000L),
        )
        val profile = TrackProfile(title = "Master of Puppets", artist = "Metallica", results = results)

        val response = profile.toDemoResponse(elapsedMs = 0)

        val details = response.sections.first { it.key == "details" }
        val duration = details.items.first { it.primary == "Duration" }
        assertEquals("6:31", duration.secondary)
    }

    @Test
    fun `track album row prefers provider-confirmed title over as-entered`() {
        val results = resultsOf(
            EnrichmentType.TRACK_METADATA to EnrichmentData.TrackMetadata(albumTitle = "Master of Puppets"),
        )
        val profile = TrackProfile(title = "Battery", artist = "Metallica", results = results)

        val response = profile.toDemoResponse(elapsedMs = 0, requestedAlbum = "master of puppets (typo)")

        val details = response.sections.first { it.key == "details" }
        val album = details.items.first { it.primary == "Album" }
        assertEquals("Master of Puppets", album.secondary)
        assertNull(album.meta)
    }

    @Test
    fun `track album row falls back to as-entered when nothing confirmed it`() {
        val results = resultsOf()
        val profile = TrackProfile(title = "Battery", artist = "Metallica", results = results)

        val response = profile.toDemoResponse(elapsedMs = 0, requestedAlbum = "Master of Puppets")

        val details = response.sections.first { it.key == "details" }
        val album = details.items.first { it.primary == "Album" }
        assertEquals("Master of Puppets", album.secondary)
        assertEquals("as entered", album.meta)
    }

    @Test
    fun `track details omit album row when neither confirmed nor entered`() {
        val results = resultsOf()
        val profile = TrackProfile(title = "Battery", artist = "Metallica", results = results)

        val response = profile.toDemoResponse(elapsedMs = 0)

        val details = response.sections.firstOrNull { it.key == "details" }
        assertNull(details?.items?.firstOrNull { it.primary == "Album" })
    }

    @Test
    fun `album summary renders description text and source when present`() {
        val results = resultsOf(
            EnrichmentType.ALBUM_DESCRIPTION to EnrichmentData.Biography(
                text = "A landmark thrash metal album.",
                source = "wikipedia",
            ),
        )
        val profile = AlbumProfile(title = "Master of Puppets", artist = "Metallica", results = results)

        val response = profile.toDemoResponse(elapsedMs = 0)

        assertEquals("A landmark thrash metal album.", response.summary.text)
        assertEquals("wikipedia", response.summary.textSource)
    }

    @Test
    fun `album summary text absent when no description resolved`() {
        val results = resultsOf()
        val profile = AlbumProfile(title = "Master of Puppets", artist = "Metallica", results = results)

        val response = profile.toDemoResponse(elapsedMs = 0)

        assertNull(response.summary.text)
        assertNull(response.summary.textSource)
    }

    @Test
    fun `artist gallery includes photo alternatives labelled by provider`() {
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

        val response = profile.toDemoResponse(elapsedMs = 0)

        assertTrue(response.gallery.any { it.url == "https://example.com/fanarttv.jpg" && it.label == "fanarttv" })
        assertTrue(response.gallery.any { it.url == "https://example.com/wikidata.jpg" && it.label == "wikidata" })
    }

    @Test
    fun `artist gallery dedupes an alternative that matches the primary photo`() {
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

        val response = profile.toDemoResponse(elapsedMs = 0)

        assertEquals(0, response.gallery.count { it.url == "https://example.com/primary.jpg" })
        assertEquals(1, response.gallery.count { it.url == "https://example.com/wikidata.jpg" })
    }

    @Test
    fun `artist gallery dedupes alternatives that repeat each other and a logo-banner url`() {
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

        val response = profile.toDemoResponse(elapsedMs = 0)

        assertEquals(1, response.gallery.count { it.url == "https://example.com/shared.jpg" })
        assertEquals("Logo", response.gallery.first { it.url == "https://example.com/shared.jpg" }.label)
    }

    @Test
    fun `artist gallery absent when nothing resolved`() {
        val results = resultsOf()
        val profile = ArtistProfile(name = "Metallica", results = results)

        val response = profile.toDemoResponse(elapsedMs = 0)

        assertTrue(response.gallery.isEmpty())
    }

    @Test
    fun `album gallery includes art alternatives alongside back-booklet-cdart`() {
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

        val response = profile.toDemoResponse(elapsedMs = 0)

        assertTrue(response.gallery.any { it.url == "https://example.com/back.jpg" && it.label == "Back" })
        assertTrue(response.gallery.any { it.url == "https://example.com/caa.jpg" && it.label == "coverartarchive" })
    }

    @Test
    fun `album gallery dedupes an alternative that matches the primary art`() {
        val results = resultsOf(
            EnrichmentType.ALBUM_ART to EnrichmentData.Artwork(
                url = "https://example.com/primary.jpg",
                alternatives = listOf(
                    ArtworkSource(provider = "coverartarchive", url = "https://example.com/primary.jpg"),
                ),
            ),
        )
        val profile = AlbumProfile(title = "Master of Puppets", artist = "Metallica", results = results)

        val response = profile.toDemoResponse(elapsedMs = 0)

        assertTrue(response.gallery.isEmpty())
    }
}
