package com.cascade.enrichment.provider.coverartarchive

import com.cascade.enrichment.http.HttpClient

/**
 * Cover Art Archive API client. Checks artwork availability via redirect URLs.
 * CAA endpoints return 307 redirects to archive.org for existing artwork.
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
