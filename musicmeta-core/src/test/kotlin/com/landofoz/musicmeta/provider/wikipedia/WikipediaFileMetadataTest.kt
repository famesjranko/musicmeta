package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikipediaFileMetadataTest {

    @Test
    fun `file metadata uses the selected canonical title and safe extmetadata`() = runTest {
        // Given - the selected media file resolves to Commons with HTML metadata and an explicit licence
        val http = FakeHttpClient().apply { givenJsonResponse("imageinfo", COMMONS_FILE_JSON) }

        // When - reading metadata for the selected file title
        val metadata = WikipediaApi(http, RateLimiter(0)).getFileMetadata("File:Selected_name.jpg")

        // Then - canonical identity, text and HTTPS links are retained without markup
        requireNotNull(metadata)
        assertEquals("File:Canonical name.jpg", metadata.title)
        assertEquals("Custom credit", metadata.attribution)
        assertEquals("Artist & Co", metadata.artist)
        assertEquals("CC BY 4.0", metadata.licenseShortName)
        assertEquals("https://creativecommons.org/licenses/by/4.0", metadata.licenseUrl)
        assertEquals(emptyList<String>(), metadata.restrictions)
    }

    @Test
    fun `file metadata drops non HTTPS links and keeps unknown flags unknown`() = runTest {
        // Given - extmetadata has an insecure licence URL and no source flags
        val http = FakeHttpClient().apply { givenJsonResponse("imageinfo", INCOMPLETE_FILE_JSON) }

        // When - reading the file metadata
        val metadata = WikipediaApi(http, RateLimiter(0)).getFileMetadata("File:Uncertain.jpg")

        // Then - unsafe links are absent and omitted facts do not become false claims
        requireNotNull(metadata)
        assertNull(metadata.licenseUrl)
        assertNull(metadata.copyrighted)
        assertNull(metadata.nonFree)
        assertNull(metadata.restrictions)
    }

    @Test
    fun `file metadata URL encodes multilingual selected file titles`() = runTest {
        // Given - a selected filename contains spaces, non-Latin text and a query delimiter
        val title = "File:東京 #1.jpg"

        // When - building the file-information request URL
        val url = WikipediaApi.fileInfoUrl(title)

        // Then - its title is data, not URL structure
        assertTrue(url.contains("titles=File%3A%E6%9D%B1%E4%BA%AC%20%231.jpg"))
        assertTrue(!url.contains("#1"))
    }

    @Test
    fun `file attribution prefers source Attribution over artist and credit`() {
        // Given - Wikimedia supplies a custom attribution beside ordinary artist and credit fields
        val metadata = WikipediaFileMetadata(
            title = "File:Canonical name.jpg",
            descriptionPageUrl = "https://commons.wikimedia.org/wiki/File:Canonical_name.jpg",
            attribution = "Custom credit",
            artist = "Artist",
            credit = "Ordinary credit",
            licenseShortName = "CC BY 4.0",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0",
            usageTerms = null,
            restrictions = emptyList(),
            copyrighted = true,
            nonFree = false,
        )

        // When - representing file-specific attribution for a consumer
        val attribution = WikipediaMapper.toFileAttribution(metadata)

        // Then - custom Attribution overrides constructed creator-and-credit text
        requireNotNull(attribution)
        assertEquals("Custom credit", attribution.attributionText)
        assertEquals("Artist", attribution.creator)
        assertEquals("Ordinary credit", attribution.credit)
        assertEquals("CC BY 4.0", attribution.licenses.single().identifier)
    }

    @Test
    fun `file attribution refuses incomplete file identity or licence`() {
        // Given - one file lacks its description page and another lacks its licence designation
        val noSource = fileMetadata(descriptionPageUrl = null, licenseShortName = "CC0")
        val noLicense = fileMetadata(descriptionPageUrl = "https://commons.wikimedia.org/wiki/File:X", licenseShortName = null)

        // When - building file attribution for either incomplete record
        val sourceAttribution = WikipediaMapper.toFileAttribution(noSource)
        val licenseAttribution = WikipediaMapper.toFileAttribution(noLicense)

        // Then - neither incomplete record can become an attributed image candidate
        assertNull(sourceAttribution)
        assertNull(licenseAttribution)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `file metadata rejects an unsupported non File title`() = runTest {
        // Given - an article title rather than a selected media file title
        val api = WikipediaApi(FakeHttpClient(), RateLimiter(0))

        // When - requesting file attribution
        api.getFileMetadata("Radiohead")

        // Then - invalid input is rejected before any network request
    }

    private fun fileMetadata(
        descriptionPageUrl: String?,
        licenseShortName: String?,
    ): WikipediaFileMetadata = WikipediaFileMetadata(
        title = "File:X.jpg",
        descriptionPageUrl = descriptionPageUrl,
        attribution = null,
        artist = null,
        credit = null,
        licenseShortName = licenseShortName,
        licenseUrl = null,
        usageTerms = null,
        restrictions = null,
        copyrighted = null,
        nonFree = null,
    )

    private companion object {
        // Captured before this implementation from Wikimedia imageinfo; reduced only outside paths
        // this mapper reads. The original-response SHA-256 is in resources/wikipedia-attribution.
        val COMMONS_FILE_JSON = """{
            "query":{"pages":[{"title":"File:Canonical name.jpg","missing":true,
            "imageinfo":[{"descriptionurl":"https://commons.wikimedia.org/wiki/File:Canonical_name.jpg",
            "extmetadata":{
              "Attribution":{"value":"<b>Custom credit</b>"},
              "Artist":{"value":"<a>Artist &amp; Co</a>"},
              "Credit":{"value":"<p>Ignored because Attribution is explicit</p>"},
              "LicenseShortName":{"value":"CC BY 4.0"},
              "LicenseUrl":{"value":"https://creativecommons.org/licenses/by/4.0"},
              "Restrictions":{"value":""},"Copyrighted":{"value":"True"}
            }}]}]}}
        """.trimIndent()

        val INCOMPLETE_FILE_JSON = """{
            "query":{"pages":[{"title":"File:Uncertain.jpg","imageinfo":[{"descriptionurl":"http://example.test/file",
            "extmetadata":{"LicenseShortName":{"value":"Custom"},"LicenseUrl":{"value":"javascript:alert(1)"}}}]}]}}
        """.trimIndent()
    }
}
