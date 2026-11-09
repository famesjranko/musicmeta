package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.engine.ProviderCallScope
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.net.URLDecoder
import java.net.URLEncoder

@RunWith(Parameterized::class)
class MusicBrainzFeaturedCreditPathsTest(private val endpoint: String) {
    private val client = FakeHttpClient()
    private val api = MusicBrainzApi(client, RateLimiter(0))
    private val provider = MusicBrainzProvider(client, RateLimiter(0))
    private val records = JSONArray(resource("captures.json"))

    private fun entry(case: String, arm: String) = (0 until records.length())
        .map { records.getJSONObject(it) }
        .single {
            it.getString("endpoint") == endpoint && it.getString("case") == case && when {
                arm == "strict-current" -> it.getString("mode") == "strict" && it.getString("arm") == "current"
                arm == "strict-components" -> it.getString("mode") == "strict" && it.getString("arm") == "components"
                arm == "fuzzy-current" -> it.getString("mode") == "fuzzy" && it.getString("arm") == "current"
                arm == "fuzzy-components" -> it.getString("mode") == "fuzzy" && it.getString("arm") == "components"
                arm == "canonical-current" -> it.getString("mode") == "canonical" && it.getString("arm") == "current"
                arm == "canonical-components" -> it.getString("mode") == "canonical" && it.getString("arm") == "components"
                else -> it.getString("arm") == arm
            }
        }

    private fun route(capture: JSONObject) = client.givenJsonResponse(
        "query=" + URLEncoder.encode(capture.getString("query"), "UTF-8") + "&",
        resource(capture.getString("file")),
    )

    private fun resource(file: String) = checkNotNull(
        javaClass.getResourceAsStream("/pools/musicbrainz-featured-credit/" + file),
    ).bufferedReader().use { it.readText() }

    private fun queryUrls() = client.requestedUrls.filter { it.contains("query=") }.map { url ->
        val query = URLDecoder.decode(url.substringAfter("query=").substringBefore("&"), "UTF-8")
        query to url.substringAfter("&limit=")
    }

    private fun rowIds(capture: JSONObject) = capture.getJSONArray("rows").let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it).getString("id") }
    }

    private fun expectedCandidates(capture: JSONObject) = capture.getJSONArray("rows").let { rows ->
        (0 until rows.length()).map {
            val row = rows.getJSONObject(it)
            Triple(row.getString("id"), row.getString("title"), row.getString("canonical_credit"))
        }.toSet()
    }

    private fun request() = if (endpoint == "recording") {
        EnrichmentRequest.forTrack("Love the Way You Lie", "Eminem feat. Rihanna")
    } else {
        EnrichmentRequest.forAlbum("Love the Way You Lie", "Eminem feat. Rihanna")
    }

    @Test
    fun `strict featured pools resolve without fuzzy promotion`() = runTest {
        // Given - the canonical recording or strict release component pool is routed exactly
        if (endpoint == "recording") {
            route(entry("feat", "canonical-current")); route(entry("feat", "canonical-components"))
            route(entry("feat", "strict-current")); route(entry("feat", "strict-components"))
        } else {
            route(entry("feat", "strict-current")); route(entry("feat", "strict-components"))
        }

        // When - resolving the supplied featured credit directly
        val result = if (endpoint == "recording") {
            MusicBrainzTrackEnrichment(api, "musicbrainz", 80).enrichTrack(request() as EnrichmentRequest.ForTrack, EnrichmentType.GENRE)
        } else {
            MusicBrainzAlbumEnrichment(api, "musicbrainz", 80).enrichAlbum(request() as EnrichmentRequest.ForAlbum, EnrichmentType.GENRE)
        }

        // Then - exact identity resolves from the component pool with no fuzzy request
        assertTrue(result is EnrichmentResult.Success)
        val success = result as EnrichmentResult.Success
        assertNotNull(success.resolvedIdentifiers?.musicBrainzId)
        assertTrue(success.provenance == LookupProvenance.EXACT_NAME)
        val resolvedPool = if (endpoint == "recording") {
            entry("feat", "canonical-components")
        } else {
            entry("feat", "strict-components")
        }
        assertTrue(success.resolvedIdentifiers?.musicBrainzId in rowIds(resolvedPool))
        assertEquals(
            if (endpoint == "recording") listOf(entry("feat", "canonical-components").getString("query") to "100")
            else listOf(entry("feat", "strict-components").getString("query") to "25"),
            queryUrls(),
        )
    }

    @Test
    fun `candidate search uses one featured word query after a strict miss`() = runTest {
        // Given - strict current/component misses and the captured fuzzy component pool
        route(entry("lvoe", "strict-current")); route(entry("lvoe", "strict-components"))
        route(entry("lvoe", "fuzzy-current")); route(entry("lvoe", "fuzzy-components"))

        // When - searching candidates for the featured typo
        val candidates = provider.searchCandidates(
            if (endpoint == "recording") EnrichmentRequest.forTrack("Lvoe the Way You Lie", "Eminem feat. Rihanna")
            else EnrichmentRequest.forAlbum("Lvoe the Way You Lie", "Eminem feat. Rihanna"),
            3,
        )

        // Then - strict and fuzzy component requests produce the captured candidate set
        val strict = entry("lvoe", "strict-components")
        val fuzzy = entry("lvoe", "fuzzy-components")
        assertEquals(listOf(strict.getString("query") to "100", fuzzy.getString("query") to "3"), queryUrls())
        assertEquals(expectedCandidates(fuzzy), candidates.map { Triple(it.identifiers.musicBrainzId, it.title, it.artist) }.toSet())
        assertEquals(2, queryUrls().size)
    }

    @Test
    fun `enrichment miss offers featured word suggestions without extra requests`() = runTest {
        // Given - the exact ladder's canonical, strict and fuzzy component pools
        route(entry("lvoe", "strict-current")); route(entry("lvoe", "strict-components"))
        route(entry("lvoe", "fuzzy-current")); route(entry("lvoe", "fuzzy-components"))
        if (endpoint == "recording") {
            route(entry("lvoe", "canonical-components"))
        }

        // When - enrichment misses inside one provider call scope
        val result = withContext(ProviderCallScope()) {
            provider.enrich(
                if (endpoint == "recording") EnrichmentRequest.forTrack("Lvoe the Way You Lie", "Eminem feat. Rihanna")
                else EnrichmentRequest.forAlbum("Lvoe the Way You Lie", "Eminem feat. Rihanna"),
                EnrichmentType.GENRE,
            )
        }

        // Then - the ladder ends with captured suggestions and no artist lookup
        assertTrue(result is EnrichmentResult.NotFound)
        val fuzzy = entry("lvoe", "fuzzy-components")
        val expected = if (endpoint == "recording") {
            listOf(entry("lvoe", "canonical-components").getString("query") to "100", entry("lvoe", "strict-components").getString("query") to "25", fuzzy.getString("query") to "3")
        } else {
            listOf(entry("lvoe", "strict-components").getString("query") to "25", fuzzy.getString("query") to "3")
        }
        assertEquals(expected, queryUrls())
        assertTrue(client.requestedUrls.none { it.contains("/artist?") })
        assertEquals(expectedCandidates(fuzzy), (result as EnrichmentResult.NotFound).suggestions.orEmpty().map {
            Triple(it.identifiers.musicBrainzId, it.title, it.artist)
        }.toSet())
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun endpoints() = listOf(arrayOf("recording"), arrayOf("release"))
    }
}
