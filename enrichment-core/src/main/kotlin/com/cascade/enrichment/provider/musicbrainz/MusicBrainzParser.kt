package com.cascade.enrichment.provider.musicbrainz

import org.json.JSONObject

/** Parses MusicBrainz JSON API responses into internal DTOs. */
object MusicBrainzParser {

    fun parseReleases(json: JSONObject): List<MusicBrainzRelease> {
        val releases = json.optJSONArray("releases") ?: return emptyList()
        return (0 until releases.length()).map { i ->
            parseReleaseObject(releases.getJSONObject(i))
        }
    }

    fun parseArtists(json: JSONObject): List<MusicBrainzArtist> {
        val artists = json.optJSONArray("artists") ?: return emptyList()
        return (0 until artists.length()).map { i ->
            parseArtistObject(artists.getJSONObject(i))
        }
    }

    fun parseRecordings(json: JSONObject): List<MusicBrainzRecording> {
        val recordings = json.optJSONArray("recordings") ?: return emptyList()
        return (0 until recordings.length()).map { i ->
            parseRecordingObject(recordings.getJSONObject(i))
        }
    }

    /** Parse a lookup response where the entity is at the top level. */
    fun parseLookupRelease(json: JSONObject): MusicBrainzRelease? {
        json.optString("id").takeIf { it.isNotBlank() } ?: return null
        return parseReleaseObject(json, defaultScore = 100)
    }

    /** Parse a lookup response for an artist at the top level. */
    fun parseLookupArtist(json: JSONObject): MusicBrainzArtist? {
        json.optString("id").takeIf { it.isNotBlank() } ?: return null
        return parseArtistObject(json, defaultScore = 100)
    }

    private fun parseReleaseObject(obj: JSONObject, defaultScore: Int = 0): MusicBrainzRelease =
        MusicBrainzRelease(
            id = obj.getString("id"),
            title = obj.getString("title"),
            artistCredit = extractArtistCredit(obj),
            date = obj.optString("date").takeIf { it.isNotBlank() },
            country = obj.optString("country").takeIf { it.isNotBlank() },
            barcode = obj.optString("barcode").takeIf { it.isNotBlank() },
            tags = extractReleaseTags(obj),
            label = extractLabel(obj),
            releaseType = extractReleaseType(obj),
            releaseGroupId = extractReleaseGroupId(obj),
            disambiguation = obj.optString("disambiguation").takeIf { it.isNotBlank() },
            score = obj.optInt("score", defaultScore),
            hasFrontCover = extractHasFrontCover(obj),
        )

    private fun parseArtistObject(obj: JSONObject, defaultScore: Int = 0): MusicBrainzArtist {
        val lifeSpan = obj.optJSONObject("life-span")
        return MusicBrainzArtist(
            id = obj.getString("id"),
            name = obj.getString("name"),
            type = obj.optString("type").takeIf { it.isNotBlank() },
            country = obj.optString("country").takeIf { it.isNotBlank() },
            beginDate = lifeSpan?.optString("begin")?.takeIf { it.isNotBlank() },
            endDate = lifeSpan?.optString("end")?.takeIf { it.isNotBlank() },
            tags = extractTags(obj),
            disambiguation = obj.optString("disambiguation").takeIf { it.isNotBlank() },
            wikidataId = extractWikidataId(obj),
            wikipediaTitle = extractWikipediaTitle(obj),
            score = obj.optInt("score", defaultScore),
        )
    }

    private fun parseRecordingObject(obj: JSONObject): MusicBrainzRecording =
        MusicBrainzRecording(
            id = obj.getString("id"),
            title = obj.getString("title"),
            isrcs = extractIsrcs(obj),
            tags = extractTags(obj),
            score = obj.optInt("score", 0),
        )

    internal fun extractArtistCredit(obj: JSONObject): String? {
        val credits = obj.optJSONArray("artist-credit") ?: return null
        return buildString {
            for (i in 0 until credits.length()) {
                val credit = credits.getJSONObject(i)
                append(credit.optJSONObject("artist")?.optString("name") ?: "")
                append(credit.optString("joinphrase", ""))
            }
        }.takeIf { it.isNotBlank() }
    }

    /**
     * Extract tags, falling back to release-group tags.
     * Tags are primarily on release-groups in MusicBrainz.
     */
    internal fun extractReleaseTags(release: JSONObject): List<String> {
        val releaseTags = extractTags(release)
        if (releaseTags.isNotEmpty()) return releaseTags
        val releaseGroup = release.optJSONObject("release-group") ?: return emptyList()
        return extractTags(releaseGroup)
    }

    internal fun extractTags(obj: JSONObject): List<String> {
        val tags = obj.optJSONArray("tags") ?: return emptyList()
        return (0 until tags.length())
            .map { i -> tags.getJSONObject(i) }
            .sortedByDescending { it.optInt("count", 0) }
            .map { it.getString("name") }
    }

    private fun extractLabel(release: JSONObject): String? {
        val labelInfo = release.optJSONArray("label-info") ?: return null
        if (labelInfo.length() == 0) return null
        return labelInfo.getJSONObject(0)
            .optJSONObject("label")
            ?.optString("name")
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractReleaseType(release: JSONObject): String? {
        val group = release.optJSONObject("release-group") ?: return null
        return group.optString("primary-type").takeIf { it.isNotBlank() }
    }

    private fun extractReleaseGroupId(release: JSONObject): String? {
        val group = release.optJSONObject("release-group") ?: return null
        return group.optString("id").takeIf { it.isNotBlank() }
    }

    internal fun extractWikidataId(obj: JSONObject): String? {
        val relations = obj.optJSONArray("relations") ?: return null
        for (i in 0 until relations.length()) {
            val rel = relations.getJSONObject(i)
            if (rel.optString("type") == "wikidata") {
                val url = rel.optJSONObject("url")?.optString("resource") ?: continue
                return url.substringAfterLast("/").takeIf { it.startsWith("Q") }
            }
        }
        return null
    }

    internal fun extractWikipediaTitle(obj: JSONObject): String? {
        val relations = obj.optJSONArray("relations") ?: return null
        for (i in 0 until relations.length()) {
            val rel = relations.getJSONObject(i)
            if (rel.optString("type") == "wikipedia") {
                val url = rel.optJSONObject("url")?.optString("resource") ?: continue
                // URL format: https://en.wikipedia.org/wiki/Title_Name
                return url.substringAfterLast("/wiki/").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun extractHasFrontCover(release: JSONObject): Boolean {
        val coverArt = release.optJSONObject("cover-art-archive") ?: return false
        return coverArt.optBoolean("front", false)
    }

    private fun extractIsrcs(recording: JSONObject): List<String> {
        val isrcs = recording.optJSONArray("isrcs") ?: return emptyList()
        return (0 until isrcs.length()).map { isrcs.getString(it) }
    }
}
