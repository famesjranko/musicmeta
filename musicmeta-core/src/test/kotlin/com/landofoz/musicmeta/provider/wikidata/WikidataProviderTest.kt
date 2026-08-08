package com.landofoz.musicmeta.provider.wikidata

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
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
        // Given - Wikidata returns a P18 image claim for the artist
        val wikidataId = "Q44802"
        httpClient.givenJsonResponse(
            "wikidata.org",
            """{"entities":{"$wikidataId":{"claims":
                {"P18":[{"mainsnak":{"datavalue":{"value":"Radiohead 2016.jpg","type":"string"}}}]}
            }}}""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = wikidataId),
            name = "Radiohead",
        )

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - success with an Artwork URL built from the P18 filename
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertEquals("wikidata", success.provider)
        assertEquals(0.95f, success.confidence, 0.01f)
        val artwork = success.data as EnrichmentData.Artwork
        assertTrue(artwork.url.contains("Radiohead"))
        assertTrue(artwork.url.contains("width=1200"))
    }

    @Test
    fun `enrich returns NotFound when no wikidataId in identifiers`() = runTest {
        // Given - identifiers carry no wikidataId
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(),
            name = "Radiohead",
        )

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - NotFound because there is no wikidataId to query
        assertTrue(result is EnrichmentResult.NotFound)
        val notFound = result as EnrichmentResult.NotFound
        assertEquals("wikidata", notFound.provider)
    }

    @Test
    fun `enrich returns NotFound when P18 property missing`() = runTest {
        // Given - Wikidata returns claims with no P18 property
        val wikidataId = "Q99999"
        httpClient.givenJsonResponse("wikidata.org", """{"entities":{"$wikidataId":{"claims":{}}}}""")

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = wikidataId),
            name = "Unknown",
        )

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - NotFound because P18 is absent
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich handles SVG files by appending png extension`() = runTest {
        // Given - P18 points at an SVG file
        val wikidataId = "Q12345"
        httpClient.givenJsonResponse(
            "wikidata.org",
            """{"entities":{"$wikidataId":{"claims":
                {"P18":[{"mainsnak":{"datavalue":{"value":"Logo.svg","type":"string"}}}]}
            }}}""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = wikidataId),
            name = "Test",
        )

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - the SVG filename gets .png appended for the rendered thumbnail URL
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
            """{"entities":{"Q44802":{"claims":{"P18":[
                {"rank":"normal","mainsnak":{"datavalue":{"value":"Old_photo.jpg","type":"string"}}},
                {"rank":"preferred","mainsnak":{"datavalue":{"value":"New_photo.jpg","type":"string"}}}
            ]}}}}""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q44802"),
            name = "Radiohead",
        )

        // When - enriching for artist photo
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
            """{"entities":{"Q44802":{"claims":{"P18":[
                {"rank":"normal","mainsnak":{"datavalue":{"value":"First.jpg","type":"string"}}},
                {"rank":"normal","mainsnak":{"datavalue":{"value":"Second.jpg","type":"string"}}}
            ]}}}}""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q44802"),
            name = "Radiohead",
        )

        // When - enriching for artist photo
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
            """{"entities":{"Q44802":{"claims":{"P18":[
                {"mainsnak":{"datavalue":{"value":"Only_photo.jpg","type":"string"}}}
            ]}}}}""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q44802"),
            name = "Radiohead",
        )

        // When - enriching for artist photo
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
        // Given - Wikidata's real "missing entity" shape: entities.<id> present, no claims key
        // (confirmed live: wbgetentities?ids=Q999999999&props=claims -> {"entities":{"Q999999999":
        // {"id":"Q999999999","missing":""}},"success":1})
        val wikidataId = "Q99998"
        httpClient.givenJsonResponse(
            "wikidata.org",
            """{"entities":{"$wikidataId":{"id":"$wikidataId","missing":""}},"success":1}""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = wikidataId),
            name = "Unknown Artist",
        )

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - NotFound because a missing entity has no "claims" key
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns NotFound when Wikidata answers HTTP 200 with an error body`() = runTest {
        // Given - Wikidata's real error shape for a bad/no-such-entity id, still HTTP 200
        // (confirmed live: wbgetentities?ids=notanid&props=claims -> {"error":{"code":
        // "no-such-entity",...}} with no "entities" key at all)
        val wikidataId = "Qbad"
        httpClient.givenJsonResponse(
            "wikidata.org",
            """{"error":{"code":"no-such-entity","info":"Could not find an entity with the ID \"$wikidataId\"."}}""",
        )

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = wikidataId),
            name = "Unknown Artist",
        )

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - NotFound, not a crash and not a permanent failure mistaken for one: there is no
        // "entities" key to read claims from, so the lookup chain falls through to null. This is
        // the regression this ticket guards against — the old wbgetclaims call hit this exact
        // shape (param-invalid) on every single call, forever.
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `getEntityProperties sends the exact wbgetentities URL contract`() = runTest {
        // Given - Wikidata returns a P18 claim so enrich completes normally
        val wikidataId = "Q44802"
        httpClient.givenJsonResponse(
            "wikidata.org",
            """{"entities":{"$wikidataId":{"claims":
                {"P18":[{"mainsnak":{"datavalue":{"value":"Photo.jpg","type":"string"}}}]}
            }}}""",
        )
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = wikidataId),
            name = "Radiohead",
        )

        // When - enriching for artist photo
        provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - pins action=wbgetentities, ids=<id>, props=claims, format=json: the exact contract
        // Wikidata's `paraminfo` module accepts. wbgetclaims's `property` param is not multi-valued
        // and rejects a pipe-delimited list with param-invalid at HTTP 200 — this is what regressed.
        assertEquals(
            "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$wikidataId&props=claims&format=json",
            httpClient.requestedUrls.single(),
        )
    }

    @Test
    fun `getEntityProperties parses claims nested under entities id claims`() = runTest {
        // Given - the wbgetentities response shape: claims live at entities.<id>.claims, not
        // top-level "claims" the way wbgetclaims used to return them.
        httpClient.givenJsonResponse(
            "wikidata.org",
            """{"entities":{"Q7259":{"type":"item","id":"Q7259","claims":{
                "P569":[{"mainsnak":{"datavalue":{"value":{"time":"+1912-06-23T00:00:00Z"},"type":"time"}}}]
            }}}}""",
        )
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q7259"),
            name = "Alan Turing",
        )

        // When - enriching for COUNTRY type
        val result = provider.enrich(request, EnrichmentType.COUNTRY)

        // Then - success with the birth date parsed from the nested P569 claim
        assertTrue(result is EnrichmentResult.Success)
        val metadata = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertEquals("1912-06-23", metadata.beginDate)
    }

    @Test
    fun `enrich returns NotFound when P18 array is empty`() = runTest {
        // Given - Wikidata API returns claims with an empty P18 array
        val wikidataId = "Q99997"
        httpClient.givenJsonResponse("wikidata.org", """{"entities":{"$wikidataId":{"claims":{"P18":[]}}}}""")

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = wikidataId),
            name = "No Photo Artist",
        )

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - NotFound because P18 array length is 0
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Metadata with birth and death dates from Wikidata properties`() = runTest {
        // Given - Wikidata has P569 (birth), P570 (death), P495 (country Q30=US)
        httpClient.givenJsonResponse("wikidata.org", """{
            "entities": {"Q12345": {
                "claims": {
                    "P569": [{"mainsnak":{"datavalue":{"value":{"time":"+1968-10-07T00:00:00Z"},"type":"time"}}}],
                    "P570": [{"mainsnak":{"datavalue":{"value":{"time":"+2045-01-01T00:00:00Z"},"type":"time"}}}],
                    "P495": [{"mainsnak":{"datavalue":{"value":{"id":"Q30"},"type":"wikibase-entityid"}}}],
                    "P106": [{"mainsnak":{"datavalue":{"value":{"id":"Q177220"},"type":"wikibase-entityid"}}}]
                }
            }}
        }""")

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q12345"),
            name = "Test Artist",
        )

        // When - enriching for COUNTRY type
        val result = provider.enrich(request, EnrichmentType.COUNTRY)

        // Then - success with Metadata containing birth date, country, occupation
        assertTrue(result is EnrichmentResult.Success)
        val metadata = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertEquals("1968-10-07", metadata.beginDate)
        assertEquals("2045-01-01", metadata.endDate)
        assertEquals("US", metadata.country)
        assertEquals("singer", metadata.artistType)
    }

    @Test
    fun `enrich returns NotFound for COUNTRY when no properties present`() = runTest {
        // Given - Wikidata returns claims with no relevant properties
        httpClient.givenJsonResponse("wikidata.org", """{"entities":{"Q99996":{"claims":{}}}}""")

        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q99996"),
            name = "Unknown",
        )

        // When - enriching for COUNTRY type
        val result = provider.enrich(request, EnrichmentType.COUNTRY)

        // Then - NotFound because no properties were found
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `enrich returns Error with NETWORK ErrorKind when API fails`() = runTest {
        // Given - simulate an IOException from the HTTP layer
        httpClient.givenIoException("wikidata.org")
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q44802"),
            name = "Radiohead",
        )

        // When - enriching for artist photo
        val result = provider.enrich(request, EnrichmentType.ARTIST_PHOTO)

        // Then - Error with NETWORK kind because IOException maps to ErrorKind.NETWORK
        assertTrue(result is EnrichmentResult.Error)
        assertEquals(ErrorKind.NETWORK, (result as EnrichmentResult.Error).errorKind)
        assertEquals("wikidata", result.provider)
    }
}
