package com.cascade.enrichment.provider.wikidata

import com.cascade.enrichment.EnrichmentData
import com.cascade.enrichment.EnrichmentRequest
import com.cascade.enrichment.EnrichmentResult
import com.cascade.enrichment.EnrichmentType
import com.cascade.enrichment.EnrichmentIdentifiers
import com.cascade.enrichment.http.RateLimiter
import com.cascade.enrichment.testutil.FakeHttpClient
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
