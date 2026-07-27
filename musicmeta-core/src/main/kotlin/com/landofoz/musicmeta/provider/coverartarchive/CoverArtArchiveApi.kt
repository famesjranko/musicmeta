package com.landofoz.musicmeta.provider.coverartarchive

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.bodyOrThrowTransient
import org.json.JSONObject

/**
 * Cover Art Archive API client. Checks artwork availability via redirect URLs
 * and fetches image metadata (thumbnails/sizes) from the JSON endpoint.
 *
 * Upstream publishes no rate limit (judgement 2026-07-27), so the interval is politeness to a
 * volunteer service; the figure lives with the other per-host limiters in `withDefaultProviders()`.
 */
internal class CoverArtArchiveApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    /**
     * Check if artwork exists for a release and return the redirect URL.
     * Returns null if no artwork is available (404).
     */
    suspend fun getArtworkUrl(releaseId: String, size: Int = 1200): String? {
        val url = "$BASE_URL/release/$releaseId/front-$size"
        return rateLimiter.execute { httpClient.fetchRedirectUrl(url) }
    }

    /**
     * Check if artwork exists for a release group (fallback).
     * Returns null if no artwork is available.
     */
    suspend fun getGroupArtworkUrl(releaseGroupId: String, size: Int = 1200): String? {
        val url = "$BASE_URL/release-group/$releaseGroupId/front-$size"
        return rateLimiter.execute { httpClient.fetchRedirectUrl(url) }
    }

    /**
     * Fetch full image metadata for a release, including thumbnail sizes.
     * Returns the list of images with their thumbnails, or null on error.
     */
    suspend fun getArtworkMetadata(releaseId: String): List<CoverArtArchiveImage>? {
        val url = "$BASE_URL/release/$releaseId"
        val json = rateLimiter.execute {
            httpClient.fetchJsonResult(url).bodyOrThrowTransient()
        } ?: return null
        return parseImageList(json)
    }

    private fun parseImageList(json: JSONObject): List<CoverArtArchiveImage> {
        val imagesArray = json.optJSONArray("images") ?: return emptyList()
        return (0 until imagesArray.length()).mapNotNull { i ->
            val obj = imagesArray.optJSONObject(i) ?: return@mapNotNull null
            val front = obj.optBoolean("front", false)
            val imageUrl = obj.optString("image", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val thumbsObj = obj.optJSONObject("thumbnails")
            val thumbnails = mutableMapOf<String, String>()
            if (thumbsObj != null) {
                for (key in thumbsObj.keys()) {
                    val value = thumbsObj.optString(key, "").takeIf { it.isNotBlank() }
                    if (value != null) thumbnails[key] = value
                }
            }
            val typesArray = obj.optJSONArray("types")
            val types = if (typesArray != null) {
                (0 until typesArray.length()).map { j -> typesArray.getString(j) }
            } else {
                emptyList()
            }
            CoverArtArchiveImage(front = front, url = imageUrl, thumbnails = thumbnails, types = types)
        }
    }

    companion object {
        const val BASE_URL = "https://coverartarchive.org"
    }
}
