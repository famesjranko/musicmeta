package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.bodyOrThrowTransient
import java.net.URLEncoder

/**
 * Fetches artist biographies and page images from Wikipedia.
 *
 * The extract comes from the Action API (`action=query&prop=extracts|pageimages|pageprops`), which
 * answers with the lead text, the page thumbnail and the page properties in one request. Images
 * come from the REST `page/media-list` endpoint, whose items carry rendered `srcset` thumbnails —
 * there is no original-file URL or original dimensions on that response.
 */
internal class WikipediaApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    /**
     * The article's lead text, thumbnail and page properties.
     *
     * Returns null when the page is missing, has no lead text, or is a disambiguation page —
     * a disambiguation lead is a list of unrelated topics, never a usable biography.
     */
    suspend fun getPageExtract(title: String): WikipediaSummary? = rateLimiter.execute {
        val url = "$ACTION_API?action=query&format=json&formatversion=2&redirects=1" +
            "&prop=extracts%7Cpageimages%7Cpageprops&exintro=1&explaintext=1" +
            "&piprop=thumbnail&pithumbsize=$THUMBNAIL_SIZE&titles=${encodeTitle(title)}"
        val json = httpClient.fetchJsonResult(url).bodyOrThrowTransient() ?: return@execute null

        val page = json.optJSONObject("query")
            ?.optJSONArray("pages")
            ?.optJSONObject(0)
            ?: return@execute null
        if (page.optBoolean("missing", false)) return@execute null

        val pageProps = page.optJSONObject("pageprops")
        if (pageProps != null && pageProps.has("disambiguation")) return@execute null

        val extract = page.optString("extract").takeIf { it.isNotBlank() } ?: return@execute null

        WikipediaSummary(
            title = page.optString("title", title),
            extract = extract,
            description = pageProps?.optString("wikibase-shortdesc")?.takeIf { it.isNotBlank() },
            thumbnailUrl = page.optJSONObject("thumbnail")?.optString("source")?.takeIf { it.isNotBlank() },
            wikibaseItem = pageProps?.optString("wikibase_item")?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun getPageMediaList(title: String): List<WikipediaMediaItem> = rateLimiter.execute {
        val url = "$MEDIA_LIST_BASE_URL/${encodeTitle(title)}"
        val json = httpClient.fetchJsonResult(url).bodyOrThrowTransient() ?: return@execute emptyList()
        parseMediaList(json)
    }

    /**
     * Photographs from a `page/media-list` response, the article's lead image first.
     *
     * Items carry a `srcset` of rendered thumbnails, so the URL and width here are the 1x
     * rendering's, not the original file's; height is not on the response at all. Icons, logos and
     * SVG-sourced files are dropped, as is anything rendered below [MIN_IMAGE_WIDTH] wide.
     */
    private fun parseMediaList(json: org.json.JSONObject): List<WikipediaMediaItem> {
        val items = json.optJSONArray("items") ?: return emptyList()
        val results = mutableListOf<WikipediaMediaItem>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("type", "") != "image") continue
            val title = item.optString("title", "")
            if (title.endsWith(".svg", ignoreCase = true)) continue
            if (title.contains("icon", ignoreCase = true)) continue
            if (title.contains("logo", ignoreCase = true)) continue
            val source = item.optJSONArray("srcset")?.optJSONObject(0) ?: continue
            val imageUrl = source.optString("src").takeIf { it.isNotBlank() }?.let(::absoluteUrl) ?: continue
            val width = renderedWidthOf(imageUrl)
            if (width != null && width < MIN_IMAGE_WIDTH) continue
            results.add(
                WikipediaMediaItem(
                    title = title,
                    url = imageUrl,
                    width = width,
                    height = null,
                    isLeadImage = item.optBoolean("leadImage", false),
                ),
            )
        }
        return results.sortedByDescending { it.isLeadImage }
    }

    /** `srcset` sources are protocol-relative (`//upload.wikimedia.org/…`). */
    private fun absoluteUrl(src: String): String = if (src.startsWith("//")) "https:$src" else src

    /**
     * The width a thumbnail URL renders at, read from its `…/500px-Name.jpg` segment. Null for a
     * URL with no such segment, which is a full-size file of unknown width rather than a small one.
     */
    private fun renderedWidthOf(url: String): Int? =
        THUMBNAIL_WIDTH_PATTERN.find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun encodeTitle(title: String): String =
        URLEncoder.encode(title, "UTF-8").replace("+", "%20")

    private companion object {
        const val ACTION_API = "https://en.wikipedia.org/w/api.php"
        const val MEDIA_LIST_BASE_URL = "https://en.wikipedia.org/api/rest_v1/page/media-list"
        const val THUMBNAIL_SIZE = 320
        const val MIN_IMAGE_WIDTH = 100
        val THUMBNAIL_WIDTH_PATTERN = Regex("""/(\d+)px-""")
    }
}
