package com.landofoz.musicmeta.provider.musicbrainz

import org.json.JSONObject

/**
 * Parses credits and release-group detail from MusicBrainz JSON responses.
 *
 * Extracted from [MusicBrainzParser] to keep both files under the 300-line limit.
 */
internal object MusicBrainzCreditParser {

    /**
     * Parse recording credits from a recording lookup response with artist-rels and work-rels.
     *
     * Processes top-level artist-rels and work-rels (composer/lyricist/arranger from nested
     * work relations when the relation type is "performance").
     */
    fun parseRecordingCredits(json: JSONObject): List<MusicBrainzCredit> {
        val relations = json.optJSONArray("relations") ?: return emptyList()
        val credits = mutableListOf<MusicBrainzCredit>()
        for (i in 0 until relations.length()) {
            val rel = relations.getJSONObject(i)
            val targetType = rel.optString("target-type")
            when {
                targetType == "artist" -> {
                    val artist = rel.optJSONObject("artist") ?: continue
                    val relType = rel.optString("type")
                    val attrs = rel.optJSONArray("attributes")
                    val firstAttr = attrs?.let { if (it.length() > 0) it.getString(0) else null }
                    val (role, category) = mapArtistRelType(relType, firstAttr)
                    credits.add(
                        MusicBrainzCredit(
                            name = artist.getString("name"),
                            id = artist.optString("id").takeIf { it.isNotBlank() },
                            role = role,
                            roleCategory = category,
                        ),
                    )
                }
                targetType == "work" && rel.optString("type") == "performance" -> {
                    val work = rel.optJSONObject("work") ?: continue
                    val workRels = work.optJSONArray("relations") ?: continue
                    for (j in 0 until workRels.length()) {
                        val workRel = workRels.getJSONObject(j)
                        if (workRel.optString("target-type") != "artist") continue
                        val artist = workRel.optJSONObject("artist") ?: continue
                        val workRelType = workRel.optString("type")
                        val (role, category) = mapWorkRelType(workRelType)
                        credits.add(
                            MusicBrainzCredit(
                                name = artist.getString("name"),
                                id = artist.optString("id").takeIf { it.isNotBlank() },
                                role = role,
                                roleCategory = category,
                            ),
                        )
                    }
                }
            }
        }
        return credits
    }

    /**
     * Parse a release-group lookup response with releases, labels, and media.
     * Extracts each release as a MusicBrainzEdition with format, label, catalog number, etc.
     */
    fun parseReleaseGroupDetail(json: JSONObject): MusicBrainzReleaseGroupDetail {
        val id = json.getString("id")
        val title = json.getString("title")
        val releasesArray = json.optJSONArray("releases")
            ?: return MusicBrainzReleaseGroupDetail(id, title, emptyList())
        val editions = (0 until releasesArray.length()).map { i ->
            val obj = releasesArray.getJSONObject(i)
            val media = obj.optJSONArray("media")
            val format = if (media != null && media.length() > 0) {
                media.getJSONObject(0).optString("format").takeIf { it.isNotBlank() }
            } else null
            val labelInfo = obj.optJSONArray("label-info")
            val firstLabel = if (labelInfo != null && labelInfo.length() > 0) {
                labelInfo.getJSONObject(0)
            } else null
            val label = firstLabel?.optJSONObject("label")
                ?.optString("name")?.takeIf { it.isNotBlank() }
            val catalogNumber = firstLabel?.optString("catalog-number")
                ?.takeIf { it.isNotBlank() }
            MusicBrainzEdition(
                id = obj.getString("id"),
                title = obj.getString("title"),
                date = obj.optString("date").takeIf { it.isNotBlank() },
                country = obj.optString("country").takeIf { it.isNotBlank() },
                barcode = obj.optString("barcode").takeIf { it.isNotBlank() },
                format = format,
                label = label,
                catalogNumber = catalogNumber,
            )
        }
        return MusicBrainzReleaseGroupDetail(id = id, title = title, releases = editions)
    }

    private fun mapArtistRelType(type: String, firstAttr: String?): Pair<String, String?> =
        when (type) {
            "vocal" -> Pair(firstAttr ?: "vocals", "performance")
            "instrument" -> Pair(firstAttr ?: "instrument", "performance")
            "performer" -> Pair("performer", "performance")
            "producer" -> Pair("producer", "production")
            "engineer" -> Pair("engineer", "production")
            "mix" -> Pair("mixer", "production")
            "mastering" -> Pair("mastering", "production")
            "recording" -> Pair("recording engineer", "production")
            else -> Pair(type, null)
        }

    private fun mapWorkRelType(type: String): Pair<String, String?> =
        when (type) {
            "composer" -> Pair("composer", "songwriting")
            "lyricist" -> Pair("lyricist", "songwriting")
            "arranger" -> Pair("arranger", "songwriting")
            else -> Pair(type, null)
        }
}
