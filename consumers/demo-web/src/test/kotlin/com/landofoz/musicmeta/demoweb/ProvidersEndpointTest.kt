package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.atomic.AtomicReference

/**
 * HTTP-level coverage for `/api/providers`, which had none above [buildProviderRows] as a pure
 * function before this class. This pins the wire join between a live [EnrichmentProvider] and the
 * [ProviderRow] the frontend's providers panel renders from.
 */
class ProvidersEndpointTest {

    private class StubKeyedProvider : EnrichmentProvider {
        override val id = "stub-keyed-provider"
        override val displayName = "Stub Keyed Provider"
        override val requiresApiKey = true
        override val isAvailable = false
        override val capabilities = listOf(ProviderCapability(EnrichmentType.TRACK_PREVIEW, 100))

        override suspend fun enrich(request: EnrichmentRequest, type: EnrichmentType): EnrichmentResult =
            EnrichmentResult.NotFound(type, id)
    }

    private fun startTestServer(provider: EnrichmentProvider): Int {
        val engine = EnrichmentEngine.Builder()
            .addProvider(provider)
            .cache(InMemoryEnrichmentCache())
            .build()
        return startServer(AtomicReference(engine), AtomicReference(CacheMode.NETWORK_FIRST), { engine }, ApiKeyConfig(), 0)
    }

    private val http = HttpClient.newHttpClient()

    private fun get(port: Int, path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test fun `providers reports a registered provider's own fields, not a shell 200`() {
        // Given - a server built with one keyed, unavailable, single-capability provider
        val port = startTestServer(StubKeyedProvider())

        // When - requesting the providers panel's data
        val response = get(port, "/api/providers")

        // Then - the row for this provider carries its actual registered fields. Checked against
        // the raw wire JSON key, not just the DTO the server itself encoded it with — a field the
        // server silently renamed would still decode fine through its own class, so a shape test
        // that only round-trips through that class could not catch the rename.
        assertEquals(200, response.statusCode())
        val rowJson = Json.parseToJsonElement(response.body())
            .jsonObject["providers"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["id"]?.jsonPrimitive?.content == "stub-keyed-provider" }
        assertEquals("Stub Keyed Provider", rowJson["displayName"]?.jsonPrimitive?.content)
        assertEquals(false, rowJson["available"]?.jsonPrimitive?.boolean)
        assertEquals(true, rowJson["requiresApiKey"]?.jsonPrimitive?.boolean)
        val capabilities = rowJson["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(capabilities.contains("TRACK_PREVIEW"))
    }
}
