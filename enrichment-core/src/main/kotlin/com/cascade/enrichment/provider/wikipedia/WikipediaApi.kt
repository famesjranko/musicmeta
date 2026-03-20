package com.cascade.enrichment.provider.wikipedia

import com.cascade.enrichment.http.HttpClient
import com.cascade.enrichment.http.RateLimiter
import java.net.URLEncoder

/**
 * Fetches artist biographies from the Wikipedia REST API.
 * Uses the page/summary endpoint for plain text extracts.
 */
class WikipediaApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    suspend fun getPageSummary(title: String): WikipediaSummary? = rateLimiter.execute {
        val encoded = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val url = "$BASE_URL/$encoded"
        val json = httpClient.fetchJson(url) ?: return@execute null

        val extract = json.optString("extract").takeIf { it.isNotBlank() }
            ?: return@execute null

        WikipediaSummary(
            title = json.optString("title", title),
            extract = extract,
            description = json.optString("description").takeIf { it.isNotBlank() },
            thumbnailUrl = json.optJSONObject("thumbnail")?.optString("source"),
        )
    }

    private companion object {
        const val BASE_URL = "https://en.wikipedia.org/api/rest_v1/page/summary"
    }
}
