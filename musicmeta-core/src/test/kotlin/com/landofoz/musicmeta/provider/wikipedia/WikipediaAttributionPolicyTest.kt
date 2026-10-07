package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.zip.GZIPInputStream

class WikipediaAttributionPolicyTest {
    @Test
    fun `oversized metadata and invalid canonical identity cannot become credit`() = runTest {
        // Given - synthetic metadata too large to normalize and a control byte in the canonical identity
        val oversized = variant("Artist" to "x".repeat(32769))
        val invalidTitle = variant()
        page(invalidTitle).put("title", "File:X\u0000.jpg")

        // When - parsing both untrusted boundaries
        val credit = read(oversized)?.artist
        val failure = runCatching { read(invalidTitle) }.exceptionOrNull()

        // Then - oversized credit is absent and an invalid selected identity is a protocol failure
        assertNull(credit)
        assertTrue(failure is IOException)
    }

    @Test
    fun `attribution requirement retains explicit true false and unknown values`() = runTest {
        // Given - source values report both boolean values and two forms of unknown
        val json = listOf("true", "False", "unsupported", null)

        // When - parsing the source requirement without consulting custom credit
        val flags = json.map { read(variant("AttributionRequired" to it))?.attributionRequired }

        // Then - false and unknown remain distinct from true
        assertEquals(listOf(true, false, null, null), flags)
    }

    @Test
    fun `missing and malformed metadata values never assert unrestricted reuse`() = runTest {
        // Given - synthetic absent metadata and a malformed Restrictions entry
        val noMetadata = capture()
        page(noMetadata).getJSONArray("imageinfo").getJSONObject(0).remove("extmetadata")
        val malformedRestrictions = variant()
        page(malformedRestrictions).getJSONArray("imageinfo").getJSONObject(0)
            .getJSONObject("extmetadata").put("Restrictions", JSONObject())

        // When - parsing the incomplete source records
        val absent = read(noMetadata)
        val malformed = requireNotNull(read(malformedRestrictions))

        // Then - no metadata is safe absence and a malformed restriction stays unknown
        assertNull(absent)
        assertNull(malformed.restrictions)
        assertNull(WikipediaMapper.toFileAttribution(malformed))
    }

    @Test
    fun `metadata transport and Action API errors stay bounded protocol failures`() = runTest {
        // Given - a request rejection and a 200 Action API error with untrusted diagnostic text
        val rejection = FakeHttpClient().apply { givenHttpResult("imageinfo", HttpResult.ClientError(400)) }
        val actionError = FakeHttpClient().apply {
            givenJsonResponse("imageinfo", JSONObject().put("error", JSONObject().put("code", "x".repeat(10000))).toString())
        }

        // When - reading the selected file through both failing routes
        val failures = listOf(rejection, actionError).map { http ->
            runCatching { WikipediaApi(http, RateLimiter(0)).getFileMetadata("File:X.jpg") }.exceptionOrNull()
        }

        // Then - request failures cannot become absence or echo unbounded upstream diagnostics
        assertTrue(failures.all { it is IOException && requireNotNull(it.message).length < 120 })
    }

    @Test
    fun `metadata cancellation cannot become a normal return`() = runTest {
        // Given - an HTTP operation suspended beyond its caller's deadline
        val delegate = FakeHttpClient()
        val http = object : HttpClient by delegate {
            override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> {
                delay(100)
                return HttpResult.Ok(capture())
            }
        }
        var returnedNormally = false

        // When - the caller cancels its metadata request
        withTimeoutOrNull(1) {
            WikipediaApi(http, RateLimiter(0)).getFileMetadata("File:X.jpg")
            returnedNormally = true
        }

        // Then - cancellation unwinds the route before it can report a result
        assertEquals(false, returnedNormally)
    }

    @Test
    fun `file metadata rejects empty multiple and control character titles before transport`() = runTest {
        // Given - titles that cannot identify exactly one file
        val http = FakeHttpClient()
        val titles = listOf("File: ", "File:X|File:Y", "File:X\u0000.jpg")

        // When - requesting metadata for these unsupported inputs
        val failures = titles.map {
            runCatching { WikipediaApi(http, RateLimiter(0)).getFileMetadata(it) }.exceptionOrNull()
        }

        // Then - each input is explicitly rejected without a network operation
        assertTrue(failures.all { it is IllegalArgumentException })
        assertTrue(http.requestedUrls.isEmpty())
    }

    @Test
    fun `captured selected Commons file retains canonical identity and reported flags`() = runTest {
        // Given - the complete response captured before implementation
        val json = capture()

        // When - parsing the selected file through the actual request route
        val metadata = read(json)
        val attribution = WikipediaMapper.toFileAttribution(requireNotNull(metadata))

        // Then - canonical identity and source-required credit survive without inferred flags
        assertEquals("File:RadioheadO2211125 composite.jpg", metadata.title)
        assertTrue(requireNotNull(metadata.descriptionPageUrl).startsWith("https://commons.wikimedia.org/wiki/File:"))
        assertEquals(true, metadata.copyrighted)
        assertNull(metadata.nonFree)
        assertEquals(emptyList<String>(), metadata.restrictions)
        assertEquals("CC BY 4.0", metadata.licenseShortName)
        assertTrue(!requireNotNull(metadata.artist).contains("<"))
        assertNull(attribution)
    }

    @Test
    fun `invalid boolean values remain unknown`() = runTest {
        // Given - an upstream value that is neither explicit true nor explicit false
        val json = variant("Copyrighted" to "unknown", "NonFree" to "0")

        // When - parsing source flags
        val metadata = requireNotNull(read(json))

        // Then - malformed booleans do not claim absence of copyright or restrictions
        assertNull(metadata.copyrighted)
        assertNull(metadata.nonFree)
    }

    @Test
    fun `public domain honors explicit false without requiring a creator`() = runTest {
        // Given - synthetic public-domain metadata with explicit negative flags
        val json = variant("Copyrighted" to "False", "AttributionRequired" to "False", "NonFree" to "False",
            "LicenseShortName" to "Public domain", "License" to "pd", "LicenseUrl" to "", "Artist" to "",
            "UsageTerms" to "Public domain")

        // When - building file attribution
        val attribution = WikipediaMapper.toFileAttribution(requireNotNull(read(json)))

        // Then - public-domain facts are retained without inventing an attribution requirement
        requireNotNull(attribution)
        assertEquals(false, attribution.copyrighted)
        assertEquals(false, attribution.attributionRequired)
        assertNull(attribution.creator)
    }

    @Test
    fun `custom Attribution overrides artist credit without changing reported requirement`() = runTest {
        // Given - synthetic custom credit and an explicitly false attribution flag
        val json = variant("Attribution" to "<b>Chosen credit</b>", "AttributionRequired" to "False")

        // When - reading metadata independently of its eventual reuse policy
        val metadata = requireNotNull(read(json))

        // Then - custom credit remains available while the contradictory reuse record is refused
        assertEquals("Chosen credit", metadata.attribution)
        assertNull(WikipediaMapper.toFileAttribution(metadata))
    }

    @Test
    fun `rejected present custom attribution suppresses otherwise reusable fallback credit`() = runTest {
        // Given - captured reusable metadata with only its present custom credit corrupted
        val cases = listOf(
            variant("Attribution" to "x".repeat(32_769), "Artist" to "Fallback artist"),
            variant("Attribution" to "", "Artist" to "Fallback artist"),
            variant("Attribution" to "<script>hidden</script>", "Artist" to "Fallback artist"),
            variant("Attribution" to "\u0000", "Artist" to "Fallback artist"),
            variant("Artist" to "Fallback artist").also { json ->
                page(json).getJSONArray("imageinfo").getJSONObject(0).getJSONObject("extmetadata")
                    .put("Attribution", JSONObject().put("value", JSONObject()))
            },
        )

        // When - parsing the corrupt custom credit before applying file reuse policy
        val metadata = cases.map { requireNotNull(read(it)) }
        val attributions = metadata.map(WikipediaMapper::toFileAttribution)

        // Then - rejected custom credit cannot become an absent value that falls back to Artist
        assertTrue(metadata.all { it.attributionState == WikipediaAttributionState.PRESENT_REJECTED })
        assertTrue(attributions.all { it == null })
    }

    @Test
    fun `absent custom attribution permits artist fallback while valid custom credit overrides it`() = runTest {
        // Given - captured reusable metadata with custom credit absent or present and valid
        val absent = variant("Attribution" to null, "Artist" to "Fallback artist")
        val custom = variant("Attribution" to "Chosen credit", "Artist" to "Fallback artist")

        // When - applying file reuse policy to each source representation
        val absentMetadata = requireNotNull(read(absent))
        val customMetadata = requireNotNull(read(custom))
        val absentAttribution = WikipediaMapper.toFileAttribution(absentMetadata)
        val customAttribution = WikipediaMapper.toFileAttribution(customMetadata)

        // Then - absence uses Artist while a valid custom value remains the overriding credit
        assertEquals(WikipediaAttributionState.ABSENT, absentMetadata.attributionState)
        assertEquals(WikipediaAttributionState.PRESENT_VALID, customMetadata.attributionState)
        assertEquals("Fallback artist", requireNotNull(absentAttribution).creator)
        assertNull(absentAttribution.attributionText)
        assertEquals("Chosen credit", requireNotNull(customAttribution).attributionText)
    }

    @Test
    fun `restricted nonfree and unknown records cannot become reusable attribution`() = runTest {
        // Given - synthetic restricted, non-free and unreported flags
        val cases = listOf(variant("Restrictions" to "trademark|personality"),
            variant("NonFree" to "True"), variant("NonFree" to ""), variant("Copyrighted" to ""),
            variant("AttributionRequired" to ""), variant("Restrictions" to null))

        // When - applying reuse policy to each case
        val attributions = cases.map { WikipediaMapper.toFileAttribution(requireNotNull(read(it))) }

        // Then - every incomplete or prohibited case is withheld
        assertTrue(attributions.all { it == null })
    }

    @Test
    fun `ambiguous multiple and inconsistent licenses fail closed`() = runTest {
        // Given - synthetic combined designations and conflicting short-name and license codes
        val cases = listOf(variant("LicenseShortName" to "CC BY-SA 4.0 / GFDL"),
            variant("License" to "cc-by-4.0|gfdl"), variant("License" to "gfdl"),
            variant("LicenseUrl" to "https://example.test/unrelated"), variant("LicenseShortName" to "Custom"))

        // When - applying reuse policy without guessing a license relationship
        val attributions = cases.map { WikipediaMapper.toFileAttribution(requireNotNull(read(it))) }

        // Then - no uncertain license is asserted reusable
        assertTrue(attributions.all { it == null })
    }

    @Test
    fun `contradictory usage terms cannot turn a recognized license into reusable credit`() = runTest {
        // Given - synthetic all-rights-reserved terms beside an otherwise complete CC BY license
        val json = variant("UsageTerms" to "All rights reserved")

        // When - assessing the complete set of file metadata
        val attribution = WikipediaMapper.toFileAttribution(requireNotNull(read(json)))

        // Then - a familiar short name cannot erase contradictory source terms
        assertNull(attribution)
    }

    @Test
    fun `malformed HTML entities and controls become safe plain text`() = runTest {
        // Given - synthetic malformed markup, executable content and encoded control bytes
        val json = variant("Artist" to "Alice&nbsp;&#x26; Bob<script>alert(1)</script><b title='>'> C</b>\u0000&#10;&#60;img&#62;<broken")

        // When - normalizing metadata at the parser boundary
        val metadata = requireNotNull(read(json))

        // Then - readable credit survives without tags, script contents or controls
        assertEquals("Alice & Bob C img", metadata.artist)
    }

    @Test
    fun `unsafe credential control and non HTTPS links are rejected`() = runTest {
        // Given - synthetic URLs whose apparent HTTPS prefix does not establish safe links
        val urls = listOf("https://user:secret@example.test/file", "https://example.test/%0Afile",
            "https://example.test/\u0000file", "http://example.test/file", "//example.test/file",
            "javascript:alert(1)", "https://example.test/%7Ffile")

        // When - parsing each source and license link
        val records = urls.map { requireNotNull(read(variant("LicenseUrl" to it, descriptionUrl = it))) }

        // Then - neither metadata link accepts a hazardous URL
        assertTrue(records.all { it.licenseUrl == null && it.descriptionPageUrl == null })
    }

    @Test
    fun `local repository redirects and multilingual canonical titles retain selected identity`() = runTest {
        // Given - synthetic local-file metadata whose requested title redirects to a Unicode canonical title
        val json = variant(descriptionUrl = "https://en.wikipedia.org/wiki/File:%E6%9D%B1%E4%BA%AC.jpg")
        json.getJSONObject("query").put("redirects", listOf(mapOf("from" to "File:Old.jpg", "to" to "File:東京.jpg")))
        page(json).put("title", "File:東京.jpg").put("imagerepository", "local")

        // When - reading the selected metadata
        val attribution = WikipediaMapper.toFileAttribution(requireNotNull(read(json)))

        // Then - local description identity and canonical title are preserved together
        requireNotNull(attribution)
        assertEquals("File:東京.jpg", attribution.resourceId)
        assertEquals("https://en.wikipedia.org/wiki/File:%E6%9D%B1%E4%BA%AC.jpg", attribution.sourceUrl)
    }

    @Test
    fun `missing metadata is safe absence while malformed response is a protocol error`() = runTest {
        // Given - a missing file and a response whose query envelope disappeared
        val missing = JSONObject("""{"query":{"pages":[{"title":"File:Missing.jpg","missing":true}]}}""")
        val malformed = JSONObject("""{"unexpected":true}""")

        // When - reading absent and malformed metadata separately
        val absent = read(missing)
        val failure = runCatching { read(malformed) }.exceptionOrNull()

        // Then - absence is withheld and a protocol failure remains visible
        assertNull(absent)
        assertTrue(failure is IOException)
    }

    @Test
    fun `article links encode reserved delimiters and Unicode title data`() {
        // Given - an article title containing characters that otherwise change URL structure
        val summary = WikipediaSummary("東京 #1 &?/%", "Text", null, null, null)

        // When - mapping the article attribution
        val bio = WikipediaMapper.toBiography(summary)

        // Then - the source points to the article using a safe encoded path
        assertEquals("https://en.wikipedia.org/wiki/%E6%9D%B1%E4%BA%AC_%231_%26%3F%2F%25",
            bio.attribution?.sourceUrl)
        assertNull(bio.thumbnailUrl)
    }

    private suspend fun read(json: JSONObject): WikipediaFileMetadata? {
        val http = FakeHttpClient().apply { givenJsonResponse("imageinfo", json.toString()) }
        return WikipediaApi(http, RateLimiter(0)).getFileMetadata("File:RadioheadO2211125_composite.jpg")
    }

    private fun capture(): JSONObject {
        val encoded = requireNotNull(javaClass.getResourceAsStream("/wikipedia-attribution/selected-imageinfo.json.gz.base64"))
            .bufferedReader().use { it.readText() }
        return GZIPInputStream(Base64.getMimeDecoder().decode(encoded).inputStream())
            .bufferedReader().use { JSONObject(it.readText()) }
    }

    private fun page(json: JSONObject): JSONObject = json.getJSONObject("query").getJSONArray("pages").getJSONObject(0)

    // Adversarial variants are synthetic; only capture() is an unmodified upstream response.
    private fun variant(vararg values: Pair<String, String?>, descriptionUrl: String? = null): JSONObject {
        val json = capture()
        val info = page(json).getJSONArray("imageinfo").getJSONObject(0)
        val metadata = info.getJSONObject("extmetadata")
        metadata.put("NonFree", JSONObject().put("value", "False"))
        for ((name, value) in values) {
            if (value == null) metadata.remove(name) else metadata.put(name, JSONObject().put("value", value))
        }
        if (descriptionUrl != null) info.put("descriptionurl", descriptionUrl)
        return json
    }
}
