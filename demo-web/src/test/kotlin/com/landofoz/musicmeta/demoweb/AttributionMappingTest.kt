package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.AlbumProfile
import com.landofoz.musicmeta.ArtistProfile
import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.DiscographyAlbum
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.SimilarArtist
import com.landofoz.musicmeta.TrackProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where each rendered datum says it came from, and where a reader can go and see it there. */
class AttributionMappingTest {

    private fun resultsWith(
        identifiers: EnrichmentIdentifiers = EnrichmentIdentifiers(),
        vararg entries: Triple<EnrichmentType, String, EnrichmentData>,
    ): EnrichmentResults =
        EnrichmentResults(
            raw = entries.associate { (type, provider, data) ->
                type to EnrichmentResult.Success(type, data, provider = provider, confidence = 1.0f)
            },
            requestedTypes = entries.map { it.first }.toSet(),
            identity = IdentityResolution(
                identifiers = identifiers,
                status = CanonicalStatus.RESOLVED,
                matchScore = null,
                suggestions = emptyList(),
            ),
        )

    private fun discography(): EnrichmentData.Discography =
        EnrichmentData.Discography(
            albums = listOf(DiscographyAlbum(title = "Master of Puppets", year = 1986)),
        )

    private fun sectionCredits(response: DemoResponse, key: String): List<SourceCredit> =
        response.sections.first { it.key == key }.credits

    @Test
    fun `a card credits the provider whose result filled it`() {
        // Given - an artist whose discography came back from Discogs
        val results = resultsWith(
            entries = arrayOf(Triple(EnrichmentType.ARTIST_DISCOGRAPHY, "discogs", discography())),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the discography card credits Discogs and nothing else
        assertEquals(listOf("discogs"), sectionCredits(response, "discography").map { it.provider })
    }

    @Test
    fun `a merged card credits the upstreams its items name, never the merger`() {
        // Given - similar artists merged by the engine from two upstreams
        val merged = EnrichmentData.SimilarArtists(
            artists = listOf(
                SimilarArtist(name = "Megadeth", matchScore = 0.9f, sources = listOf("lastfm")),
                SimilarArtist(name = "Anthrax", matchScore = 0.8f, sources = listOf("deezer", "lastfm")),
            ),
        )
        val results = resultsWith(
            entries = arrayOf(Triple(EnrichmentType.SIMILAR_ARTISTS, "similar_artist_merger", merged)),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the card credits each item's own source, once each, and never the merger
        assertEquals(listOf("lastfm", "deezer"), sectionCredits(response, "similar_artists").map { it.provider })
    }

    @Test
    fun `a synthesized card credits the upstreams behind the types it was derived from`() {
        // Given - a timeline the engine synthesized from a Deezer discography and MusicBrainz members
        val timeline = EnrichmentData.ArtistTimeline(
            events = listOf(
                com.landofoz.musicmeta.TimelineEvent(date = "1986", description = "Formed", type = "formation"),
            ),
        )
        val members = EnrichmentData.BandMembers(
            members = listOf(com.landofoz.musicmeta.BandMember(name = "James Hetfield", role = "vocals")),
        )
        val results = resultsWith(
            entries = arrayOf(
                Triple(EnrichmentType.ARTIST_TIMELINE, "timeline_synthesizer", timeline),
                Triple(EnrichmentType.ARTIST_DISCOGRAPHY, "deezer", discography()),
                Triple(EnrichmentType.BAND_MEMBERS, "musicbrainz", members),
            ),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the card credits those two upstreams, never the synthesizer no reader can visit
        assertEquals(listOf("deezer", "musicbrainz"), sectionCredits(response, "timeline").map { it.provider })
    }

    @Test
    fun `the summary image is credited to the provider whose image was painted`() {
        // Given - album art ranked to Cover Art Archive but painted from the Deezer alternative
        val art = EnrichmentData.Artwork(
            url = "https://coverartarchive.org/release/1/front.jpg",
            alternatives = listOf(
                ArtworkSource(provider = "deezer", url = "https://cdn-images.dzcdn.net/images/cover/x.jpg"),
            ),
        )
        val results = resultsWith(entries = arrayOf(Triple(EnrichmentType.ALBUM_ART, "coverartarchive", art)))
        val profile = AlbumProfile(title = "Master of Puppets", artist = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the credit names Deezer, the provider of the image actually on the card
        assertEquals("deezer", response.summary.imageCredit?.provider)
    }

    @Test
    fun `summary text credits the provider that supplied it, linked to the article it came from`() {
        // Given - an artist bio from Wikipedia, with the article title resolution settled on
        val results = resultsWith(
            identifiers = EnrichmentIdentifiers(wikipediaTitle = "Radiohead"),
            entries = arrayOf(
                Triple(
                    EnrichmentType.ARTIST_BIO,
                    "wikipedia",
                    EnrichmentData.Biography(text = "Formed in 1985.", source = "Wikipedia"),
                ),
            ),
        )
        val profile = ArtistProfile(name = "Radiohead", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the text credit links the article, which carries the author history the licence needs
        assertEquals(
            SourceCredit("wikipedia", "https://en.wikipedia.org/wiki/Radiohead"),
            response.summary.textCredit,
        )
    }

    @Test
    fun `a Discogs credit links the Discogs page the lookup resolved`() {
        // Given - an artist whose identity resolution settled on a Discogs artist id
        val results = resultsWith(
            identifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DISCOGS_ARTIST, "18839"),
            entries = arrayOf(Triple(EnrichmentType.ARTIST_DISCOGRAPHY, "discogs", discography())),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the credit points at that artist's own Discogs page
        assertEquals("https://www.discogs.com/artist/18839", sectionCredits(response, "discography").single().url)
    }

    @Test
    fun `a Discogs credit with no resolved id falls back to a search for the entity`() {
        // Given - the same discography, with no Discogs id resolved
        val results = resultsWith(
            entries = arrayOf(Triple(EnrichmentType.ARTIST_DISCOGRAPHY, "discogs", discography())),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the credit still resolves to Discogs' own page for that name
        val url = sectionCredits(response, "discography").single().url
        assertTrue(url, url!!.startsWith("https://www.discogs.com/search/?q=Metallica"))
    }

    @Test
    fun `a Deezer credit links the entity kind its id names`() {
        // Given - a track whose identity resolution settled on a Deezer track id
        val similar = EnrichmentData.SimilarTracks(tracks = emptyList())
        val results = resultsWith(
            identifiers = EnrichmentIdentifiers().with(IdentifierNamespace.DEEZER, "3135556"),
            entries = arrayOf(
                Triple(
                    EnrichmentType.CREDITS,
                    "deezer",
                    EnrichmentData.Credits(
                        credits = listOf(com.landofoz.musicmeta.Credit(name = "James Hetfield", role = "vocals")),
                    ),
                ),
                Triple(EnrichmentType.SIMILAR_TRACKS, "deezer", similar),
            ),
        )
        val profile = TrackProfile(title = "Battery", artist = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the id is read as a track id, because the request was for a track
        assertEquals("https://www.deezer.com/track/3135556", sectionCredits(response, "credits").single().url)
    }

    @Test
    fun `a Last dot fm credit links the catalogue page for the entity, as its terms require`() {
        // Given - a track whose similar tracks came from Last.fm
        val similar = EnrichmentData.SimilarTracks(
            tracks = listOf(
                com.landofoz.musicmeta.SimilarTrack(
                    title = "Whiplash",
                    artist = "Metallica",
                    matchScore = 0.7f,
                    sources = listOf("lastfm"),
                ),
            ),
        )
        val results = resultsWith(entries = arrayOf(Triple(EnrichmentType.SIMILAR_TRACKS, "lastfm", similar)))
        val profile = TrackProfile(title = "Battery", artist = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the credit links Last.fm's own page for that track
        assertEquals(
            "https://www.last.fm/music/Metallica/_/Battery",
            sectionCredits(response, "similar_tracks").single().url,
        )
    }

    @Test
    fun `a MusicBrainz credit links the entity the request kind names`() {
        // Given - an artist whose MBID resolution settled, with band members from MusicBrainz
        val members = EnrichmentData.BandMembers(
            members = listOf(com.landofoz.musicmeta.BandMember(name = "James Hetfield", role = "vocals")),
        )
        val results = resultsWith(
            identifiers = EnrichmentIdentifiers(musicBrainzId = "65f4f0c5-ef9e-490c-aee3-909e7ae6b2ab"),
            entries = arrayOf(Triple(EnrichmentType.BAND_MEMBERS, "musicbrainz", members)),
        )
        val profile = ArtistProfile(name = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the MBID is read as an artist id, not an unlabelled one
        assertEquals(
            "https://musicbrainz.org/artist/65f4f0c5-ef9e-490c-aee3-909e7ae6b2ab",
            sectionCredits(response, "band_members").single().url,
        )
    }

    @Test
    fun `a gallery image is credited to the provider that supplied it`() {
        // Given - album art whose losing alternative comes from a different provider
        val art = EnrichmentData.Artwork(
            url = "https://coverartarchive.org/release/1/front.jpg",
            alternatives = listOf(
                ArtworkSource(provider = "itunes", url = "https://is1-ssl.mzstatic.com/image/thumb/x.jpg"),
            ),
        )
        val results = resultsWith(entries = arrayOf(Triple(EnrichmentType.ALBUM_ART, "coverartarchive", art)))
        val profile = AlbumProfile(title = "Master of Puppets", artist = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the gallery entry for that image credits iTunes
        assertEquals("itunes", response.gallery.single { it.label == "itunes" }.credit?.provider)
    }

    @Test
    fun `a card whose provider is unknown to the link table is credited without an invented link`() {
        // Given - lyrics from LRCLIB, which resolution has no identifier to address
        val lyrics = EnrichmentData.Lyrics(plainLyrics = "Lashing out the action")
        val results = resultsWith(entries = arrayOf(Triple(EnrichmentType.LYRICS_PLAIN, "lrclib", lyrics)))
        val profile = TrackProfile(title = "Battery", artist = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the text is credited to LRCLIB with no link-back the response could not support
        assertEquals("lrclib", response.summary.textCredit?.provider)
        assertNull(response.summary.textCredit?.url)
    }
}
