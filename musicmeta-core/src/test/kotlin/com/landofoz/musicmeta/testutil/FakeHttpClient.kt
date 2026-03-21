package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import org.json.JSONArray
import org.json.JSONObject

class FakeHttpClient : HttpClient {
    private val jsonResponses = mutableMapOf<String, String>()
    private val errors = mutableSetOf<String>()
    private val httpResultResponses = mutableMapOf<String, HttpResult<JSONObject>>()
    val requestedUrls = mutableListOf<String>()

    fun givenJsonResponse(urlContains: String, json: String) { jsonResponses[urlContains] = json }
    fun givenJsonArrayResponse(urlContains: String, json: String) { jsonResponses[urlContains] = json }
    fun givenError(urlContains: String) { errors.add(urlContains) }
    fun givenHttpResult(urlContains: String, result: HttpResult<JSONObject>) { httpResultResponses[urlContains] = result }

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

    override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> {
        requestedUrls.add(url)
        val configured = httpResultResponses.entries.firstOrNull { url.contains(it.key) }
        if (configured != null) return configured.value
        // Fall back to existing fetchJson behavior for backward compatibility
        val json = fetchJson(url)
        return if (json != null) HttpResult.Ok(json) else HttpResult.NetworkError("No response configured")
    }
}
