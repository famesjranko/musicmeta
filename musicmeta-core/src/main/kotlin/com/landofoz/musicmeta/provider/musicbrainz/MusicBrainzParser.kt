package com.landofoz.musicmeta.provider.musicbrainz

import org.json.JSONArray
import org.json.JSONObject

/** Parses MusicBrainz JSON API responses into internal DTOs. */
internal object MusicBrainzParser {

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

    /**
     * [albumHint], when present, is the request's own album — not necessarily the query's
     * `release:` term (the hint-less fallback search still wants it for ranking/art purposes). See
     * [findArtReleaseGroup] for how it's used.
     */
    fun parseRecordings(json: JSONObject, albumHint: String? = null): List<MusicBrainzRecording> {
        val recordings = json.optJSONArray("recordings") ?: return emptyList()
        return (0 until recordings.length()).map { i ->
            parseRecordingObject(recordings.getJSONObject(i), albumHint)
        }
    }

    /** Parse a lookup response where the entity is at the top level. */
    fun parseLookupRelease(json: JSONObject): MusicBrainzRelease? {
        json.optString("id").takeIf { it.isNotBlank() } ?: return null
        return parseReleaseObject(json, defaultScore = 100, fromLookup = true)
    }

    /** Parse a lookup response for an artist at the top level. */
    fun parseLookupArtist(json: JSONObject): MusicBrainzArtist? {
        json.optString("id").takeIf { it.isNotBlank() } ?: return null
        return parseArtistObject(json, defaultScore = 100, fromLookup = true)
    }

    /**
     * Parse a lookup response for a recording at the top level. [albumHint] is the request's own
     * album, exactly as on [parseRecordings] — a lookup has no query to take it from, but the
     * request that asked for the lookup does, and it is the only thing that decides tier 0 of
     * [findArtReleaseGroup].
     *
     * `defaultScore = 100` for the same reason [parseLookupRelease] uses it: a lookup response
     * carries no `score`, and a zero would be filtered out by every score floor downstream.
     */
    fun parseLookupRecording(json: JSONObject, albumHint: String? = null): MusicBrainzRecording? {
        json.optString("id").takeIf { it.isNotBlank() } ?: return null
        return parseRecordingObject(json, albumHint, defaultScore = 100, fromLookup = true)
    }

    /**
     * Wikidata/Wikipedia URL relations off a release-group lookup — same relation shape the
     * artist path reads via [extractWikidataId]/[extractWikipediaTitle], but a release-group's
     * relations are never embedded in a release search or release lookup response, so this reads
     * a dedicated `inc=url-rels` release-group lookup.
     */
    fun parseReleaseGroupWikiLinks(json: JSONObject): Pair<String?, String?> =
        extractWikidataId(json) to extractWikipediaTitle(json)

    private fun parseReleaseObject(
        obj: JSONObject,
        defaultScore: Int = 0,
        fromLookup: Boolean = false,
    ): MusicBrainzRelease {
        val tagCounts = extractReleaseTagCounts(obj)
        val group = obj.optJSONObject("release-group")
        return MusicBrainzRelease(
            id = obj.getString("id"),
            title = obj.getString("title"),
            artistCredit = extractArtistCredit(obj),
            artistCredits = extractArtistCreditNames(obj),
            date = obj.optString("date").takeIf { it.isNotBlank() },
            country = obj.optString("country").takeIf { it.isNotBlank() },
            barcode = obj.optString("barcode").takeIf { it.isNotBlank() },
            tags = tagCounts.map { it.name },
            tagCounts = tagCounts,
            genreCounts = extractReleaseGenreCounts(obj),
            curationKnown = fromLookup,
            label = extractLabel(obj),
            releaseType = extractReleaseType(obj),
            releaseGroupId = extractReleaseGroupId(obj),
            disambiguation = obj.optString("disambiguation").takeIf { it.isNotBlank() },
            score = obj.optInt("score", defaultScore),
            hasFrontCover = extractHasFrontCover(obj),
            tracks = parseMedia(obj),
            status = obj.optString("status").takeIf { it.isNotBlank() },
            secondaryTypes = extractSecondaryTypes(group),
            trackCount = obj.optInt("track-count", -1).takeIf { it >= 0 },
            releaseGroupDisambiguation = group?.optString("disambiguation")?.takeIf { it.isNotBlank() },
            releaseGroupFirstReleaseDate = group?.optString("first-release-date")?.takeIf { it.isNotBlank() },
        )
    }

    /** The release group's `secondary-types` array, absent in some responses (a release lookup included). */
    private fun extractSecondaryTypes(group: JSONObject?): List<String> {
        val types = group?.optJSONArray("secondary-types") ?: return emptyList()
        return (0 until types.length()).mapNotNull { types.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private fun parseArtistObject(
        obj: JSONObject,
        defaultScore: Int = 0,
        fromLookup: Boolean = false,
    ): MusicBrainzArtist {
        val lifeSpan = obj.optJSONObject("life-span")
        val tagCounts = extractTagsWithCounts(obj)
        return MusicBrainzArtist(
            id = obj.getString("id"),
            name = obj.getString("name"),
            sortName = obj.optString("sort-name").takeIf { it.isNotBlank() },
            type = obj.optString("type").takeIf { it.isNotBlank() },
            country = obj.optString("country").takeIf { it.isNotBlank() },
            beginDate = lifeSpan?.optString("begin")?.takeIf { it.isNotBlank() },
            endDate = lifeSpan?.optString("end")?.takeIf { it.isNotBlank() },
            tags = tagCounts.map { it.name },
            tagCounts = tagCounts,
            genreCounts = extractGenresWithCounts(obj),
            curationKnown = fromLookup,
            aliases = extractAliases(obj),
            disambiguation = obj.optString("disambiguation").takeIf { it.isNotBlank() },
            wikidataId = extractWikidataId(obj),
            wikipediaTitle = extractWikipediaTitle(obj),
            score = obj.optInt("score", defaultScore),
            urlRelations = parseUrlRelations(obj),
            bandMembers = parseBandMembers(obj),
            rating = extractRating(obj),
        )
    }

    private fun parseRecordingObject(
        obj: JSONObject,
        albumHint: String? = null,
        defaultScore: Int = 0,
        fromLookup: Boolean = false,
    ): MusicBrainzRecording {
        val tagCounts = extractTagsWithCounts(obj)
        val artReleaseGroup = findArtReleaseGroup(obj, albumHint)
        return MusicBrainzRecording(
            id = obj.getString("id"),
            title = obj.getString("title"),
            isrcs = extractIsrcs(obj),
            tags = tagCounts.map { it.name },
            tagCounts = tagCounts,
            genreCounts = extractGenresWithCounts(obj),
            curationKnown = fromLookup,
            rating = extractRating(obj),
            score = obj.optInt("score", defaultScore),
            disambiguation = obj.optString("disambiguation").takeIf { it.isNotBlank() },
            artistCredit = extractArtistCredit(obj),
            artistCredits = extractArtistCreditNames(obj),
            hasOfficialAlbumRelease = findStrictOfficialAlbumReleaseGroup(obj) != null,
            artReleaseGroupId = artReleaseGroup?.optString("id")?.takeIf { it.isNotBlank() },
            artReleaseGroupTitle = artReleaseGroup?.optString("title")?.takeIf { it.isNotBlank() },
            lengthMs = obj.optLong("length", 0L).takeIf { it > 0 },
            isVideo = obj.optBoolean("video", false),
        )
    }

    /**
     * The release-group object of a recording search hit's first Official release whose
     * release-group primary-type is Album (MB embeds a handful of `releases` per recording hit).
     * Strict on purpose — backs [MusicBrainzRecording.hasOfficialAlbumRelease], which prefers the
     * studio-album cut when ranking a recording search pool.
     */
    private fun findStrictOfficialAlbumReleaseGroup(recording: JSONObject): JSONObject? {
        val releases = recording.optJSONArray("releases") ?: return null
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optString("status") != "Official") continue
            val group = release.optJSONObject("release-group") ?: continue
            if (group.optString("primary-type") == "Album") return group
        }
        return null
    }

    /**
     * The release-group object to try CAA art against, from the same `releases` MB embeds on a
     * recording search hit. Looser than [findStrictOfficialAlbumReleaseGroup] on purpose: MB embeds
     * only the releases matching the search query's own `release:` hint, so the "right" Album-type
     * release is often simply not in the payload at all — verified live for "Enter Sandman" under
     * the "Metallica" album hint, whose embedded release is Official on an "Other" (box-set)
     * release-group. Backs [MusicBrainzRecording.artReleaseGroupId]. [albumHint], when non-blank,
     * adds a tier 0 ahead of the three below: the recording's `releases` array is embedding order,
     * not relevance order, so a compilation ("The Best Of") can sit ahead of the requested album
     * ("OK Computer") even when both are Official Album releases in the same array — verified live
     * for Radiohead "Karma Police": the search hit embeds both, "The Best Of" first. Tiers, first
     * match within the best tier wins:
     *
     * 0. (only with [albumHint]) any release whose release-group title matches [albumHint]
     *    case-insensitively, regardless of status — an explicit album request outranks the
     *    Official/Album-type heuristic below, since it's a stronger, user-supplied signal.
     * 1. Official release, release-group primary-type Album (delegates to
     *    [findStrictOfficialAlbumReleaseGroup] — the same shape [hasOfficialAlbumRelease] requires).
     * 2. release status exactly `"Official"`, any release-group primary-type — e.g. a box set,
     *    single, or compilation MB status-marks Official. Deliberately status-exact, not
     *    status-exclusion: MB's other statuses (`Promotion`, `Bootleg`, `Pseudo-Release`,
     *    `Withdrawn`, `Cancelled`) all fall through to tier 3 rather than being special-cased here.
     * 3. any release carrying a release-group id at all, regardless of status — including
     *    `Bootleg`/unofficial releases, accepted deliberately as a last resort: some art (or a cheap
     *    CAA NotFound when the group has no images) beats none, and a bootleg's release-group is
     *    frequently the *only* release-group MB's search embeds for a bootleg-only recording.
     */
    private fun findArtReleaseGroup(recording: JSONObject, albumHint: String? = null): JSONObject? {
        val releases = recording.optJSONArray("releases") ?: return null
        findAlbumHintReleaseGroup(releases, albumHint)?.let { return it }
        findStrictOfficialAlbumReleaseGroup(recording)?.let { return it }
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optString("status") != "Official") continue
            release.optJSONObject("release-group")?.let { return it }
        }
        for (i in 0 until releases.length()) {
            val group = releases.getJSONObject(i).optJSONObject("release-group") ?: continue
            if (group.optString("id").isNotBlank()) return group
        }
        return null
    }

    /** Tier 0 of [findArtReleaseGroup]: a release-group whose title matches [albumHint], any status. */
    private fun findAlbumHintReleaseGroup(releases: JSONArray, albumHint: String?): JSONObject? {
        val trimmedHint = albumHint?.trim()?.takeIf { it.isNotBlank() } ?: return null
        for (i in 0 until releases.length()) {
            val group = releases.getJSONObject(i).optJSONObject("release-group") ?: continue
            if (group.optString("title").trim().equals(trimmedHint, ignoreCase = true)) return group
        }
        return null
    }

    /** Parse band members from artist-rels in an artist lookup response.
     *  Only includes "backward" direction — people who are members OF this entity.
     *  "forward" direction means this entity is a member of the related artist (e.g. side projects).
     *  Deduplicates by member ID — merges roles and picks the widest date range. */
    fun parseBandMembers(json: JSONObject): List<MusicBrainzBandMember> {
        val relations = json.optJSONArray("relations") ?: return emptyList()
        val byId = LinkedHashMap<String, MutableList<RawMemberRel>>()
        for (i in 0 until relations.length()) {
            val rel = relations.getJSONObject(i)
            if (rel.optString("type") != "member of band") continue
            if (rel.optString("direction") != "backward") continue
            val artist = rel.optJSONObject("artist") ?: continue
            val id = artist.optString("id").takeIf { it.isNotBlank() } ?: continue
            val attrs = rel.optJSONArray("attributes")
            val role = attrs?.let { if (it.length() > 0) it.getString(0) else null }
            byId.getOrPut(id) { mutableListOf() }.add(
                RawMemberRel(
                    name = artist.getString("name"),
                    role = role,
                    beginDate = rel.optString("begin").takeIf { it.isNotBlank() },
                    endDate = rel.optString("end").takeIf { it.isNotBlank() },
                    ended = rel.optBoolean("ended", false),
                ),
            )
        }
        return byId.map { (id, rels) ->
            val roles = rels.mapNotNull { it.role }.distinct()
            val earliest = rels.mapNotNull { it.beginDate }.minOrNull()
            val latest = rels.mapNotNull { it.endDate }.maxOrNull()
            val stillActive = rels.any { !it.ended }
            MusicBrainzBandMember(
                name = rels.first().name,
                id = id,
                role = roles.joinToString(", ").takeIf { it.isNotEmpty() },
                beginDate = earliest,
                endDate = if (stillActive) null else latest,
                ended = !stillActive,
            )
        }
    }

    private data class RawMemberRel(
        val name: String,
        val role: String?,
        val beginDate: String?,
        val endDate: String?,
        val ended: Boolean,
    )

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

    /**
     * MusicBrainz medium `format` names that mean "moving picture", not audio, per MusicBrainz's
     * documented format list (musicbrainz.org/doc/Release/Format). Matched by *exact* name,
     * case-insensitively, never by substring: a substring `"DVD"` filter would also reject
     * `"DVD-Audio"`, which the same page lists as an audio format, and `"Blu-ray"` similarly must
     * not reject a hypothetical audio-only Blu-ray release.
     *
     * Two entries are judgement calls the page leaves unstated, both resolved the same way — treat
     * the ambiguous bare name as the video variant, since that is the overwhelmingly common case for
     * an unspecified bonus disc:
     * - bare `"DVD"` (an editor who didn't record a child format). Known cost: a true audio-only
     *   disc catalogued as plain "DVD" instead of "DVD-Audio" loses its tracklist.
     * - bare `"Blu-ray"` — the documented list has no distinct "Blu-ray Audio" entry at all (only
     *   "Blu-ray" and the writable "Blu-ray-R"), so a plain "Blu-ray" medium is presumed video.
     *
     * Hybrid discs are listed per side: only the `"… (DVD-Video side)"` names are video; the
     * `(CD side)`/`(DVD-Audio side)` siblings stay audio. LaserDisc appears both bare and sized
     * (`8" LaserDisc`, `12" LaserDisc`) — all three are video.
     *
     * Known gap: formats absent from this set but also video (e.g. "Blu-ray-R", "CDV", "VHD",
     * "Ultra HD Blu-ray") are treated as audio and kept — narrower false-negatives were preferred
     * over a broader list risking a false-positive on an audio format not yet seen.
     */
    private val VIDEO_MEDIA_FORMATS = setOf(
        "dvd", "dvd-video", "blu-ray", "hd-dvd", "vhs", "vcd", "svcd", "betamax", "umd",
        "laserdisc", "8\" laserdisc", "12\" laserdisc",
        "dualdisc (dvd-video side)", "dvdplus (dvd-video side)",
    )

    private fun isVideoFormat(format: String?): Boolean =
        format != null && format.trim().lowercase() in VIDEO_MEDIA_FORMATS

    /**
     * Parse tracks from the `media` array in a release lookup response.
     *
     * Video media (see [VIDEO_MEDIA_FORMATS]) are dropped — MusicBrainz release lookups embed a
     * bonus DVD/Blu-ray's own tracklist alongside the audio disc's, and flattening both produced
     * duplicated positions (e.g. St. Anger's CD + bonus DVD-Video rendering 22 "tracks" for an
     * 11-track album). A release with nothing but video media keeps it: an all-video tracklist beats
     * an empty one. Audio media (including legitimate multi-disc releases — a 2×CD or 2×LP) are all
     * kept, with positions made cumulative across media in document order, since [TrackInfo.position]
     * is the release's only ordering slot and per-medium numbering would otherwise collide (disc 2
     * track 1 would repeat position 1 instead of continuing at 12 for an 11-track disc 1).
     */
    fun parseMedia(json: JSONObject): List<MusicBrainzTrack> {
        val media = json.optJSONArray("media") ?: return emptyList()
        val allMedia = (0 until media.length()).map { media.getJSONObject(it) }
        val audioMedia = allMedia.filterNot { isVideoFormat(it.optString("format").takeIf(String::isNotBlank)) }
        val chosenMedia = audioMedia.ifEmpty { allMedia }

        val tracks = mutableListOf<MusicBrainzTrack>()
        var positionOffset = 0
        for (medium in chosenMedia) {
            val trackList = medium.optJSONArray("tracks") ?: continue
            for (t in 0 until trackList.length()) {
                val track = trackList.getJSONObject(t)
                val recordingId = track.optJSONObject("recording")
                    ?.optString("id")?.takeIf { it.isNotBlank() }
                tracks.add(
                    MusicBrainzTrack(
                        title = track.getString("title"),
                        position = track.getInt("position") + positionOffset,
                        durationMs = track.optLong("length", 0L).takeIf { it > 0 },
                        id = recordingId ?: track.optString("id").takeIf { it.isNotBlank() },
                    ),
                )
            }
            positionOffset += trackList.length()
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
                append(credit.optJSONObject("artist")?.optString("name").orEmpty())
                append(credit.optString("joinphrase", ""))
            }
        }.takeIf { it.isNotBlank() }
    }

    /**
     * Each credited artist's name individually, unlike [extractArtistCredit]'s single join-phrase
     * string — a multi-artist credit ("Artist A & Artist B") should still match a caller-requested
     * artist that is only one of them, which a joined-string equality check cannot do.
     */
    internal fun extractArtistCreditNames(obj: JSONObject): List<String> {
        val credits = obj.optJSONArray("artist-credit") ?: return emptyList()
        return (0 until credits.length()).mapNotNull { i ->
            credits.getJSONObject(i).optJSONObject("artist")?.optString("name")?.takeIf { it.isNotBlank() }
        }
    }

    /**
     * Extract tags with counts, falling back to release-group tags.
     * Tags are primarily on release-groups in MusicBrainz.
     */
    internal fun extractReleaseTagCounts(release: JSONObject): List<TagCount> {
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
        extractReleaseTagCounts(release).map { it.name }

    /**
     * The curated `genres` array — MusicBrainz's controlled vocabulary, and a subset of the same
     * response's free-text `tags` carrying the same vote counts. Only present when the request asked
     * for `inc=genres`; a search hit never carries it.
     */
    internal fun extractGenresWithCounts(obj: JSONObject): List<TagCount> {
        val genres = obj.optJSONArray("genres") ?: return emptyList()
        return (0 until genres.length())
            .map { i -> genres.getJSONObject(i) }
            .sortedByDescending { it.optInt("count", 0) }
            .map { TagCount(it.getString("name"), it.optInt("count", 0)) }
    }

    /** Curated genres off a release, falling back to its release-group as [extractReleaseTagCounts] does. */
    internal fun extractReleaseGenreCounts(release: JSONObject): List<TagCount> {
        val releaseGenres = extractGenresWithCounts(release)
        if (releaseGenres.isNotEmpty()) return releaseGenres
        val releaseGroup = release.optJSONObject("release-group") ?: return emptyList()
        return extractGenresWithCounts(releaseGroup)
    }

    /**
     * An artist's or recording's `aliases`, present on a lookup asking for `inc=aliases` and — for an
     * artist — on every search hit without one.
     *
     * `primary` is `null` far more often than it is `false` (verified live 2026-08-12: four of
     * Coldplay's five aliases), so it is read as absent rather than as a negative claim.
     */
    internal fun extractAliases(obj: JSONObject): List<MusicBrainzAlias> {
        val aliases = obj.optJSONArray("aliases") ?: return emptyList()
        return (0 until aliases.length()).mapNotNull { i ->
            val alias = aliases.getJSONObject(i)
            val name = alias.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MusicBrainzAlias(
                name = name,
                type = alias.optString("type").takeIf { it.isNotBlank() },
                locale = alias.optString("locale").takeIf { it.isNotBlank() },
                primary = alias.optBoolean("primary", false),
            )
        }
    }

    /**
     * The `rating` block from a lookup asking for `inc=ratings`.
     *
     * Null unless someone has voted: MusicBrainz sends `{"votes-count": 0, "value": null}` for an
     * entity nobody has rated (verified live 2026-08-12), which is an absence and not a score of
     * zero — `optDouble` would otherwise hand back its own default and invent one
     * (`docs/pitfalls.md` §3).
     */
    internal fun extractRating(obj: JSONObject): MusicBrainzRating? {
        val rating = obj.optJSONObject("rating") ?: return null
        if (rating.isNull("value")) return null
        val value = rating.optDouble("value", Double.NaN)
        if (value.isNaN()) return null
        return MusicBrainzRating(value = value.toFloat(), votes = rating.optInt("votes-count", 0))
    }

    internal fun extractTagsWithCounts(obj: JSONObject): List<TagCount> {
        val tags = obj.optJSONArray("tags") ?: return emptyList()
        return (0 until tags.length())
            .map { i -> tags.getJSONObject(i) }
            .sortedByDescending { it.optInt("count", 0) }
            .map { TagCount(it.getString("name"), it.optInt("count", 0)) }
    }

    internal fun extractTags(obj: JSONObject): List<String> =
        extractTagsWithCounts(obj).map { it.name }

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

    /**
     * Extract an **English** Wikipedia article title from a MusicBrainz `wikipedia` URL relation.
     *
     * Only `en.wikipedia.org` relations count: the title is consumed by `WikipediaProvider`, which
     * only ever queries `en.wikipedia.org`, so a title from another language wiki 404s and the bio
     * comes back `NotFound`. An artist whose only relation is another language (Portishead carries
     * `fr.wikipedia.org/wiki/Portishead_(groupe)` and nothing else) yields `null` here and resolves
     * through the Wikidata `enwiki` sitelink instead.
     */
    internal fun extractWikipediaTitle(obj: JSONObject): String? {
        val relations = obj.optJSONArray("relations") ?: return null
        for (i in 0 until relations.length()) {
            val rel = relations.getJSONObject(i)
            if (rel.optString("type") == "wikipedia") {
                val url = rel.optJSONObject("url")?.optString("resource") ?: continue
                // URL format: https://en.wikipedia.org/wiki/Title_Name
                if (!url.contains("//en.wikipedia.org/")) continue
                return url.substringAfterLast("/wiki/").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    /** Null where nothing in the response answers the question — see [MusicBrainzRelease.hasFrontCover]. */
    private fun extractHasFrontCover(release: JSONObject): Boolean? {
        val coverArt = release.optJSONObject("cover-art-archive") ?: return null
        if (!coverArt.has("front")) return null
        return coverArt.optBoolean("front", false)
    }

    private fun extractIsrcs(recording: JSONObject): List<String> {
        val isrcs = recording.optJSONArray("isrcs") ?: return emptyList()
        return (0 until isrcs.length()).map { isrcs.getString(it) }
    }
}
