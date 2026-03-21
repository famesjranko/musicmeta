package com.landofoz.musicmeta.provider.coverartarchive

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import org.json.JSONObject

/**
 * Cover Art Archive API client. Checks artwork availability via redirect URLs
 * and fetches image metadata (thumbnails/sizes) from the JSON endpoint.
 */
class CoverArtArchiveApi(
    private val httpClient: HttpClient,
) {

    /**
     * Check if artwork exists for a release and return the redirect URL.
     * Returns null if no artwork is available (404).
     */
    suspend fun getArtworkUrl(releaseId: String, size: Int = 1200): String? {
        val url = "$BASE_URL/release/$releaseId/front-$size"
        return httpClient.fetchRedirectUrl(url)
    }

    /**
     * Check if artwork exists for a release group (fallback).
     * Returns null if no artwork is available.
     */
    suspend fun getGroupArtworkUrl(releaseGroupId: String, size: Int = 1200): String? {
        val url = "$BASE_URL/release-group/$releaseGroupId/front-$size"
        return httpClient.fetchRedirectUrl(url)
    }

    /**
     * Fetch full image metadata for a release, including thumbnail sizes.
     * Returns the list of images with their thumbnails, or null on error.
     */
    suspend fun getArtworkMetadata(releaseId: String): CoverArtArchiveImageList? {
        val url = "$BASE_URL/release/$releaseId"
        val json = when (val result = httpClient.fetchJsonResult(url)) {
            is HttpResult.Ok -> result.body
            else -> return null
        }
        return parseImageList(json)
    }

    private fun parseImageList(json: JSONObject): CoverArtArchiveImageList {
        val imagesArray = json.optJSONArray("images") ?: return CoverArtArchiveImageList(emptyList())
        val images = (0 until imagesArray.length()).mapNotNull { i ->
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
        return CoverArtArchiveImageList(images)
    }

    /** Build a canonical CAA URL without checking availability. */
    fun buildReleaseUrl(releaseId: String, size: Int = 1200): String =
        "$BASE_URL/release/$releaseId/front-$size"

    /** Build a canonical release-group CAA URL without checking availability. */
    fun buildGroupUrl(releaseGroupId: String, size: Int = 1200): String =
        "$BASE_URL/release-group/$releaseGroupId/front-$size"

    companion object {
        const val BASE_URL = "https://coverartarchive.org"
    }
}

data class CoverArtArchiveImageList(val images: List<CoverArtArchiveImage>)

data class CoverArtArchiveImage(
    val front: Boolean,
    val url: String,
    val thumbnails: Map<String, String>,
    val types: List<String> = emptyList(),
)
