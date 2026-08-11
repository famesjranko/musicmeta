package com.landofoz.musicmeta.provider.wikidata

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.IdentifierNamespace
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `enrich carries the external-id claims as resolved identifiers`() = runTest {
        // Given - the live Coldplay entity, which carries all four external-id claims
        httpClient.givenJsonResponse("wikidata.org", COLDPLAY_CLAIMS)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q45188"),
            name = "Coldplay",
        )

        // When - enriching for COUNTRY type
        val result = provider.enrich(request, EnrichmentType.COUNTRY)

        // Then - all four identifiers ride the one claims request already made
        val success = result as EnrichmentResult.Success
        val ids = success.resolvedIdentifiers!!
        assertEquals("cc197bad-dc9c-440d-a5b5-d52ba2e14234", ids.get(IdentifierNamespace.MUSICBRAINZ_ARTIST))
        assertEquals("29735", ids.get(IdentifierNamespace.DISCOGS_ARTIST))
        assertEquals("4gzpq5DPGxSnKTe4SA8HAU", ids.get(IdentifierNamespace.SPOTIFY_ARTIST))
        assertEquals("471744", ids.get(IdentifierNamespace.ITUNES_ARTIST))
        assertEquals(1, httpClient.requestedUrls.size)
    }

    @Test
    fun `enrich omits the identifier claims an entity does not carry`() = runTest {
        // Given - a long-tail Latvian act with P434 and P1953 and P1902, but no P2850
        httpClient.givenJsonResponse("wikidata.org", LATVIAN_ARTIST_CLAIMS)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q16349192"),
            name = "Pienvedēja Piedzīvojumi",
        )

        // When - enriching for COUNTRY type
        val result = provider.enrich(request, EnrichmentType.COUNTRY)

        // Then - the present claims are filled and the absent Apple Music one is simply null
        val ids = (result as EnrichmentResult.Success).resolvedIdentifiers!!
        assertEquals("6d7f0a3f-358e-4864-83fe-7d2d7595afc2", ids.get(IdentifierNamespace.MUSICBRAINZ_ARTIST))
        assertEquals("5zHMsdWDY6kINMWyhyBrit", ids.get(IdentifierNamespace.SPOTIFY_ARTIST))
        assertNull(ids.get(IdentifierNamespace.ITUNES_ARTIST))
    }

    @Test
    fun `enrich leaves identifiers null when the entity carries no external-id claim`() = runTest {
        // Given - an entity whose only claim is the birth date
        httpClient.givenJsonResponse(
            "wikidata.org",
            """{"entities":{"Q7259":{"claims":{
                "P569":[{"mainsnak":{"datavalue":{"value":{"time":"+1912-06-23T00:00:00Z"},"type":"time"}}}]
            }}}}""",
        )
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q7259"),
            name = "Alan Turing",
        )

        // When - enriching for COUNTRY type
        val result = provider.enrich(request, EnrichmentType.COUNTRY)

        // Then - no identifiers rather than an empty map, so nothing downstream merges a blank
        assertNull((result as EnrichmentResult.Success).resolvedIdentifiers)
    }

    @Test
    fun `enrich returns the P856 official website as ARTIST_LINKS`() = runTest {
        // Given - the live Coldplay entity, which carries a P856 official website
        httpClient.givenJsonResponse("wikidata.org", COLDPLAY_CLAIMS)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q45188"),
            name = "Coldplay",
        )

        // When - enriching for ARTIST_LINKS
        val result = provider.enrich(request, EnrichmentType.ARTIST_LINKS)

        // Then - one link, typed the way MusicBrainz types the same relation
        val links = ((result as EnrichmentResult.Success).data as EnrichmentData.ArtistLinks).links
        assertEquals(1, links.size)
        assertEquals("official homepage", links.single().type)
        assertEquals("https://coldplay.com", links.single().url)
    }

    @Test
    fun `enrich returns NotFound for ARTIST_LINKS when P856 is absent`() = runTest {
        // Given - a long-tail act whose entity carries no P856 claim
        httpClient.givenJsonResponse("wikidata.org", LATVIAN_ARTIST_CLAIMS)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q16349192"),
            name = "Pienvedēja Piedzīvojumi",
        )

        // When - enriching for ARTIST_LINKS
        val result = provider.enrich(request, EnrichmentType.ARTIST_LINKS)

        // Then - NotFound: an absent website is missing data, not a provider failure
        assertTrue(result is EnrichmentResult.NotFound)
    }

    @Test
    fun `P495 of Q211 reports Latvia and not Czech Republic`() = runTest {
        // Given - a Latvian act whose P495 is Q211, the QID the map used to label Czech Republic
        httpClient.givenJsonResponse("wikidata.org", LATVIAN_ARTIST_CLAIMS)
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q16349192"),
            name = "Pienvedēja Piedzīvojumi",
        )

        // When - enriching for COUNTRY type
        val result = provider.enrich(request, EnrichmentType.COUNTRY)

        // Then - Latvia, which is what Q211 is
        val metadata = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertEquals("Latvia", metadata.country)
    }

    @Test
    fun `P495 of Q213 reports Czech Republic`() = runTest {
        // Given - synthetic: an entity whose P495 is Q213, the real Czech Republic QID
        httpClient.givenJsonResponse(
            "wikidata.org",
            """{"entities":{"Q99995":{"claims":{"P495":[
                {"mainsnak":{"datavalue":{"value":{"id":"Q213"},"type":"wikibase-entityid"}}}
            ]}}}}""",
        )
        val request = EnrichmentRequest.ForArtist(
            identifiers = EnrichmentIdentifiers(wikidataId = "Q99995"),
            name = "Czech Artist",
        )

        // When - enriching for COUNTRY type
        val result = provider.enrich(request, EnrichmentType.COUNTRY)

        // Then - Czech Republic, now keyed on the QID that means it
        val metadata = (result as EnrichmentResult.Success).data as EnrichmentData.Metadata
        assertEquals("Czech Republic", metadata.country)
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

    private companion object {
        // captured 2026-08-12: GET /w/api.php?action=wbgetentities&ids=Q45188&props=claims&format=json,
        // trimmed to the read properties; each statement keeps its real mainsnak, datatype and rank.
        val COLDPLAY_CLAIMS = """
            {"entities":{"Q45188":{"type":"item","id":"Q45188","claims":{
              "P18":[{"mainsnak":{"snaktype":"value","property":"P18","datavalue":
                {"value":"ColdplayWembley120925 (cropped).jpg","type":"string"},
                "datatype":"commonsMedia"},"type":"statement","rank":"normal"}],
              "P434":[{"mainsnak":{"snaktype":"value","property":"P434","datavalue":
                {"value":"cc197bad-dc9c-440d-a5b5-d52ba2e14234","type":"string"},
                "datatype":"external-id"},"type":"statement","rank":"normal"}],
              "P1953":[{"mainsnak":{"snaktype":"value","property":"P1953","datavalue":
                {"value":"29735","type":"string"},
                "datatype":"external-id"},"type":"statement","rank":"normal"}],
              "P1902":[{"mainsnak":{"snaktype":"value","property":"P1902","datavalue":
                {"value":"4gzpq5DPGxSnKTe4SA8HAU","type":"string"},
                "datatype":"external-id"},"type":"statement","rank":"normal"}],
              "P2850":[{"mainsnak":{"snaktype":"value","property":"P2850","datavalue":
                {"value":"471744","type":"string"},
                "datatype":"external-id"},"type":"statement","rank":"normal"}],
              "P856":[{"mainsnak":{"snaktype":"value","property":"P856","datavalue":
                {"value":"https://coldplay.com","type":"string"},
                "datatype":"url"},"type":"statement","rank":"normal"}],
              "P495":[{"mainsnak":{"snaktype":"value","property":"P495","datavalue":
                {"value":{"entity-type":"item","numeric-id":145,"id":"Q145"},"type":"wikibase-entityid"},
                "datatype":"wikibase-item"},"type":"statement","rank":"normal"}]
            }}},"success":1}
        """.trimIndent()

        // captured 2026-08-12: GET /w/api.php?action=wbgetentities&ids=Q16349192&props=claims&format=json,
        // trimmed the same way. P2850 and P856 are genuinely absent upstream on this act.
        val LATVIAN_ARTIST_CLAIMS = """
            {"entities":{"Q16349192":{"type":"item","id":"Q16349192","claims":{
              "P18":[{"mainsnak":{"snaktype":"value","property":"P18","datavalue":
                {"value":"POS17 @Kristsll-604 (35834852332).jpg","type":"string"},
                "datatype":"commonsMedia"},"type":"statement","rank":"normal"}],
              "P434":[{"mainsnak":{"snaktype":"value","property":"P434","datavalue":
                {"value":"6d7f0a3f-358e-4864-83fe-7d2d7595afc2","type":"string"},
                "datatype":"external-id"},"type":"statement","rank":"normal"}],
              "P1953":[{"mainsnak":{"snaktype":"value","property":"P1953","datavalue":
                {"value":"735648","type":"string"},
                "datatype":"external-id"},"type":"statement","rank":"normal"}],
              "P1902":[{"mainsnak":{"snaktype":"value","property":"P1902","datavalue":
                {"value":"5zHMsdWDY6kINMWyhyBrit","type":"string"},
                "datatype":"external-id"},"type":"statement","rank":"normal"}],
              "P495":[{"mainsnak":{"snaktype":"value","property":"P495","datavalue":
                {"value":{"entity-type":"item","numeric-id":211,"id":"Q211"},"type":"wikibase-entityid"},
                "datatype":"wikibase-item"},"type":"statement","rank":"normal"}]
            }}},"success":1}
        """.trimIndent()
    }
}
