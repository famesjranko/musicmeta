package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.AlbumProfile
import com.landofoz.musicmeta.ArtistProfile
import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.ContentAttribution
import com.landofoz.musicmeta.ContentLicense
import com.landofoz.musicmeta.DiscographyAlbum
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.LicenseRelation
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
    fun `a same-name pair renders each act's own disambiguation ahead of its rank`() {
        // Given - the split pair a reader cannot tell apart from the name alone
        val merged = EnrichmentData.SimilarArtists(
            artists = listOf(
                SimilarArtist(
                    name = "Loathe",
                    matchScore = 0.9f,
                    sources = listOf("listenbrainz"),
                    disambiguation = "UK experimental metal",
                ),
                SimilarArtist(
                    name = "Loathe",
                    matchScore = 0.4f,
                    sources = listOf("lastfm"),
                    disambiguation = "Maltese death metal band",
                ),
            ),
        )
        val results = resultsWith(
            entries = arrayOf(Triple(EnrichmentType.SIMILAR_ARTISTS, "similar_artist_merger", merged)),
        )
        val profile = ArtistProfile(name = "Sleep Token", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - both rows are still named Loathe, and each carries its own act's description first
        val items = response.sections.first { it.key == "similar_artists" }.items
        assertEquals(listOf("Loathe", "Loathe"), items.map { it.primary })
        assertEquals(
            listOf("UK experimental metal · rank 0.90", "Maltese death metal band · rank 0.40"),
            items.map { it.secondary },
        )
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
    fun `the summary image carries its selected file credit rather than a provider label`() {
        // Given - a selected artwork file with creator, description page, and licence metadata
        val attribution = ContentAttribution(
            resourceId = "File:Master of Puppets.jpg",
            sourceUrl = "https://commons.wikimedia.org/wiki/File:Master_of_Puppets.jpg",
            creator = "<Metallica>",
            licenses = listOf(ContentLicense("CC BY-SA 4.0", "https://creativecommons.org/licenses/by-sa/4.0/")),
            copyrighted = true,
            attributionRequired = true,
            nonFree = false,
            restrictions = emptyList(),
        )
        val results = resultsWith(entries = arrayOf(
            Triple(EnrichmentType.ALBUM_ART, "wikipedia", EnrichmentData.Artwork("https://example.com/a.jpg", attribution = attribution)),
        ))

        // When - mapping to a demo response
        val response = AlbumProfile("Master of Puppets", "Metallica", results).toDemoResponse(0)

        // Then - the image exposes file-specific source facts for the watermark popover
        assertEquals("<Metallica>", response.summary.imageAttribution?.creator)
        assertEquals(attribution.sourceUrl, response.summary.imageAttribution?.sourceUrl)
        assertEquals("CC BY-SA 4.0", response.summary.imageAttribution?.licenses?.single()?.identifier)
    }

    @Test
    fun `an old Wikimedia image without file attribution is withheld`() {
        // Given - an old cached Wikipedia artwork payload with no file attribution
        val results = resultsWith(entries = arrayOf(
            Triple(EnrichmentType.ALBUM_ART, "wikipedia", EnrichmentData.Artwork("https://example.com/legacy.jpg")),
        ))

        // When - mapping to a demo response
        val response = AlbumProfile("Master of Puppets", "Metallica", results).toDemoResponse(0)

        // Then - the unsafe legacy image has no rendered URL or provider fallback credit
        assertNull(response.summary.imageUrl)
        assertNull(response.summary.imageAttribution)
    }

    @Test
    fun `article attribution never establishes thumbnail rights`() {
        // Given - an attributed Wikipedia article with an independently unattributed thumbnail
        val article = ContentAttribution("Fixture", "https://en.wikipedia.org/wiki/Fixture", creator = "Contributors")
        val results = resultsWith(entries = arrayOf(Triple(EnrichmentType.ARTIST_BIO, "wikipedia",
            EnrichmentData.Biography("Biography", "Wikipedia", thumbnailUrl = "https://upload.wikimedia.org/unsafe.jpg", attribution = article))))

        // When - mapping the article and thumbnail to the demo
        val response = ArtistProfile("Fixture", results).toDemoResponse(0)

        // Then - the biography keeps its article attribution while the thumbnail is withheld
        assertNull(response.summary.imageUrl)
        assertNull(response.summary.imageAttribution)
        assertEquals(article.sourceUrl, response.summary.textAttribution?.sourceUrl)
    }

    @Test
    fun `unsafe Wikimedia alternatives and backgrounds are withheld`() {
        // Given - an eligible primary plus old Wikimedia alternative and background payloads
        val art = EnrichmentData.Artwork("https://example.test/primary.jpg", alternatives = listOf(
            ArtworkSource("wikipedia", "https://cdn-images.dzcdn.net/unsafe.jpg")))
        val results = resultsWith(entries = arrayOf(
            Triple(EnrichmentType.ARTIST_PHOTO, "other", art),
            Triple(EnrichmentType.ARTIST_BACKGROUND, "wikipedia", EnrichmentData.Artwork("https://upload.wikimedia.org/unsafe.jpg"))))

        // When - mapping the images to the demo
        val response = ArtistProfile("Fixture", results).toDemoResponse(0)

        // Then - the eligible primary remains while neither unsafe image is rendered
        assertEquals(art.url, response.summary.imageUrl)
        assertEquals(emptyList<GalleryImage>(), response.gallery)
        assertNull(response.summary.backgroundImageUrl)
    }

    @Test
    fun `public domain artwork preserves its file source and licence without inventing a creator`() {
        // Given - a file explicitly released into the public domain
        val attribution = ContentAttribution("File:Archive.jpg", "https://commons.wikimedia.org/wiki/File:Archive.jpg",
            licenses = listOf(ContentLicense("Public domain")), copyrighted = false, attributionRequired = false,
            nonFree = false, restrictions = emptyList())
        val results = resultsWith(entries = arrayOf(Triple(EnrichmentType.ALBUM_ART, "wikipedia",
            EnrichmentData.Artwork("https://example.test/archive.jpg", attribution = attribution))))

        // When - mapping public domain artwork
        val response = AlbumProfile("Fixture", "Artist", results).toDemoResponse(0)

        // Then - the source and public domain designation survive with no invented creator
        assertEquals("https://example.test/archive.jpg", response.summary.imageUrl)
        assertEquals(attribution.sourceUrl, response.summary.imageAttribution?.sourceUrl)
        assertEquals("Public domain", response.summary.imageAttribution?.licenses?.single()?.identifier)
        assertNull(response.summary.imageAttribution?.creator)
    }

    @Test
    fun `ambiguous or restricted Wikimedia artwork is withheld`() {
        // Given - incomplete or restricted file rights in a cached payload
        val attribution = ContentAttribution("File:Unsafe.jpg", "https://commons.wikimedia.org/wiki/File:Unsafe.jpg",
            creator = "Photographer", licenses = listOf(ContentLicense("CC BY 4.0"), ContentLicense("CC0")),
            licenseRelation = LicenseRelation.UNKNOWN, nonFree = false, restrictions = listOf("editorial only"))
        val results = resultsWith(entries = arrayOf(Triple(EnrichmentType.ALBUM_ART, "wikipedia",
            EnrichmentData.Artwork("https://example.test/unsafe.jpg", attribution = attribution))))

        // When - mapping an image whose reuse is not established
        val response = AlbumProfile("Fixture", "Artist", results).toDemoResponse(0)

        // Then - the demo withholds the image even though an attribution object exists
        assertNull(response.summary.imageUrl)
    }

    @Test
    fun `Wikimedia image URLs require file attribution regardless of provider`() {
        // Given - a legacy Wikidata image hosted by Wikimedia without file attribution
        val results = resultsWith(entries = arrayOf(Triple(EnrichmentType.ARTIST_PHOTO, "wikidata",
            EnrichmentData.Artwork("https://upload.wikimedia.org/wikipedia/commons/a/a1/Legacy.jpg"))))

        // When - mapping an image routed through another provider
        val response = ArtistProfile("Fixture", results).toDemoResponse(0)

        // Then - the Wikimedia image cannot bypass the file attribution guard
        assertNull(response.summary.imageUrl)
    }

    @Test
    fun `the chosen CDN alternative preserves its custom credit and multiple licence relation`() {
        // Given - a fast alternative with file-specific custom credit and two required licences
        val attribution = ContentAttribution("Alternative", "https://example.test/alternative", creator = "Author",
            attributionText = "Custom credit", licenses = listOf(ContentLicense("CC BY 4.0"), ContentLicense("Custom grant")),
            licenseRelation = LicenseRelation.ALL_OF, usageTerms = "Both apply", restrictions = listOf("Retain notice"))
        val art = EnrichmentData.Artwork("https://example.test/primary.jpg", alternatives = listOf(
            ArtworkSource("other", "https://cdn-images.dzcdn.net/alternative.jpg", attribution = attribution)))
        val results = resultsWith(entries = arrayOf(Triple(EnrichmentType.ALBUM_ART, "other", art)))

        // When - mapping the fast alternative selected for the card
        val response = AlbumProfile("Fixture", "Artist", results).toDemoResponse(0)

        // Then - primary and gallery uses carry that exact file credit and required licence relation
        assertEquals("Custom credit", response.summary.imageAttribution?.attributionText)
        assertEquals("ALL_OF", response.summary.imageAttribution?.licenseRelation)
        assertEquals(listOf("CC BY 4.0", "Custom grant"), response.summary.imageAttribution?.licenses?.map { it.identifier })
        assertEquals("Both apply", response.summary.imageAttribution?.usageTerms)
        assertEquals(listOf("Retain notice"), response.summary.imageAttribution?.restrictions)
        assertEquals(response.summary.imageAttribution, response.gallery.single().attribution)
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
