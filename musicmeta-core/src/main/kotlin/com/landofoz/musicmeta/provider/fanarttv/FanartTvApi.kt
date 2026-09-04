package com.landofoz.musicmeta.provider.fanarttv

import com.landofoz.musicmeta.drift.SchemaTarget
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.bodyOrThrowAuthOrTransient
import com.landofoz.musicmeta.provider.encodePathSegment
import com.landofoz.musicmeta.provider.encodeQueryValue
import org.json.JSONObject

/**
 * Fanart.tv API client. Requires a project API key.
 * Provides high-quality artist images: thumbnails, backgrounds, logos, banners.
 */
internal class FanartTvApi(
    private val projectKeyProvider: () -> String,
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    constructor(projectKey: String, httpClient: HttpClient, rateLimiter: RateLimiter) :
        this({ projectKey }, httpClient, rateLimiter)

    /**
     * Fetches album-specific images for a release group from the Fanart.tv album endpoint.
     *
     * The endpoint resolves the release group to its *artist* and answers with that artist's
     * document, so the requested album is one entry in a map:
     * `{ "name": …, "mbid_id": <artist mbid>, "albums": { "<releaseGroupMbid>": { "albumcover":
     * [...], "cdart": [...] } } }`. Reading `<releaseGroupMbid>` at the top level finds nothing and
     * degrades to "this album has no art", which is indistinguishable from the real thing.
     *
     * Returns null if the release group is not found or has no images.
     */
    suspend fun getAlbumImages(releaseGroupMbid: String): FanartTvAlbumImages? {
        val url = albumImagesUrl(releaseGroupMbid, projectKeyProvider())
        val json = rateLimiter.execute { httpClient.fetchJsonResult(url).bodyOrThrowAuthOrTransient() } ?: return null
        val albumObj = json.optJSONObject("albums")?.optJSONObject(releaseGroupMbid) ?: return null
        return FanartTvAlbumImages(
            albumCovers = extractImages(albumObj, "albumcover"),
            cdArt = extractImages(albumObj, "cdart"),
        )
    }

    suspend fun getArtistImages(mbid: String): FanartTvArtistImages? {
        val url = artistImagesUrl(mbid, projectKeyProvider())
        val json = rateLimiter.execute { httpClient.fetchJsonResult(url).bodyOrThrowAuthOrTransient() } ?: return null
        return parseArtistImages(json)
    }

    // The artist document's nested "albums" map is not read: it merges albumcover/cdart across
    // every album under the artist, so album art is taken from getAlbumImages() only.
    private fun parseArtistImages(json: JSONObject) = FanartTvArtistImages(
        thumbnails = extractImages(json, "artistthumb"),
        backgrounds = extractImages(json, "artistbackground"),
        logos = extractImages(json, "hdmusiclogo"),
        banners = extractImages(json, "musicbanner"),
    )

    private fun extractImages(json: JSONObject, key: String): List<FanartTvImage> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.getJSONObject(i)
            val url = obj.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            FanartTvImage(
                url = url,
                id = obj.optString("id").takeIf { it.isNotBlank() },
                likes = obj.optString("likes", "0").toIntOrNull() ?: 0,
            )
        }
    }

    companion object {
        const val BASE_URL = "https://webservice.fanart.tv/v3/music"

        /** The URL [getAlbumImages] requests. */
        fun albumImagesUrl(releaseGroupMbid: String, apiKey: String): String =
            "$BASE_URL/albums/${encodePathSegment(releaseGroupMbid)}" +
                "?api_key=${encodeQueryValue(apiKey)}"

        /** The URL [getArtistImages] requests. */
        fun artistImagesUrl(mbid: String, apiKey: String): String =
            "$BASE_URL/${encodePathSegment(mbid)}?api_key=${encodeQueryValue(apiKey)}"

        /**
         * Schema-pin targets, mirroring [getAlbumImages] and [parseArtistImages].
         *
         * Both routes are pinned because they read different documents from one host: the album
         * route's images live under `albums.<release group mbid>`, the artist route's at the top
         * level. Pinning only one would have left the other's nesting unwatched.
         *
         * OK Computer's release group and Radiohead's artist MBID — both carry every pinned image
         * kind today.
         */
        fun schemaPinTargets(apiKey: String): List<SchemaTarget> {
            val releaseGroup = "b1392450-e666-3926-a536-22c65f834433"
            return listOf(
                SchemaTarget(
                    provider = "fanarttv",
                    route = "album images",
                    url = albumImagesUrl(releaseGroup, apiKey),
                    requiredPaths = listOf(
                        "albums.$releaseGroup.albumcover[0].url",
                        "albums.$releaseGroup.albumcover[0].id",
                        "albums.$releaseGroup.cdart[0].url",
                    ),
                ),
                SchemaTarget(
                    provider = "fanarttv",
                    route = "artist images",
                    url = artistImagesUrl("a74b1b7f-71a5-4011-9441-d0b5e4122711", apiKey),
                    requiredPaths = listOf(
                        "artistthumb[0].url",
                        "artistbackground[0].url",
                        "hdmusiclogo[0].url",
                        "musicbanner[0].url",
                    ),
                ),
            )
        }
    }
}
