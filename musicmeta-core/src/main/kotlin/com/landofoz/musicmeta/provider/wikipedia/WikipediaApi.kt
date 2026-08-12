package com.landofoz.musicmeta.provider.wikipedia

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.bodyOrThrowTransient
import java.io.IOException
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

        // The Action API reports its own failures inside a 200 (`maxlag` is the one this call can
        // provoke), so an error body must not read as an article that does not exist.
        json.optJSONObject("error")?.let { error ->
            throw IOException("Wikipedia Action API error: ${error.optString("code")}")
        }

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
            thumbnailUrl = page.optJSONObject("thumbnail")
                ?.optString("source")
                ?.takeIf { it.isNotBlank() }
                ?.let(::shippableUrl),
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
     * An item's `srcset` offers the same picture at several scales and nothing else — there is no
     * original-file URL and no height anywhere on the response. Each item therefore reports every
     * scale offered, largest first, and no height. Icons, logos and SVG-sourced files are dropped,
     * as is anything whose largest rendering is below [MIN_IMAGE_WIDTH] wide.
     *
     * Where no item is flagged `leadImage` — a gallery-only article — the first surviving item in
     * the article's own order wins, which is the article's first photograph.
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
            val renderings = renderingsOf(item.optJSONArray("srcset"))
            val largest = renderings.firstOrNull() ?: continue
            if (largest.width != null && largest.width < MIN_IMAGE_WIDTH) continue
            results.add(
                WikipediaMediaItem(
                    title = title,
                    url = largest.url,
                    width = largest.width,
                    height = null,
                    renderings = renderings,
                    isLeadImage = item.optBoolean("leadImage", false),
                ),
            )
        }
        return results.sortedByDescending { it.isLeadImage }
    }

    /**
     * An item's `srcset` entries as renderings, largest scale first.
     *
     * Entries are ordered by their own `scale` field (`1x`, `1.5x`, `2x`), never by array
     * position, and a URL with no readable width sorts last rather than winning by default.
     */
    private fun renderingsOf(srcset: org.json.JSONArray?): List<WikipediaRendering> {
        if (srcset == null) return emptyList()
        val renderings = mutableListOf<WikipediaRendering>()
        for (i in 0 until srcset.length()) {
            val entry = srcset.optJSONObject(i) ?: continue
            val src = entry.optString("src").takeIf { it.isNotBlank() } ?: continue
            val url = shippableUrl(src)
            renderings.add(
                WikipediaRendering(
                    url = url,
                    width = renderedWidthOf(url),
                    scale = entry.optString("scale").takeIf { it.isNotBlank() },
                ),
            )
        }
        return renderings.sortedByDescending { scaleFactorOf(it.scale) }
    }

    /** `2x` → 2.0. An entry with no readable scale sorts last, never ahead of a stated one. */
    private fun scaleFactorOf(scale: String?): Double =
        scale?.removeSuffix("x")?.toDoubleOrNull() ?: 0.0

    /**
     * A `srcset` source as a URL fit to hand a consumer: absolute (sources are protocol-relative)
     * and without the `utm_*` parameters Wikimedia attaches to attribute its own parser's traffic.
     */
    private fun shippableUrl(src: String): String {
        val absolute = if (src.startsWith("//")) "https:$src" else src
        val query = absolute.substringAfter('?', "")
        if (query.isEmpty()) return absolute
        val kept = query.split('&').filterNot { it.startsWith("utm_") }
        val base = absolute.substringBefore('?')
        return if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}"
    }

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
