package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.Attribution
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testkit.UpstreamPools
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The facts `imageinfo` states about one file, read from live captures in
 * `pools/wikipedia-file-attribution/` (and, where a capture cannot show the case, from a copy of one
 * edited in the test, which `scenario.md` calls derived). The mapping judges nothing: each case
 * names every fact it expects, and a missing, odd or restrictive answer maps to what it said.
 */
class WikipediaFileAttributionTest {

    private suspend fun fileInfoFrom(body: String): WikipediaFileInfo? {
        val http = FakeHttpClient()
        http.givenJsonResponse("prop=imageinfo", body)
        return WikipediaApi(http, RateLimiter(0L)).getFileInfo("File:Any.jpg")
    }

    private suspend fun attributionOfCapture(file: String): Attribution =
        checkNotNull(fileInfoFrom(UpstreamPools.body(POOL, file))).toFileAttribution()

    /** A copy of the Radiohead capture whose `extmetadata` is replaced by [fields]. */
    private suspend fun fileInfoOfDerived(fields: Map<String, String>, descriptionUrl: String? = null): WikipediaFileInfo {
        val body = JSONObject(UpstreamPools.body(POOL, "imageinfo-radiohead-lead.json"))
        val info = body.getJSONObject("query").getJSONArray("pages").getJSONObject(0)
            .getJSONArray("imageinfo").getJSONObject(0)
        val extmetadata = JSONObject()
        fields.forEach { (name, value) -> extmetadata.put(name, JSONObject().put("value", value)) }
        info.put("extmetadata", extmetadata)
        if (descriptionUrl != null) info.put("descriptionurl", descriptionUrl)
        return checkNotNull(fileInfoFrom(body.toString()))
    }

    private suspend fun attributionOfDerived(fields: Map<String, String>, descriptionUrl: String? = null): Attribution =
        fileInfoOfDerived(fields, descriptionUrl).toFileAttribution()

    /**
     * The attribution [artist] maps to, failing with a [java.util.concurrent.TimeoutException] when
     * the mapping takes 2 s. It runs on a daemon thread so a slow mapping fails the test, not the suite.
     */
    private suspend fun attributionOfWithin2s(artist: String): Attribution {
        val info = fileInfoOfDerived(mapOf("Artist" to artist, "LicenseShortName" to "CC BY 4.0"))
        val result = CompletableFuture<Attribution>()
        thread(isDaemon = true) { result.complete(info.toFileAttribution()) }
        return result.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `the Radiohead lead image carries its own creator and licence, not the article's text licence`() = runTest {
        // Given - the live imageinfo answer for the lead image of the Radiohead article

        // When - it is mapped to an attribution
        val attribution = attributionOfCapture("imageinfo-radiohead-lead.json")

        // Then - the creator is Raph_PH and the licence is CC BY 4.0, with the Commons description page
        assertEquals(
            Attribution(
                title = "File:RadioheadO2211125 composite.jpg",
                sourceUrl = "https://commons.wikimedia.org/wiki/File:RadioheadO2211125_composite.jpg",
                creator = "Raph_PH",
                credit = "https://www.flickr.com/photos/raph_ph/albums/72177720330630937 " +
                    "Ed O'Brien Thom Yorke Colin Greenwood Philip Selway Johnny Greenwood",
                licence = "CC BY 4.0",
                licenceUrl = "https://creativecommons.org/licenses/by/4.0",
                copyrightStatus = "True",
            ),
            attribution,
        )
    }

    @Test
    fun `a public domain file carries its status and a creator line with the markup removed`() = runTest {
        // Given - the live imageinfo answer for a public domain photograph, whose Artist field is HTML

        // When - it is mapped to an attribution
        val attribution = attributionOfCapture("imageinfo-public-domain.json")

        // Then - the licence is Public domain, no licence URL is invented, and the creator is plain text
        assertEquals("Public domain", attribution.licence)
        assertNull(attribution.licenceUrl)
        assertEquals("False", attribution.copyrightStatus)
        assertEquals(
            "Photograph by Orren Jack Turner, Princeton, N.J. Modified with Photoshop by PM_Poon and later by Dantadd.",
            attribution.creator,
        )
    }

    @Test
    fun `a file with a custom attribution line carries it beside the creator and credit`() = runTest {
        // Given - the live imageinfo answer for a file whose uploader supplied an Attribution line

        // When - it is mapped to an attribution
        val attribution = attributionOfCapture("imageinfo-custom-attribution.json")

        // Then - the attribution text, the creator and the credit are all kept
        assertEquals("© Raimond Spekking / CC BY-SA 4.0 (via Wikimedia Commons)", attribution.attributionText)
        assertEquals("Raimond Spekking", attribution.creator)
        assertEquals("Own work", attribution.credit)
        assertEquals("CC BY-SA 4.0", attribution.licence)
    }

    @Test
    fun `a multi-licensed file carries the one licence the answer states and names no further one`() = runTest {
        // Given - the live answer for a file Commons categorises under both GFDL 1.2 and the Free Art Licence

        // When - it is mapped to an attribution
        val attribution = attributionOfCapture("imageinfo-multi-licensed.json")

        // Then - the licence is the one stated, and no second licence is guessed
        assertEquals("GFDL 1.2", attribution.licence)
        assertEquals(emptyList<String>(), attribution.otherLicences)
    }

    @Test
    fun `an http licence URL is kept verbatim beside the other facts`() = runTest {
        // Given - the live answer for a file whose LicenseUrl is an http link to gnu.org

        // When - it is mapped to an attribution
        val attribution = attributionOfCapture("imageinfo-multi-licensed.json")

        // Then - the link is carried as the upstream wrote it, with the licence name, creator and page
        assertEquals("http://www.gnu.org/licenses/old-licenses/fdl-1.2.html", attribution.licenceUrl)
        assertEquals("GFDL 1.2", attribution.licence)
        assertEquals("Ralf Roletschek", attribution.creator)
        assertNotNull(attribution.sourceUrl)
    }

    @Test
    fun `a file with a reuse restriction carries the keyword and still carries its licence`() = runTest {
        // Given - the live answer for a CC BY-SA photograph of a person, with Restrictions of personality

        // When - it is mapped to an attribution
        val attribution = attributionOfCapture("imageinfo-personality-restricted.json")

        // Then - the restriction is listed beside the licence rather than replacing it
        assertEquals(listOf("personality"), attribution.restrictions)
        assertEquals("CC BY-SA 3.0", attribution.licence)
        assertEquals("https://creativecommons.org/licenses/by-sa/3.0", attribution.licenceUrl)
    }

    @Test
    fun `a non-free file carries its fair use terms and a non-free restriction`() = runTest {
        // Given - the live answer for an album cover uploaded locally to English Wikipedia as fair use

        // When - it is mapped to an attribution
        val attribution = attributionOfCapture("imageinfo-non-free.json")

        // Then - the licence is Fair use, the restriction says non-free, and the creator and credit stay
        assertEquals("Fair use", attribution.licence)
        assertEquals(listOf("non-free"), attribution.restrictions)
        assertEquals("Radiohead", attribution.creator)
        assertEquals("https://www.spin.com/2017/06/radiohead-oknotok-review-ok-computer/", attribution.credit)
        assertNull(attribution.licenceUrl)
    }

    @Test
    fun `a file the wiki does not know yields no file info`() = runTest {
        // Given - the live answer for a title that is not a file

        // When - the file info is read
        val info = fileInfoFrom(UpstreamPools.body(POOL, "imageinfo-missing-file.json"))

        // Then - there is nothing to map
        assertNull(info)
    }

    @Test
    fun `an answer with no extmetadata still names the file and its description page`() = runTest {
        // Given - a copy of the Radiohead capture whose extmetadata is empty (derived)

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(emptyMap())

        // Then - the title and the description page remain, and every other fact is absent
        assertEquals(
            Attribution(
                title = "File:RadioheadO2211125 composite.jpg",
                sourceUrl = "https://commons.wikimedia.org/wiki/File:RadioheadO2211125_composite.jpg",
            ),
            attribution,
        )
    }

    @Test
    fun `a partial answer keeps the one fact it has`() = runTest {
        // Given - a copy of the Radiohead capture whose extmetadata holds only an Artist (derived)

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(mapOf("Artist" to "Raph_PH"))

        // Then - the creator is kept and the licence facts are null
        assertEquals("Raph_PH", attribution.creator)
        assertNull(attribution.licence)
        assertNull(attribution.licenceUrl)
    }

    @Test
    fun `an unfamiliar licence is kept as the upstream wrote it`() = runTest {
        // Given - a copy of the Radiohead capture naming a licence no code here has heard of (derived)
        val fields = mapOf("LicenseShortName" to "Studio Terms 7", "LicenseUrl" to "https://example.org/studio-terms-7")

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields)

        // Then - the name and the link are carried unchanged
        assertEquals("Studio Terms 7", attribution.licence)
        assertEquals("https://example.org/studio-terms-7", attribution.licenceUrl)
    }

    @Test
    fun `a licence statement naming two licences is kept whole and not split or picked from`() = runTest {
        // Given - a copy of the Radiohead capture whose short name lists two licences (derived)
        val fields = mapOf("LicenseShortName" to "CC BY-SA 3.0 / GFDL 1.2 or later")

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields)

        // Then - the statement is the licence verbatim and no alternative list is built from it
        assertEquals("CC BY-SA 3.0 / GFDL 1.2 or later", attribution.licence)
        assertEquals(emptyList<String>(), attribution.otherLicences)
    }

    @Test
    fun `contradictory flags are all carried and none decides the others`() = runTest {
        // Given - a copy of the Radiohead capture saying CC BY, not copyrighted, non-free and trademarked (derived)
        val fields = mapOf(
            "LicenseShortName" to "CC BY 4.0",
            "Copyrighted" to "False",
            "NonFree" to "true",
            "Restrictions" to "trademarked|personality",
        )

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields)

        // Then - the licence, the status and every restriction appear as stated
        assertEquals("CC BY 4.0", attribution.licence)
        assertEquals("False", attribution.copyrightStatus)
        assertEquals(listOf("trademarked", "personality", "non-free"), attribution.restrictions)
    }

    @Test
    fun `the licence falls back to the usage terms and then to the licence slug`() = runTest {
        // Given - two copies of the Radiohead capture (derived), one without a short name and one with only a slug
        val withTerms = mapOf("UsageTerms" to "Creative Commons Attribution 4.0", "License" to "cc-by-4.0")
        val slugOnly = mapOf("License" to "cc-by-4.0")

        // When - both are mapped to attributions
        val fromTerms = attributionOfDerived(withTerms)
        val fromSlug = attributionOfDerived(slugOnly)

        // Then - each carries the most descriptive statement it has
        assertEquals("Creative Commons Attribution 4.0", fromTerms.licence)
        assertEquals("cc-by-4.0", fromSlug.licence)
    }

    @Test
    fun `markup and entities in an Artist field are reduced to plain text`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist is nested HTML with entities and a script (derived)
        val html = "<bdi><a href=\"https://example.org\"><span title=\"x\">Ana &amp; Bo</span></a></bdi>" +
            "<script>alert(1)</script><br>&#169; 2025 &lt;b&gt;&nbsp;Studio"

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(mapOf("Artist" to html))

        // Then - tags and the script are gone, entities decoded once, and whitespace collapsed
        assertEquals("Ana & Bo © 2025 <b> Studio", attribution.creator)
    }

    @Test
    fun `an unsafe description page URL and licence URL are carried as given, trimmed`() = runTest {
        // Given - a copy of the Radiohead capture with a javascript description page and a protocol-relative licence link, both padded with spaces (derived)
        val fields = mapOf(
            "Artist" to "Raph_PH",
            "LicenseShortName" to "CC BY 4.0",
            "LicenseUrl" to " //creativecommons.org/licenses/by/4.0 ",
        )

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields, descriptionUrl = " javascript:alert(1) ")

        // Then - each link is the upstream's text minus the padding, and the creator and licence stay
        assertEquals("javascript:alert(1)", attribution.sourceUrl)
        assertEquals("//creativecommons.org/licenses/by/4.0", attribution.licenceUrl)
        assertEquals("Raph_PH", attribution.creator)
        assertEquals("CC BY 4.0", attribution.licence)
    }

    @Test
    fun `a field whose value is JSON null is treated as absent and not as the text null`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist value is JSON null (derived)
        val info = JSONObject(UpstreamPools.body(POOL, "imageinfo-radiohead-lead.json"))
        info.getJSONObject("query").getJSONArray("pages").getJSONObject(0)
            .getJSONArray("imageinfo").getJSONObject(0).getJSONObject("extmetadata")
            .put("Artist", JSONObject().put("value", JSONObject.NULL))

        // When - it is mapped to an attribution
        val attribution = checkNotNull(fileInfoFrom(info.toString())).toFileAttribution()

        // Then - there is no creator, and the licence is still read
        assertNull(attribution.creator)
        assertEquals("CC BY 4.0", attribution.licence)
    }

    @Test
    fun `a value of 240000 literal angle brackets is read in well under a second and is capped`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist is 240000 '<' characters and whose licence is intact (derived)
        val hostile = "<".repeat(240_000)

        // When - it is mapped to an attribution within 2 s
        val attribution = attributionOfWithin2s(hostile)

        // Then - the brackets are text, cut at the cap with an ellipsis, and the licence is still read
        assertEquals("<".repeat(MAX_FIELD_CHARS) + "\u2026", attribution.creator)
        assertEquals("CC BY 4.0", attribution.licence)
    }

    @Test
    fun `a value of 80000 unclosed tag openers is read in well under a second`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist is 80000 '<a ' openers that never close (derived)
        val hostile = "<a ".repeat(80_000)

        // When - it is mapped to an attribution within 2 s
        val attribution = attributionOfWithin2s(hostile)

        // Then - the first unclosed tag swallowed the rest, so there is no creator, and the licence is still read
        assertNull(attribution.creator)
        assertEquals("CC BY 4.0", attribution.licence)
    }

    @Test
    fun `a value of 240000 unclosed script elements is read in well under a second`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist is 240000 '<script>' openers and whose licence is intact (derived)
        val hostile = "<script>".repeat(240_000)

        // When - it is mapped to an attribution within 2 s
        val attribution = attributionOfWithin2s(hostile)

        // Then - the first unclosed script swallowed the rest, so there is no creator, and the licence is still read
        assertNull(attribution.creator)
        assertEquals("CC BY 4.0", attribution.licence)
    }

    @Test
    fun `a value over the field cap is cut there and ends in an ellipsis, and the other fields are intact`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist is 500 characters past the cap (derived)
        val fields = mapOf(
            "Artist" to "A".repeat(MAX_FIELD_CHARS + 500),
            "Credit" to "Own work",
            "LicenseShortName" to "CC BY 4.0",
        )

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields)

        // Then - the creator is the first cap characters and an ellipsis, and the other facts are whole
        assertEquals("A".repeat(MAX_FIELD_CHARS) + "\u2026", attribution.creator)
        assertEquals("Own work", attribution.credit)
        assertEquals("CC BY 4.0", attribution.licence)
    }

    @Test
    fun `a value exactly at the field cap is kept whole with no ellipsis`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist is exactly the cap long (derived)
        val fields = mapOf("Artist" to "A".repeat(MAX_FIELD_CHARS))

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields)

        // Then - the creator is the whole value
        assertEquals("A".repeat(MAX_FIELD_CHARS), attribution.creator)
    }

    @Test
    fun `a cut that falls inside a surrogate pair drops the half character and still ends in an ellipsis`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist has an emoji straddling the cap (derived)
        val fields = mapOf("Artist" to "A".repeat(MAX_FIELD_CHARS - 1) + "\uD83D\uDE00 tail")

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields)

        // Then - no lone surrogate is left before the ellipsis
        assertEquals("A".repeat(MAX_FIELD_CHARS - 1) + "\u2026", attribution.creator)
    }

    @Test
    fun `a tag with no closing bracket drops the rest of the value, as a browser reads it`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist ends in a tag that never closes (derived)
        val fields = mapOf("Artist" to "Ana &amp; Bo <a href=\"https://example.org\" Studio")

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields)

        // Then - the text before the tag is kept and nothing after it is
        assertEquals("Ana & Bo", attribution.creator)
    }

    @Test
    fun `a script element with no closing tag drops the rest of the value`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist has an opened script and no close (derived)
        val fields = mapOf("Artist" to "Ana <script>alert(1) Studio")

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields)

        // Then - only the text before the script is kept
        assertEquals("Ana", attribution.creator)
    }

    @Test
    fun `script and style blocks close case-insensitively and a closing tag may carry spaces`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist has an upper-case style and a script closed with spaces (derived)
        val fields = mapOf(
            "Artist" to "Ana<STYLE type=\"text/css\">a > b { }</STYLE>Bo<script>x</script   >Cy",
        )

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields)

        // Then - both blocks are gone and each leaves a space between its neighbours
        assertEquals("Ana Bo Cy", attribution.creator)
    }

    @Test
    fun `block tags separate words and inline tags do not`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist mixes block tags, inline tags and a pre element (derived)
        val fields = mapOf("Artist" to "a<p>b</p>c<b>d</b>e<pre>f</pre>g<br/>h")

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields)

        // Then - paragraph and break tags leave a space and bold and pre tags leave none
        assertEquals("a b cdefg h", attribution.creator)
    }

    @Test
    fun `entities decode once and an entity that is malformed or out of range stays as written`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist has valid, double-encoded, unknown, malformed and out-of-range entities (derived)
        val fields = mapOf(
            "Artist" to "&amp;lt; &#x41;&#66;&#X43; &bogus; &#xZZ; &#1114112; AT&T &amp",
        )

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields)

        // Then - only the first layer is decoded and every non-entity is kept character for character
        assertEquals("&lt; ABC &bogus; &#xZZ; &#1114112; AT&T &amp", attribution.creator)
    }

    @Test
    fun `a less-than or greater-than sign in prose is text and not a tag`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist has bare angle brackets beside one real tag (derived)
        val fields = mapOf("Artist" to "a < b and c > d, x <3 you <b>ok</b>, 1<2")

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields)

        // Then - the brackets stay, and only the real tag is removed
        assertEquals("a < b and c > d, x <3 you ok, 1<2", attribution.creator)
    }

    @Test
    fun `a quoted attribute value may hold a greater-than sign and the tag still ends at its own bracket`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist is a link whose title holds '>' and one with a bare O'Brien (derived)
        val fields = mapOf("Artist" to "<a title=\"a>b\" href='x>y'>Bob</a> <span title=O'Brien>Cy</span>")

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields)

        // Then - the attribute text is not emitted and the apostrophe in the bare value swallowed nothing
        assertEquals("Bob Cy", attribution.creator)
    }

    @Test
    fun `a comment is dropped whole, and one with no end drops the rest of the value`() = runTest {
        // Given - a copy of the Radiohead capture with a closed comment holding '>' and then an unclosed one (derived)
        val fields = mapOf("Artist" to "Ana<!-- a > b -->Bo<!-- never closed Studio")

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields)

        // Then - the closed comment leaves nothing behind and the unclosed one takes the rest
        assertEquals("AnaBo", attribution.creator)
    }

    @Test
    fun `a numeric entity for NUL, a surrogate or a control stays as written`() = runTest {
        // Given - a copy of the Radiohead capture whose Artist has entities for NUL, a high surrogate, C0 and C1 controls, and a tab (derived)
        val fields = mapOf("Artist" to "a&#0;b&#xD800;c&#55357;d&#1;e&#x85;f&#127;g&#9;h")

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields)

        // Then - the unsafe entities are text, only the tab decodes (to a space), and no NUL or lone surrogate appears
        assertEquals("a&#0;b&#xD800;c&#55357;d&#1;e&#x85;f&#127;g h", attribution.creator)
        assertTrue(attribution.creator!!.none { it == '\u0000' || it.isSurrogate() })
    }

    private companion object {
        const val POOL = "wikipedia-file-attribution"
    }
}
