package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.AlbumProfile
import com.landofoz.musicmeta.ArtistProfile
import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.Attribution
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

    private val commonsPhoto = "https://upload.wikimedia.org/wikipedia/commons/a/a1/Thom_Yorke.jpg"

    private fun artistProfileWithPhoto(
        photoProvider: String,
        photoAttribution: Attribution?,
        bioAttribution: Attribution? = null,
    ): ArtistProfile {
        val photo = EnrichmentData.Artwork(url = commonsPhoto, attribution = photoAttribution)
        val bio = EnrichmentData.Biography(
            text = "Radiohead are an English rock band.",
            source = "Wikipedia",
            attribution = bioAttribution,
        )
        val results = resultsWith(
            entries = arrayOf(
                Triple(EnrichmentType.ARTIST_PHOTO, photoProvider, photo),
                Triple(EnrichmentType.ARTIST_BIO, "wikipedia", bio),
            ),
        )
        return ArtistProfile(name = "Radiohead", results = results)
    }

    private fun artistWithPhoto(
        photoProvider: String,
        photoAttribution: Attribution?,
        bioAttribution: Attribution? = null,
    ): DemoResponse =
        artistProfileWithPhoto(photoProvider, photoAttribution, bioAttribution).toDemoResponse(elapsedMs = 0)

    private fun assertShownWithCredit(response: DemoResponse, provider: String, expected: Attribution?) {
        assertEquals(commonsPhoto, response.summary.imageUrl)
        assertEquals("Radiohead are an English rock band.", response.summary.text)
        assertEquals(provider, response.summary.imageCredit?.provider)
        assertEquals(expected, response.summary.imageCredit?.attribution)
    }

    @Test
    fun `a Wikipedia photo with no attribution is shown and credited to Wikipedia alone`() {
        // Given - a Wikipedia photo whose upstream said nothing about the file
        val profile = artistProfileWithPhoto("wikipedia", photoAttribution = null)

        // When - mapping the artist
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the photo and the text are returned, and the credit carries no file facts
        assertShownWithCredit(response, "wikipedia", expected = null)
    }

    @Test
    fun `a Wikipedia photo with partial attribution is shown with the facts it has`() {
        // Given - a Wikipedia photo whose upstream named only a creator
        val partial = Attribution(creator = "Jane Doe")

        // When - mapping the artist
        val response = artistWithPhoto("wikipedia", partial)

        // Then - the photo and the text are returned, and the credit carries that creator
        assertShownWithCredit(response, "wikipedia", partial)
    }

    @Test
    fun `a Wikipedia photo with restrictive attribution is shown with the restrictions attached`() {
        // Given - a Wikipedia photo whose upstream lists a non-commercial licence and restrictions
        val restrictive = Attribution(
            licence = "CC BY-NC 4.0",
            restrictions = listOf("No commercial use", "Personality rights"),
        )

        // When - mapping the artist
        val response = artistWithPhoto("wikipedia", restrictive)

        // Then - the photo and the text are returned, and the credit carries every restriction
        assertShownWithCredit(response, "wikipedia", restrictive)
    }

    @Test
    fun `a Wikipedia photo with contradictory attribution is shown with every statement kept`() {
        // Given - a Wikipedia photo whose upstream names public domain, a second licence and copyright
        val contradictory = Attribution(
            licence = "Public domain",
            otherLicences = listOf("CC BY-NC 4.0"),
            copyrightStatus = "True",
        )

        // When - mapping the artist
        val response = artistWithPhoto("wikipedia", contradictory)

        // Then - the photo and the text are returned, and the credit carries all of it unreconciled
        assertShownWithCredit(response, "wikipedia", contradictory)
    }

    @Test
    fun `a Wikipedia photo with unsafe links is shown with the links passed through for the page to judge`() {
        // Given - a Wikipedia photo whose description and licence links are not https
        val unsafe = Attribution(
            creator = "Jane Doe",
            sourceUrl = "javascript:alert(1)",
            licence = "CC BY 4.0",
            licenceUrl = "http://creativecommons.org/licenses/by/4.0/",
        )

        // When - mapping the artist
        val response = artistWithPhoto("wikipedia", unsafe)

        // Then - the photo and the text are returned, and every fact survives, links unchanged
        assertShownWithCredit(response, "wikipedia", unsafe)
    }

    @Test
    fun `a Wikidata photo on a Wikimedia host is shown and credited to Wikidata`() {
        // Given - a Wikidata photo carrying a licence
        val facts = Attribution(licence = "CC0")

        // When - mapping the artist
        val response = artistWithPhoto("wikidata", facts)

        // Then - the photo is returned and credited to Wikidata, with its facts
        assertShownWithCredit(response, "wikidata", facts)
    }

    private val commonsAlternative = "https://upload.wikimedia.org/wikipedia/commons/b/b2/Alt.jpg"
    private val commonsHostAlternative = "https://commons.wikimedia.org/media/c/c3/Other.jpg"
    private val commonsLogo = "https://upload.wikimedia.org/wikipedia/commons/d/d4/Logo.svg"
    private val commonsBanner = "https://upload.wikimedia.org/wikipedia/commons/e/e5/Banner.jpg"

    /**
     * An artist whose primary photo is not Wikimedia's but whose gallery holds four Wikimedia
     * images: two alternatives (one by provider, one by host only), a logo and a banner.
     */
    private fun artistWithWikimediaGallery(attribution: Attribution?): DemoResponse {
        val photo = EnrichmentData.Artwork(
            url = "https://assets.fanart.tv/photo.jpg",
            alternatives = listOf(
                ArtworkSource(provider = "wikipedia", url = commonsAlternative, attribution = attribution),
                ArtworkSource(provider = "somecdn", url = commonsHostAlternative, attribution = attribution),
            ),
        )
        val results = resultsWith(
            entries = arrayOf(
                Triple(EnrichmentType.ARTIST_PHOTO, "fanarttv", photo),
                Triple(EnrichmentType.ARTIST_LOGO, "wikipedia", EnrichmentData.Artwork(commonsLogo, attribution = attribution)),
                Triple(EnrichmentType.ARTIST_BANNER, "wikidata", EnrichmentData.Artwork(commonsBanner, attribution = attribution)),
            ),
        )
        return ArtistProfile(name = "Radiohead", results = results).toDemoResponse(elapsedMs = 0)
    }

    private fun assertWikimediaGalleryKept(response: DemoResponse, expected: Attribution?) {
        val byUrl = response.gallery.associateBy { it.url }
        assertEquals(
            setOf(commonsAlternative, commonsHostAlternative, commonsLogo, commonsBanner),
            byUrl.keys,
        )
        assertEquals(
            listOf("wikipedia", "somecdn", "wikipedia", "wikidata"),
            listOf(commonsAlternative, commonsHostAlternative, commonsLogo, commonsBanner).map { byUrl[it]?.credit?.provider },
        )
        assertTrue(byUrl.values.all { it.credit?.attribution == expected })
    }

    @Test
    fun `Wikimedia gallery images with no attribution are all kept`() {
        // Given - an artist whose Wikimedia gallery images carry no file facts
        // When - mapping the artist
        val response = artistWithWikimediaGallery(attribution = null)

        // Then - every alternative, the logo and the banner are in the gallery, credited to their providers
        assertWikimediaGalleryKept(response, expected = null)
    }

    @Test
    fun `Wikimedia gallery images with partial attribution are all kept`() {
        // Given - Wikimedia gallery images whose upstream named only a creator and no source link
        val partial = Attribution(creator = "Jane Doe")

        // When - mapping the artist
        val response = artistWithWikimediaGallery(partial)

        // Then - every one is in the gallery and carries that creator
        assertWikimediaGalleryKept(response, partial)
    }

    @Test
    fun `Wikimedia gallery images with restrictive attribution are all kept`() {
        // Given - Wikimedia gallery images under a non-commercial licence with restrictions
        val restrictive = Attribution(
            licence = "CC BY-NC 4.0",
            restrictions = listOf("No commercial use", "Personality rights"),
        )

        // When - mapping the artist
        val response = artistWithWikimediaGallery(restrictive)

        // Then - every one is in the gallery and carries the restrictions
        assertWikimediaGalleryKept(response, restrictive)
    }

    @Test
    fun `Wikimedia gallery images with contradictory attribution are all kept`() {
        // Given - Wikimedia gallery images naming public domain, a second licence and copyright
        val contradictory = Attribution(
            licence = "Public domain",
            otherLicences = listOf("CC BY-NC 4.0"),
            copyrightStatus = "True",
        )

        // When - mapping the artist
        val response = artistWithWikimediaGallery(contradictory)

        // Then - every one is in the gallery with all of it unreconciled
        assertWikimediaGalleryKept(response, contradictory)
    }

    @Test
    fun `Wikimedia gallery images with unsafe links are all kept`() {
        // Given - Wikimedia gallery images whose description and licence links are not https
        val unsafe = Attribution(
            creator = "Jane Doe",
            sourceUrl = "javascript:alert(1)",
            licence = "CC BY 4.0",
            licenceUrl = "http://creativecommons.org/licenses/by/4.0/",
        )

        // When - mapping the artist
        val response = artistWithWikimediaGallery(unsafe)

        // Then - every one is in the gallery, links unchanged for the page to judge
        assertWikimediaGalleryKept(response, unsafe)
    }

    @Test
    fun `the card image carries the facts of the alternative that is painted, not the primary's`() {
        // Given - album art whose primary is on the Cover Art Archive and whose iTunes alternative paints the card
        val art = EnrichmentData.Artwork(
            url = "https://coverartarchive.org/release/1/front.jpg",
            attribution = Attribution(creator = "Primary creator"),
            alternatives = listOf(
                ArtworkSource(
                    provider = "itunes",
                    url = "https://is1-ssl.mzstatic.com/image/thumb/x.jpg",
                    attribution = Attribution(creator = "Alternative creator"),
                ),
            ),
        )
        val results = resultsWith(entries = arrayOf(Triple(EnrichmentType.ALBUM_ART, "coverartarchive", art)))
        val profile = AlbumProfile(title = "Master of Puppets", artist = "Metallica", results = results)

        // When - mapping to a demo response
        val response = profile.toDemoResponse(elapsedMs = 0)

        // Then - the credit is iTunes' with iTunes' facts, and the gallery entry carries them too
        assertEquals("itunes", response.summary.imageCredit?.provider)
        assertEquals("Alternative creator", response.summary.imageCredit?.attribution?.creator)
        assertEquals("Alternative creator", response.gallery.single { it.label == "itunes" }.credit?.attribution?.creator)
    }

    @Test
    fun `a biography thumbnail is credited to its provider without reading the article's terms as the picture's`() {
        // Given - a Wikipedia biography with a thumbnail and no photo, whose attribution describes the text
        val article = Attribution(
            sourceUrl = "https://en.wikipedia.org/wiki/Radiohead",
            licence = "CC BY-SA 4.0",
        )
        val bio = EnrichmentData.Biography(
            text = "Radiohead are an English rock band.",
            source = "Wikipedia",
            thumbnailUrl = commonsPhoto,
            attribution = article,
        )
        val results = resultsWith(entries = arrayOf(Triple(EnrichmentType.ARTIST_BIO, "wikipedia", bio)))

        // When - mapping the artist
        val response = ArtistProfile(name = "Radiohead", results = results).toDemoResponse(elapsedMs = 0)

        // Then - the thumbnail is shown with a provider-only credit, and the text link falls back to the article
        assertEquals(commonsPhoto, response.summary.imageUrl)
        assertNull(response.summary.imageCredit?.attribution)
        assertEquals("https://en.wikipedia.org/wiki/Radiohead", response.summary.textCredit?.url)
    }
}
