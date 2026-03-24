package com.landofoz.musicmeta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {

    private fun success(type: EnrichmentType, data: EnrichmentData, provider: String = "test") =
        EnrichmentResult.Success(type, data, provider, 0.9f)

    // --- ArtistProfile ---

    @Test fun `artist profile exposes all fields from results`() {
        val results = EnrichmentResults(
            raw = mapOf(
                EnrichmentType.ARTIST_PHOTO to success(EnrichmentType.ARTIST_PHOTO,
                    EnrichmentData.Artwork(url = "https://example.com/photo.jpg")),
                EnrichmentType.ARTIST_BIO to success(EnrichmentType.ARTIST_BIO,
                    EnrichmentData.Biography(text = "A band from Oxford", source = "wikipedia", language = "en")),
                EnrichmentType.GENRE to success(EnrichmentType.GENRE,
                    EnrichmentData.Metadata(genreTags = listOf(GenreTag("rock", 0.9f)))),
                EnrichmentType.BAND_MEMBERS to success(EnrichmentType.BAND_MEMBERS,
                    EnrichmentData.BandMembers(listOf(BandMember("Thom Yorke", "vocals")))),
                EnrichmentType.ARTIST_DISCOGRAPHY to success(EnrichmentType.ARTIST_DISCOGRAPHY,
                    EnrichmentData.Discography(listOf(DiscographyAlbum("OK Computer", year = "1997")))),
                EnrichmentType.ARTIST_TOP_TRACKS to success(EnrichmentType.ARTIST_TOP_TRACKS,
                    EnrichmentData.TopTracks(listOf(TopTrack("Creep", "Radiohead", rank = 1)))),
                EnrichmentType.ARTIST_RADIO to success(EnrichmentType.ARTIST_RADIO,
                    EnrichmentData.RadioPlaylist(listOf(RadioTrack("Lucky", "Radiohead")))),
            ),
            requestedTypes = EnrichmentRequest.DEFAULT_ARTIST_TYPES,
            identity = IdentityResolution(
                identifiers = EnrichmentIdentifiers(musicBrainzId = "abc-123"),
                match = IdentityMatch.RESOLVED,
                matchScore = 95,
            ),
        )

        val profile = ArtistProfile("Radiohead", results)

        assertEquals("Radiohead", profile.name)
        assertEquals("abc-123", profile.identifiers.musicBrainzId)
        assertEquals(IdentityMatch.RESOLVED, profile.identityMatch)
        assertEquals(95, profile.identityMatchScore)
        assertNotNull(profile.photo)
        assertEquals("https://example.com/photo.jpg", profile.photo!!.url)
        assertEquals("A band from Oxford", profile.bio?.text)
        assertEquals(1, profile.genres.size)
        assertEquals("rock", profile.genres[0].name)
        assertEquals(1, profile.members.size)
        assertEquals("Thom Yorke", profile.members[0].name)
        assertEquals(1, profile.discography.size)
        assertNotNull(profile.topTracks)
        assertNotNull(profile.radio)
    }

    @Test fun `artist profile returns empty collections for missing types`() {
        val results = EnrichmentResults(raw = emptyMap(), requestedTypes = emptySet(), identity = null)
        val profile = ArtistProfile("Unknown", results)

        assertTrue(profile.genres.isEmpty())
        assertTrue(profile.members.isEmpty())
        assertTrue(profile.links.isEmpty())
        assertTrue(profile.discography.isEmpty())
        assertTrue(profile.timeline.isEmpty())
        assertTrue(profile.genreDiscovery.isEmpty())
        assertTrue(profile.suggestions.isEmpty())
        assertNull(profile.photo)
        assertNull(profile.bio)
        assertNull(profile.popularity)
        assertNull(profile.identityMatch)
    }

    @Test fun `artist profile surfaces suggestions from identity resolution`() {
        val candidates = listOf(
            SearchCandidate("Bush", "Bush", null, "GB", "Group", 80, null,
                EnrichmentIdentifiers(musicBrainzId = "mbid-1"), "musicbrainz", "British rock band"),
            SearchCandidate("Bush", "Bush", null, "CA", "Group", 75, null,
                EnrichmentIdentifiers(musicBrainzId = "mbid-2"), "musicbrainz", "Canadian band"),
        )
        val results = EnrichmentResults(
            raw = mapOf(EnrichmentType.GENRE to EnrichmentResult.NotFound(EnrichmentType.GENRE, "engine",
                suggestions = candidates, identityMatch = IdentityMatch.SUGGESTIONS)),
            requestedTypes = EnrichmentRequest.DEFAULT_ARTIST_TYPES,
            identity = IdentityResolution(
                identifiers = EnrichmentIdentifiers(),
                match = IdentityMatch.SUGGESTIONS,
                matchScore = null,
                suggestions = candidates,
            ),
        )

        val profile = ArtistProfile("Bush", results)

        assertEquals(IdentityMatch.SUGGESTIONS, profile.identityMatch)
        assertEquals(2, profile.suggestions.size)
        assertEquals("British rock band", profile.suggestions[0].disambiguation)
    }

    // --- AlbumProfile ---

    @Test fun `album profile exposes metadata and tracklist`() {
        val results = EnrichmentResults(
            raw = mapOf(
                EnrichmentType.ALBUM_ART to success(EnrichmentType.ALBUM_ART,
                    EnrichmentData.Artwork(url = "https://example.com/cover.jpg")),
                EnrichmentType.LABEL to success(EnrichmentType.LABEL,
                    EnrichmentData.Metadata(label = "Parlophone")),
                EnrichmentType.ALBUM_TRACKS to success(EnrichmentType.ALBUM_TRACKS,
                    EnrichmentData.Tracklist(listOf(
                        TrackInfo("Airbag", 1, 284000),
                        TrackInfo("Paranoid Android", 2, 383000),
                    ))),
                EnrichmentType.RELEASE_TYPE to success(EnrichmentType.RELEASE_TYPE,
                    EnrichmentData.Metadata(releaseType = "Album")),
            ),
            requestedTypes = EnrichmentRequest.DEFAULT_ALBUM_TYPES,
            identity = null,
        )

        val profile = AlbumProfile("OK Computer", "Radiohead", results)

        assertEquals("OK Computer", profile.title)
        assertEquals("Radiohead", profile.artist)
        assertNotNull(profile.artwork)
        assertEquals("Parlophone", profile.label)
        assertEquals("Album", profile.releaseType)
        assertEquals(2, profile.tracks.size)
        assertEquals("Airbag", profile.tracks[0].title)
    }

    // --- TrackProfile ---

    @Test fun `track profile exposes lyrics and credits`() {
        val results = EnrichmentResults(
            raw = mapOf(
                EnrichmentType.LYRICS_SYNCED to success(EnrichmentType.LYRICS_SYNCED,
                    EnrichmentData.Lyrics(syncedLyrics = "[00:01]But I'm a creep", plainLyrics = "But I'm a creep")),
                EnrichmentType.CREDITS to success(EnrichmentType.CREDITS,
                    EnrichmentData.Credits(listOf(Credit("Thom Yorke", "vocals", "performer")))),
                EnrichmentType.TRACK_POPULARITY to success(EnrichmentType.TRACK_POPULARITY,
                    EnrichmentData.Popularity(listenCount = 500000)),
            ),
            requestedTypes = EnrichmentRequest.DEFAULT_TRACK_TYPES,
            identity = null,
        )

        val profile = TrackProfile("Creep", "Radiohead", results)

        assertEquals("Creep", profile.title)
        assertNotNull(profile.lyrics)
        assertEquals("[00:01]But I'm a creep", profile.lyrics!!.syncedLyrics)
        assertNotNull(profile.credits)
        assertEquals(1, profile.credits!!.credits.size)
        assertNotNull(profile.popularity)
        assertEquals(500000L, profile.popularity!!.listenCount)
    }
}
