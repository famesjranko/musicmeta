package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class FakeHttpClient : HttpClient {
    private val jsonResponses = mutableMapOf<String, String>()
    private val errors = mutableSetOf<String>()
    private val ioExceptions = mutableSetOf<String>()
    private val httpResultResponses = mutableMapOf<String, HttpResult<JSONObject>>()
    private val httpResultArrayResponses = mutableMapOf<String, HttpResult<JSONArray>>()
    val requestedUrls = mutableListOf<String>()
    val requestedHeaders = mutableListOf<Map<String, String>>()

    fun givenJsonResponse(urlContains: String, json: String) { jsonResponses[urlContains] = json }
    fun givenJsonArrayResponse(urlContains: String, json: String) { jsonResponses[urlContains] = json }
    fun givenError(urlContains: String) { errors.add(urlContains) }
    /**
     * Causes fetchJsonResult (and related Result-returning methods) to throw an IOException
     * for URLs containing [urlContains]. Use this to test Provider-level error handling when
     * Api classes propagate the exception rather than returning null.
     */
    fun givenIoException(urlContains: String) { ioExceptions.add(urlContains) }
    fun givenHttpResult(urlContains: String, result: HttpResult<JSONObject>) { httpResultResponses[urlContains] = result }
    fun givenHttpResultArray(urlContains: String, result: HttpResult<JSONArray>) { httpResultArrayResponses[urlContains] = result }

    override suspend fun fetchJson(url: String): JSONObject? {
        requestedUrls.add(url)
        if (errors.any { url.contains(it) }) return null
        return jsonResponses.entries.firstOrNull { url.contains(it.key) }?.value?.let { JSONObject(it) }
    }

    override suspend fun fetchJsonArray(url: String): JSONArray? {
        requestedUrls.add(url)
        if (errors.any { url.contains(it) }) return null
        return jsonResponses.entries.firstOrNull { url.contains(it.key) }?.value?.let { JSONArray(it) }
    }

    override suspend fun fetchBody(url: String): String? {
        requestedUrls.add(url)
        if (errors.any { url.contains(it) }) return null
        return jsonResponses.entries.firstOrNull { url.contains(it.key) }?.value
    }

    override suspend fun fetchRedirectUrl(url: String): String? {
        requestedUrls.add(url)
        if (errors.any { url.contains(it) }) return null
        return jsonResponses.entries.firstOrNull { url.contains(it.key) }?.value ?: url
    }

    override suspend fun postJson(url: String, body: String): JSONObject? {
        requestedUrls.add(url)
        if (errors.any { url.contains(it) }) return null
        return jsonResponses.entries.firstOrNull { url.contains(it.key) }?.value?.let { JSONObject(it) }
    }

    override suspend fun postJsonArray(url: String, body: String): JSONArray? {
        requestedUrls.add(url)
        if (errors.any { url.contains(it) }) return null
        return jsonResponses.entries.firstOrNull { url.contains(it.key) }?.value?.let { JSONArray(it) }
    }

    override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> =
        fetchJsonResult(url, emptyMap())

    override suspend fun fetchJsonResult(
        url: String,
        headers: Map<String, String>,
    ): HttpResult<JSONObject> {
        requestedUrls.add(url)
        requestedHeaders.add(headers)
        if (ioExceptions.any { url.contains(it) }) throw IOException("Simulated network error: $url")
        if (errors.any { url.contains(it) }) return HttpResult.NetworkError("Simulated network error")
        val configured = httpResultResponses.entries.firstOrNull { url.contains(it.key) }
        if (configured != null) return configured.value
        // Fall back to existing fetchJson behavior for backward compatibility
        val json = jsonResponses.entries.firstOrNull { url.contains(it.key) }?.value
        return if (json != null) HttpResult.Ok(JSONObject(json)) else HttpResult.NetworkError("No response configured")
    }

    override suspend fun fetchJsonArrayResult(url: String): HttpResult<JSONArray> {
        requestedUrls.add(url)
        if (ioExceptions.any { url.contains(it) }) throw IOException("Simulated network error: $url")
        if (errors.any { url.contains(it) }) return HttpResult.NetworkError("Simulated network error")
        val configured = httpResultArrayResponses.entries.firstOrNull { url.contains(it.key) }
        if (configured != null) return configured.value
        // Fall back to existing fetchJsonArray behavior for backward compatibility
        val json = fetchJsonArray(url)
        return if (json != null) HttpResult.Ok(json) else HttpResult.NetworkError("No response configured")
    }

    override suspend fun postJsonResult(url: String, body: String): HttpResult<JSONObject> {
        requestedUrls.add(url)
        if (ioExceptions.any { url.contains(it) }) throw IOException("Simulated network error: $url")
        if (errors.any { url.contains(it) }) return HttpResult.NetworkError("Simulated network error")
        val configured = httpResultResponses.entries.firstOrNull { url.contains(it.key) }
        if (configured != null) return configured.value
        // Fall back to existing postJson behavior for backward compatibility
        val json = postJson(url, body)
        return if (json != null) HttpResult.Ok(json) else HttpResult.NetworkError("No response configured")
    }

    override suspend fun postJsonArrayResult(url: String, body: String): HttpResult<JSONArray> {
        requestedUrls.add(url)
        if (ioExceptions.any { url.contains(it) }) throw IOException("Simulated network error: $url")
        if (errors.any { url.contains(it) }) return HttpResult.NetworkError("Simulated network error")
        val configured = httpResultArrayResponses.entries.firstOrNull { url.contains(it.key) }
        if (configured != null) return configured.value
        // Fall back to existing postJsonArray behavior for backward compatibility
        val json = postJsonArray(url, body)
        return if (json != null) HttpResult.Ok(json) else HttpResult.NetworkError("No response configured")
    }
}
