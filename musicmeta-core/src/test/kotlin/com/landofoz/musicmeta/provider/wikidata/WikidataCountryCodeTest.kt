package com.landofoz.musicmeta.provider.wikidata

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `Metadata.country` is ISO 3166-1 alpha-2, so every P495 Q-id either maps to a code or yields
 * nothing. Each fixture below is a `wbgetentities&props=claims` P495 claim captured 2026-09-03.
 */
class WikidataCountryCodeTest {

    private lateinit var httpClient: FakeHttpClient
    private lateinit var provider: WikidataProvider

    @Before
    fun setUp() {
        httpClient = FakeHttpClient()
        provider = WikidataProvider(httpClient, RateLimiter(0L))
    }

    @Test
    fun `enrich COUNTRY yields no country when the P495 Q-id has no mapped code`() = runTest {
        // Given - Okean Elzy's P495 names Q212, which the code map does not hold
        httpClient.givenJsonResponse("wikidata.org", countryClaimResponse("Q1008489", "Q212", 212))
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q1008489"),
            name = "Okean Elzy",
        )

        // When - enriching for country
        val result = provider.enrich(request, EnrichmentType.COUNTRY)

        // Then - nothing is reported, rather than the Q-id shipped as a country
        assertTrue("expected NotFound but was $result", result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich COUNTRY reports the United Kingdom as GB`() = runTest {
        // Given - The Beatles' P495 names Q145, the United Kingdom
        httpClient.givenJsonResponse("wikidata.org", countryClaimResponse("Q1299", "Q145", 145))
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q1299"),
            name = "The Beatles",
        )

        // When - enriching for country
        val result = provider.enrich(request, EnrichmentType.COUNTRY)

        // Then - the ISO 3166-1 alpha-2 code, which is GB and never UK
        assertEquals("GB", successCountry(result))
    }

    @Test
    fun `enrich COUNTRY reports France as FR rather than by name`() = runTest {
        // Given - Indochine's P495 names Q142, France
        httpClient.givenJsonResponse("wikidata.org", countryClaimResponse("Q1122583", "Q142", 142))
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q1122583"),
            name = "Indochine",
        )

        // When - enriching for country
        val result = provider.enrich(request, EnrichmentType.COUNTRY)

        // Then - a code, not the country's name
        assertEquals("FR", successCountry(result))
    }

    private fun successCountry(result: EnrichmentResult): String? {
        assertTrue("expected Success but was $result", result is EnrichmentResult.Success)
        val data = (result as EnrichmentResult.Success).data
        return (data as EnrichmentData.Metadata).country
    }

    private fun countryClaimResponse(entityId: String, countryQid: String, numericId: Int): String =
        """{"entities":{"$entityId":{"id":"$entityId","claims":{"P495":[{"mainsnak":
            {"snaktype":"value","property":"P495","datavalue":{"value":
            {"entity-type":"item","numeric-id":$numericId,"id":"$countryQid"},
            "type":"wikibase-entityid"},"datatype":"wikibase-item"},
            "type":"statement","rank":"normal"}]}}},"success":1}"""
}
