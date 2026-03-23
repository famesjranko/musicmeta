package com.landofoz.musicmeta.provider.musicbrainz

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

    private fun parseReleaseObject(obj: JSONObject, defaultScore: Int = 0): MusicBrainzRelease {
        val tagCounts = extractReleaseTagCounts(obj)
        return MusicBrainzRelease(
            id = obj.getString("id"),
            title = obj.getString("title"),
            artistCredit = extractArtistCredit(obj),
            date = obj.optString("date").takeIf { it.isNotBlank() },
            country = obj.optString("country").takeIf { it.isNotBlank() },
            barcode = obj.optString("barcode").takeIf { it.isNotBlank() },
            tags = tagCounts.map { it.first },
            tagCounts = tagCounts,
            label = extractLabel(obj),
            releaseType = extractReleaseType(obj),
            releaseGroupId = extractReleaseGroupId(obj),
            disambiguation = obj.optString("disambiguation").takeIf { it.isNotBlank() },
            score = obj.optInt("score", defaultScore),
            hasFrontCover = extractHasFrontCover(obj),
            tracks = parseMedia(obj),
        )
    }

    private fun parseArtistObject(obj: JSONObject, defaultScore: Int = 0): MusicBrainzArtist {
        val lifeSpan = obj.optJSONObject("life-span")
        val tagCounts = extractTagsWithCounts(obj)
        return MusicBrainzArtist(
            id = obj.getString("id"),
            name = obj.getString("name"),
            type = obj.optString("type").takeIf { it.isNotBlank() },
            country = obj.optString("country").takeIf { it.isNotBlank() },
            beginDate = lifeSpan?.optString("begin")?.takeIf { it.isNotBlank() },
            endDate = lifeSpan?.optString("end")?.takeIf { it.isNotBlank() },
            tags = tagCounts.map { it.first },
            tagCounts = tagCounts,
            disambiguation = obj.optString("disambiguation").takeIf { it.isNotBlank() },
            wikidataId = extractWikidataId(obj),
            wikipediaTitle = extractWikipediaTitle(obj),
            score = obj.optInt("score", defaultScore),
            urlRelations = parseUrlRelations(obj),
            bandMembers = parseBandMembers(obj),
        )
    }

    private fun parseRecordingObject(obj: JSONObject): MusicBrainzRecording {
        val tagCounts = extractTagsWithCounts(obj)
        return MusicBrainzRecording(
            id = obj.getString("id"),
            title = obj.getString("title"),
            isrcs = extractIsrcs(obj),
            tags = tagCounts.map { it.first },
            tagCounts = tagCounts,
            score = obj.optInt("score", 0),
        )
    }

    /** Parse band members from artist-rels in an artist lookup response.
     *  Only includes "backward" direction — people who are members OF this entity.
     *  "forward" direction means this entity is a member of the related artist (e.g. side projects). */
    fun parseBandMembers(json: JSONObject): List<MusicBrainzBandMember> {
        val relations = json.optJSONArray("relations") ?: return emptyList()
        val members = mutableListOf<MusicBrainzBandMember>()
        for (i in 0 until relations.length()) {
            val rel = relations.getJSONObject(i)
            if (rel.optString("type") != "member of band") continue
            if (rel.optString("direction") != "backward") continue
            val artist = rel.optJSONObject("artist") ?: continue
            val attrs = rel.optJSONArray("attributes")
            val role = attrs?.let { if (it.length() > 0) it.getString(0) else null }
            members.add(
                MusicBrainzBandMember(
                    name = artist.getString("name"),
                    id = artist.optString("id").takeIf { it.isNotBlank() },
                    role = role,
                    beginDate = rel.optString("begin").takeIf { it.isNotBlank() },
                    endDate = rel.optString("end").takeIf { it.isNotBlank() },
                    ended = rel.optBoolean("ended", false),
                ),
            )
        }
        return members
    }

    /** Parse release groups from a browse response. */
    fun parseReleaseGroups(json: JSONObject): List<MusicBrainzReleaseGroup> {
        val groups = json.optJSONArray("release-groups") ?: return emptyList()
        return (0 until groups.length()).map { i ->
            val obj = groups.getJSONObject(i)
            MusicBrainzReleaseGroup(
                id = obj.getString("id"),
                title = obj.getString("title"),
                primaryType = obj.optString("primary-type").takeIf { it.isNotBlank() },
                firstReleaseDate = obj.optString("first-release-date").takeIf { it.isNotBlank() },
            )
        }
    }

    /** Parse tracks from media array in a release lookup response. */
    fun parseMedia(json: JSONObject): List<MusicBrainzTrack> {
        val media = json.optJSONArray("media") ?: return emptyList()
        val tracks = mutableListOf<MusicBrainzTrack>()
        for (m in 0 until media.length()) {
            val medium = media.getJSONObject(m)
            val trackList = medium.optJSONArray("tracks") ?: continue
            for (t in 0 until trackList.length()) {
                val track = trackList.getJSONObject(t)
                val recordingId = track.optJSONObject("recording")
                    ?.optString("id")?.takeIf { it.isNotBlank() }
                tracks.add(
                    MusicBrainzTrack(
                        title = track.getString("title"),
                        position = track.getInt("position"),
                        lengthMs = track.optLong("length", 0L).takeIf { it > 0 },
                        id = recordingId ?: track.optString("id").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
        return tracks
    }

    /** Parse URL relations from an artist lookup, excluding wikidata and wikipedia. */
    fun parseUrlRelations(json: JSONObject): List<MusicBrainzUrlRelation> {
        val relations = json.optJSONArray("relations") ?: return emptyList()
        val excluded = setOf("wikidata", "wikipedia")
        val result = mutableListOf<MusicBrainzUrlRelation>()
        for (i in 0 until relations.length()) {
            val rel = relations.getJSONObject(i)
            if (rel.optString("target-type") != "url") continue
            val type = rel.optString("type").takeIf { it.isNotBlank() } ?: continue
            if (type in excluded) continue
            val url = rel.optJSONObject("url")?.optString("resource") ?: continue
            result.add(MusicBrainzUrlRelation(type = type, url = url))
        }
        return result
    }

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
     * Extract tags with counts, falling back to release-group tags.
     * Tags are primarily on release-groups in MusicBrainz.
     */
    internal fun extractReleaseTagCounts(release: JSONObject): List<Pair<String, Int>> {
        val releaseTags = extractTagsWithCounts(release)
        if (releaseTags.isNotEmpty()) return releaseTags
        val releaseGroup = release.optJSONObject("release-group") ?: return emptyList()
        return extractTagsWithCounts(releaseGroup)
    }

    /**
     * Extract tags, falling back to release-group tags.
     * Tags are primarily on release-groups in MusicBrainz.
     */
    internal fun extractReleaseTags(release: JSONObject): List<String> =
        extractReleaseTagCounts(release).map { it.first }

    internal fun extractTagsWithCounts(obj: JSONObject): List<Pair<String, Int>> {
        val tags = obj.optJSONArray("tags") ?: return emptyList()
        return (0 until tags.length())
            .map { i -> tags.getJSONObject(i) }
            .sortedByDescending { it.optInt("count", 0) }
            .map { it.getString("name") to it.optInt("count", 0) }
    }

    internal fun extractTags(obj: JSONObject): List<String> =
        extractTagsWithCounts(obj).map { it.first }

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
