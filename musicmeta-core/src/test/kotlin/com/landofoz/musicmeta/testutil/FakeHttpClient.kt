package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.http.HttpClient
import org.json.JSONArray
import org.json.JSONObject

class FakeHttpClient : HttpClient {
    private val jsonResponses = mutableMapOf<String, String>()
    private val errors = mutableSetOf<String>()
    val requestedUrls = mutableListOf<String>()

    fun givenJsonResponse(urlContains: String, json: String) { jsonResponses[urlContains] = json }
    fun givenJsonArrayResponse(urlContains: String, json: String) { jsonResponses[urlContains] = json }
    fun givenError(urlContains: String) { errors.add(urlContains) }

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
}
