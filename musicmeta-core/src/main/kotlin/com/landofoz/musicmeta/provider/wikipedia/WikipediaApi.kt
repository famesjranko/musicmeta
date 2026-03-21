package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
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

    suspend fun getPageMediaList(title: String): List<WikipediaMediaItem> = rateLimiter.execute {
        val encoded = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
        val url = "$MEDIA_LIST_BASE_URL/$encoded"
        val json = httpClient.fetchJson(url) ?: return@execute emptyList()
        parseMediaList(json)
    }

    private fun parseMediaList(json: org.json.JSONObject): List<WikipediaMediaItem> {
        val items = json.optJSONArray("items") ?: return emptyList()
        val results = mutableListOf<WikipediaMediaItem>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val type = item.optString("type", "")
            if (type != "image") continue
            val title = item.optString("title", "")
            if (title.endsWith(".svg", ignoreCase = true)) continue
            if (title.contains("icon", ignoreCase = true)) continue
            if (title.contains("logo", ignoreCase = true)) continue
            val originalSrc = item.optJSONObject("original")
            val imageUrl = originalSrc?.optString("source")
                ?: continue
            if (imageUrl.isBlank()) continue
            val width = originalSrc.optInt("width", 0).takeIf { it > 0 }
            val height = originalSrc.optInt("height", 0).takeIf { it > 0 }
            if (width != null && width < 100) continue
            results.add(WikipediaMediaItem(title = title, url = imageUrl, width = width, height = height))
        }
        return results
    }

    private companion object {
        const val BASE_URL = "https://en.wikipedia.org/api/rest_v1/page/summary"
        const val MEDIA_LIST_BASE_URL = "https://en.wikipedia.org/api/rest_v1/page/media-list"
    }
}
