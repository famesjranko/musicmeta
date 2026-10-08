package com.landofoz.musicmeta.demo

import com.landofoz.musicmeta.ArtistProfile
import com.landofoz.musicmeta.ArtworkSource
import com.landofoz.musicmeta.Attribution
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.demo.ui.Terminal
import com.landofoz.musicmeta.demo.ui.Theme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Attribution facts print as credit rows beside the content; none of them decides whether a row prints. */
class AttributionRowsTest {

    private val photoUrl = "https://upload.wikimedia.org/radiohead.jpg"

    private fun photo(attribution: Attribution?, url: String = photoUrl, alternatives: List<ArtworkSource>? = null) =
        EnrichmentData.Artwork(url = url, attribution = attribution, alternatives = alternatives)

    private fun resultRows(type: EnrichmentType, data: EnrichmentData, theme: Theme = Theme.Plain): String {
        val raw = mapOf<EnrichmentType, EnrichmentResult>(
            type to EnrichmentResult.Success(type = type, data = data, provider = "wikipedia", confidence = 0.9f),
        )
        return capture(theme) { term -> Formatter.printResults(resultsOf(raw), term) }
    }

    private fun capture(theme: Theme, block: (Terminal) -> Unit): String {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer))
        try {
            block(Terminal(theme))
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    @Test
    fun `a photo with no attribution still prints its URL and no credit row`() {
        // Given - a photo whose upstream stated nothing about its file
        val data = photo(attribution = null)

        // When - rendering the results block
        val output = resultRows(EnrichmentType.ARTIST_PHOTO, data)

        // Then - the URL is printed and no credit row appears
        assertTrue(output, output.contains(photoUrl))
        assertFalse(output, output.contains("licence:"))
    }

    @Test
    fun `a photo with partial attribution prints every fact it has`() {
        // Given - a photo whose upstream gave only a licence name and a creator
        val data = photo(Attribution(creator = "Raph_PH", licence = "CC BY 4.0"))

        // When - rendering the results block
        val output = resultRows(EnrichmentType.ARTIST_PHOTO, data)

        // Then - the photo URL and both facts are printed, and the missing ones are not invented
        assertTrue(output, output.contains(photoUrl))
        assertTrue(output, output.contains("creator: Raph_PH"))
        assertTrue(output, output.contains("licence: CC BY 4.0"))
        assertFalse(output, output.contains("licence url:"))
    }

    @Test
    fun `a photo with restrictions prints the photo and each restriction`() {
        // Given - a photo whose upstream lists reuse restrictions
        val data = photo(Attribution(licence = "CC BY-NC", restrictions = listOf("trademarked", "personality rights")))

        // When - rendering the results block
        val output = resultRows(EnrichmentType.ARTIST_PHOTO, data)

        // Then - the photo is still shown beside the restrictions in the upstream's words
        assertTrue(output, output.contains(photoUrl))
        assertTrue(output, output.contains("restrictions: trademarked; personality rights"))
    }

    @Test
    fun `artwork with contradictory licences prints the URL, alternatives and all licences`() {
        // Given - artwork naming two licences and a copyright status that disagrees, with an alternative
        val alt = ArtworkSource(
            provider = "deezer",
            url = "https://cdn.example/alt.jpg",
            attribution = Attribution(licence = "All rights reserved"),
        )
        val data = photo(
            Attribution(licence = "Public domain", otherLicences = listOf("CC BY-SA 4.0"), copyrightStatus = "True"),
            alternatives = listOf(alt),
        )

        // When - rendering the results block
        val output = resultRows(EnrichmentType.ALBUM_ART, data)

        // Then - the URL, the alternative's URL, every licence and the alternative's own credit are printed
        assertTrue(output, output.contains(photoUrl))
        assertTrue(output, output.contains("https://cdn.example/alt.jpg"))
        assertTrue(output, output.contains("licence: Public domain"))
        assertTrue(output, output.contains("other licences: CC BY-SA 4.0"))
        assertTrue(output, output.contains("copyright: True"))
        assertTrue(output, output.contains("deezer licence: All rights reserved"))
    }

    @Test
    fun `a bio prints its text and its credit rows`() {
        // Given - a biography carrying its article page and licence
        val data = EnrichmentData.Biography(
            text = "Radiohead are an English rock band.",
            source = "wikipedia",
            attribution = Attribution(
                sourceUrl = "https://en.wikipedia.org/wiki/Radiohead",
                licence = "CC BY-SA 4.0",
            ),
        )

        // When - rendering the results block
        val output = resultRows(EnrichmentType.ARTIST_BIO, data)

        // Then - the text and both facts are printed
        assertTrue(output, output.contains("Radiohead are an English rock band."))
        assertTrue(output, output.contains("source: https://en.wikipedia.org/wiki/Radiohead"))
        assertTrue(output, output.contains("licence: CC BY-SA 4.0"))
    }

    @Test
    fun `unsafe links print as plain text and are never a terminal hyperlink`() {
        // Given - a photo, a source page and a licence page whose schemes are not http(s), on a styled terminal
        val bad = "javascript:alert(1)"
        val data = photo(Attribution(sourceUrl = bad, licenceUrl = "data:text/html,x", licence = "CC0"), url = bad)

        // When - rendering the results block
        val output = resultRows(EnrichmentType.ARTIST_PHOTO, data, Theme.Default)

        // Then - every URL is still printed as text and none is wrapped in an OSC 8 hyperlink
        assertTrue(output, output.contains("source: $bad"))
        assertTrue(output, output.contains("licence url: data:text/html,x"))
        assertTrue(output, output.contains("image $bad") || output.contains("wikipedia $bad"))
        assertFalse(output, output.contains("\u001b]8;;"))
    }

    @Test
    fun `a safe https photo URL is still a terminal hyperlink on a styled terminal`() {
        // Given - a photo with a plain https URL
        val data = photo(attribution = null)

        // When - rendering the results block on a styled terminal
        val output = resultRows(EnrichmentType.ARTIST_PHOTO, data, Theme.Default)

        // Then - the URL is wrapped as a hyperlink
        assertTrue(output, output.contains("\u001b]8;;$photoUrl"))
    }

    @Test
    fun `a Wikipedia photo row carries the file's facts and never the article text licence`() {
        // Given - a photo credited to its own author under a licence other than the article's
        val data = photo(Attribution(creator = "Raph_PH", licence = "CC BY 4.0"))

        // When - rendering the results block
        val output = resultRows(EnrichmentType.ARTIST_PHOTO, data)

        // Then - the file's licence is shown and the text licence is not claimed for it
        assertTrue(output, output.contains("licence: CC BY 4.0"))
        assertFalse(output, output.contains("CC BY-SA"))
        assertFalse(output, output.contains("Text from Wikipedia"))
    }

    @Test
    fun `terminal control bytes in attribution text are escaped and the facts still print`() {
        // Given - a creator with ESC and BEL, a credit with a C1 byte, and an ordinary licence
        val data = photo(
            Attribution(creator = "A\u001b[31mB\u0007", credit = "via\u009bX", licence = "CC BY 4.0"),
        )

        // When - rendering the results block
        val output = resultRows(EnrichmentType.ARTIST_PHOTO, data)

        // Then - the bytes are shown as escapes, none reaches the terminal, and the other rows print
        assertTrue(output, output.contains("creator: A\\u001b[31mB\\u0007"))
        assertTrue(output, output.contains("credit: via\\u009bX"))
        assertTrue(output, output.contains("licence: CC BY 4.0"))
        assertFalse(output, output.contains("\u001b"))
        assertFalse(output, output.contains("\u0007"))
        assertFalse(output, output.contains("\u009b"))
    }

    @Test
    fun `the profile photo and bio rows carry their credit rows`() {
        // Given - an artist profile whose photo and bio each carry attribution
        val profile = ArtistProfile(
            name = "Radiohead",
            results = resultsOf(
                mapOf(
                    EnrichmentType.ARTIST_PHOTO to EnrichmentResult.Success(
                        type = EnrichmentType.ARTIST_PHOTO,
                        data = photo(Attribution(creator = "Raph_PH")),
                        provider = "wikipedia",
                        confidence = 0.9f,
                    ),
                    EnrichmentType.ARTIST_BIO to EnrichmentResult.Success(
                        type = EnrichmentType.ARTIST_BIO,
                        data = EnrichmentData.Biography("An English band.", "wikipedia", attribution = Attribution(licence = "CC BY-SA 4.0")),
                        provider = "wikipedia",
                        confidence = 0.9f,
                    ),
                ),
            ),
        )

        // When - rendering the profile block
        val output = captureOutput { term -> Formatter.printProfile(profile, term) }.substringBefore("Results")

        // Then - the photo URL, its creator, the bio text and its licence are all in the profile block
        assertTrue(output, output.contains(photoUrl))
        assertTrue(output, output.contains("creator: Raph_PH"))
        assertTrue(output, output.contains("An English band."))
        assertTrue(output, output.contains("licence: CC BY-SA 4.0"))
    }
}
