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
import org.junit.Test

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
    private suspend fun attributionOfDerived(fields: Map<String, String>, descriptionUrl: String? = null): Attribution {
        val body = JSONObject(UpstreamPools.body(POOL, "imageinfo-radiohead-lead.json"))
        val info = body.getJSONObject("query").getJSONArray("pages").getJSONObject(0)
            .getJSONArray("imageinfo").getJSONObject(0)
        val extmetadata = JSONObject()
        fields.forEach { (name, value) -> extmetadata.put(name, JSONObject().put("value", value)) }
        info.put("extmetadata", extmetadata)
        if (descriptionUrl != null) info.put("descriptionurl", descriptionUrl)
        return checkNotNull(fileInfoFrom(body.toString())).toFileAttribution()
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
    fun `a licence URL that is not https is left out and the other facts stay`() = runTest {
        // Given - the live answer for a file whose LicenseUrl is an http link to gnu.org

        // When - it is mapped to an attribution
        val attribution = attributionOfCapture("imageinfo-multi-licensed.json")

        // Then - the link is absent while the licence name, creator and description page remain
        assertNull(attribution.licenceUrl)
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
    fun `an unsafe description page URL and licence URL are left out while the text facts stay`() = runTest {
        // Given - a copy of the Radiohead capture with a javascript description page and a protocol-relative licence link (derived)
        val fields = mapOf(
            "Artist" to "Raph_PH",
            "LicenseShortName" to "CC BY 4.0",
            "LicenseUrl" to "//creativecommons.org/licenses/by/4.0",
        )

        // When - it is mapped to an attribution
        val attribution = attributionOfDerived(fields, descriptionUrl = "javascript:alert(1)")

        // Then - neither link is carried and the creator and licence are
        assertNull(attribution.sourceUrl)
        assertNull(attribution.licenceUrl)
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

    private companion object {
        const val POOL = "wikipedia-file-attribution"
    }
}
