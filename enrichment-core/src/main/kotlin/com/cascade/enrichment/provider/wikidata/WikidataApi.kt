package com.cascade.enrichment.provider.wikidata

import com.cascade.enrichment.http.HttpClient
import com.cascade.enrichment.http.RateLimiter
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Fetches artist images from Wikidata via the P18 (image) property.
 * Constructs Wikimedia Commons thumbnail URLs from the filename.
 */
class WikidataApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    suspend fun getArtistImageUrl(
        wikidataId: String,
        size: Int = DEFAULT_IMAGE_SIZE,
    ): String? = rateLimiter.execute {
        val url = "$BASE_URL?action=wbgetclaims&entity=$wikidataId&property=P18&format=json"
        val json = httpClient.fetchJson(url) ?: return@execute null
        val filename = extractImageFilename(json) ?: return@execute null
        buildCommonsUrl(filename, size)
    }

    private fun extractImageFilename(json: JSONObject): String? {
        val claims = json.optJSONObject("claims") ?: return null
        val p18 = claims.optJSONArray("P18") ?: return null
        if (p18.length() == 0) return null
        return p18.getJSONObject(0)
            .optJSONObject("mainsnak")
            ?.optJSONObject("datavalue")
            ?.optString("value")
            ?.takeIf { it.isNotBlank() }
    }

    private fun buildCommonsUrl(filename: String, size: Int): String {
        val encoded = URLEncoder.encode(filename.replace(' ', '_'), "UTF-8")
        val ext = filename.substringAfterLast('.', "").lowercase()
        val suffix = if (ext in NON_RASTER_FORMATS) ".png" else ""
        return "$COMMONS_BASE_URL/$encoded$suffix?width=$size"
    }

    private companion object {
        const val BASE_URL = "https://www.wikidata.org/w/api.php"
        const val COMMONS_BASE_URL = "https://commons.wikimedia.org/wiki/Special:FilePath"
        const val DEFAULT_IMAGE_SIZE = 1200
        val NON_RASTER_FORMATS = setOf("svg", "tif", "tiff")
    }
}
