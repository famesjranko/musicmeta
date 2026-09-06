package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.ExternalLink
import com.landofoz.musicmeta.externalLink
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A MusicBrainz relation type names the relationship, not the site: the `Changg` capture carries
 * `social network` twice. The site comes from the URL's own host.
 */
class MusicBrainzArtistLinkSiteTest {

    private fun changgLinks(): List<ExternalLink> {
        val text = checkNotNull(
            javaClass.getResourceAsStream("/corpora/artist-external-links/musicbrainz-changg-url-rels.json"),
        ).bufferedReader().readText()
        val relations = MusicBrainzParser.parseUrlRelations(JSONObject(text))
        return MusicBrainzMapper.toArtistLinks(relations).links
    }

    @Test
    fun `two rows sharing one relation type are labelled by their own hosts`() {
        // Given - the live Changg capture, whose Facebook and Instagram rows are both `social network`
        val links = changgLinks()

        // When - the mapper turns those relations into external links
        val social = links.filter { it.type == "social network" }

        // Then - each carries its own site, taken from the host with `www.` dropped
        assertEquals(listOf("facebook.com", "instagram.com"), social.map { it.label })
    }

    @Test
    fun `a host that is not www is kept whole`() {
        // Given - the Spotify row, whose host is `open.spotify.com`
        val links = changgLinks()

        // When - the Spotify link is read
        val spotify = links.single { it.url.startsWith("https://open.spotify.com/") }

        // Then - only a leading `www.` is dropped, so the subdomain survives
        assertEquals("open.spotify.com", spotify.label)
    }

    @Test
    fun `every captured row is labelled by its site rather than its relation type`() {
        // Given - the five relations MusicBrainz returned for Changg
        val links = changgLinks()

        // When - their labels are read in the order the upstream listed them
        val labels = links.map { it.label }

        // Then - each names a site, where the types named `free streaming` and `social network` twice
        assertEquals(
            listOf("open.spotify.com", "facebook.com", "instagram.com", "soundcloud.com", "youtube.com"),
            labels,
        )
    }

    @Test
    fun `a url with no parseable host carries no label`() {
        // Given - a relation whose URL is not one a host can be read from
        val relation = MusicBrainzUrlRelation(type = "social network", url = "not a url")

        // When - the mapper maps it
        val link = MusicBrainzMapper.toArtistLinks(listOf(relation)).links.single()

        // Then - the label stays null rather than inventing one
        assertNull(link.label)
    }

    @Test
    fun `a label the caller supplied is not replaced by the host`() {
        // Given - a producer that already knows the site's name
        val supplied = "Bandcamp"

        // When - a link is built with that label
        val link = externalLink(type = "free streaming", url = "https://www.bandcamp.com/x", label = supplied)

        // Then - the derivation defers to it
        assertEquals("Bandcamp", link.label)
    }
}
