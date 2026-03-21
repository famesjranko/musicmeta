package com.landofoz.musicmeta.provider.wikidata

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WikidataProviderTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: WikidataProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = WikidataProvider(httpClient, RateLimiter(0L))
    }

    @Test
    fun `enrich returns artist photo URL from P18 property`() = runTest {
        // Given
        val wikidataId = "Q44802"
        httpClient.givenJsonResponse(
            "wikidata.org",
            """{"claims":{"P18":[{"mainsnak":{"datavalue":{"value":"Radiohead 2016.jpg","type":"string"}}}]}}""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = wikidataId),
            name = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("wikidata", success.provider)
        assertEquals(0.9f, success.confidence, 0.01f)
        val artwork = success.data as EnrichmentData.Artwork
        assertTrue(artwork.url.contains("Radiohead"))
        assertTrue(artwork.url.contains("width=1200"))
    }

    @Test
    fun `enrich returns NotFound when no wikidataId in identifiers`() = runTest {
        // Given
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(),
            name = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
        val notFound = result as EnrichmentResult.NotFound
        assertEquals("wikidata", notFound.provider)
    }

    @Test
    fun `enrich returns NotFound when P18 property missing`() = runTest {
        // Given
        val wikidataId = "Q99999"
        httpClient.givenJsonResponse("wikidata.org", """{"claims":{}}""")

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = wikidataId),
            name = "Unknown",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich handles SVG files by appending png extension`() = runTest {
        // Given
        val wikidataId = "Q12345"
        httpClient.givenJsonResponse(
            "wikidata.org",
            """{"claims":{"P18":[{"mainsnak":{"datavalue":{"value":"Logo.svg","type":"string"}}}]}}""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = wikidataId),
            name = "Test",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue(
            "SVG URL should have .png appended: ${artwork.url}",
            artwork.url.contains(".svg.png"),
        )
    }

    @Test
    fun `preferred rank claim is selected over normal rank`() = runTest {
        // Given - P18 has two claims: normal rank first, preferred rank second
        httpClient.givenJsonResponse(
            "wikidata.org",
            """{"claims":{"P18":[
                {"rank":"normal","mainsnak":{"datavalue":{"value":"Old_photo.jpg","type":"string"}}},
                {"rank":"preferred","mainsnak":{"datavalue":{"value":"New_photo.jpg","type":"string"}}}
            ]}}""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q44802"),
            name = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - should use the preferred-rank claim (New_photo.jpg)
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue(
            "URL should contain New_photo.jpg, was: ${artwork.url}",
            artwork.url.contains("New_photo.jpg"),
        )
    }

    @Test
    fun `normal rank claim is used when no preferred rank exists`() = runTest {
        // Given - P18 has two claims both with normal rank
        httpClient.givenJsonResponse(
            "wikidata.org",
            """{"claims":{"P18":[
                {"rank":"normal","mainsnak":{"datavalue":{"value":"First.jpg","type":"string"}}},
                {"rank":"normal","mainsnak":{"datavalue":{"value":"Second.jpg","type":"string"}}}
            ]}}""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q44802"),
            name = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - should use the first claim (First.jpg) when no preferred exists
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue(
            "URL should contain First.jpg, was: ${artwork.url}",
            artwork.url.contains("First.jpg"),
        )
    }

    @Test
    fun `single claim without rank field still works`() = runTest {
        // Given - P18 has a single claim with no "rank" key
        httpClient.givenJsonResponse(
            "wikidata.org",
            """{"claims":{"P18":[
                {"mainsnak":{"datavalue":{"value":"Only_photo.jpg","type":"string"}}}
            ]}}""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q44802"),
            name = "Radiohead",
        )

        // When
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - should work (backward-compatible) and use the only claim
        assertTrue(result is EnrichmentResult.Success)
        val artwork = (result as EnrichmentResult.Success).data as EnrichmentData.Artwork
        assertTrue(
            "URL should contain Only_photo.jpg, was: ${artwork.url}",
            artwork.url.contains("Only_photo.jpg"),
        )
    }

    @Test
    fun `enrich returns NotFound when claims object is missing entirely`() = runTest {
        // Given — Wikidata API returns JSON without a "claims" key
        val wikidataId = "Q99998"
        httpClient.givenJsonResponse("wikidata.org", """{"entity":"$wikidataId"}""")

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = wikidataId),
            name = "Unknown Artist",
        )

        // When — enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then — NotFound because extractImageFilename returns null when claims is absent
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when P18 array is empty`() = runTest {
        // Given — Wikidata API returns claims with an empty P18 array
        val wikidataId = "Q99997"
        httpClient.givenJsonResponse("wikidata.org", """{"claims":{"P18":[]}}""")

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = wikidataId),
            name = "No Photo Artist",
        )

        // When — enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then — NotFound because P18 array length is 0
        assertTrue(result is EnrichmentResult.NotFound)
    }
}
