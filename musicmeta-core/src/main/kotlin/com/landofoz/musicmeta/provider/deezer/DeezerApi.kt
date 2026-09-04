package com.landofoz.musicmeta.provider.deezer

import com.landofoz.musicmeta.drift.SchemaTarget
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.TitleMatcher
import com.landofoz.musicmeta.engine.bestArtistMatch
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.bodyOrThrowTransient
import com.landofoz.musicmeta.provider.encodeQueryValue
import com.landofoz.musicmeta.takeIfNotEmpty
import org.json.JSONObject
import java.io.IOException

/**
 * Deezer public search API. No authentication required.
 * Rate limit: ~10 requests/second.
 */
internal class DeezerApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    suspend fun searchAlbums(query: String, limit: Int): List<DeezerAlbumResult> {
        val json = fetchJson(albumSearchUrl(query, limit)) ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val album = data.getJSONObject(i)
            val artist = album.optJSONObject("artist")
            DeezerAlbumResult(
                id = album.optLong("id"),
                title = album.optString("title", ""),
                artistName = artist?.optString("name", "").orEmpty(),
                coverSmall = album.optString("cover_small").takeIfNotEmpty(),
                coverMedium = album.optString("cover_medium").takeIfNotEmpty(),
                coverBig = album.optString("cover_big").takeIfNotEmpty(),
                coverXl = album.optString("cover_xl").takeIfNotEmpty(),
                nbTracks = album.optInt("nb_tracks", 0).takeIf { it > 0 },
                recordType = album.optString("record_type").takeIfNotEmpty(),
                explicitLyrics = if (album.has("explicit_lyrics")) album.optBoolean("explicit_lyrics") else null,
            )
        }
    }

    /**
     * Finds the artist a human would mean by [name].
     *
     * Deezer's result order is not trustworthy: "Radiohead" returns an exact-name ghost
     * (0 albums, 470 fans) ahead of the real artist (id 399). So fetch a pool of candidates,
     * keep the ones [ArtistMatcher.isMatch] accepts, and rank those by name quality first
     * ([ArtistMatcher.matchQuality]) and only then by fans and albums.
     *
     * Name quality outranks popularity at every step, so a popular wrong-name artist cannot win:
     * not one the matcher rejects, and not one it accepts only loosely while a closer name is in
     * the pool. Popularity decides between candidates whose names are equally good — which is the
     * ghost case, two entries both named exactly "Radiohead".
     */
    suspend fun searchArtist(name: String): DeezerArtistSearchResult? {
        val encoded = encodeQueryValue(name)
        val url = "$BASE_URL/search/artist?q=$encoded&limit=$ARTIST_SEARCH_LIMIT"
        val json = fetchJson(url) ?: return null

        val data = json.optJSONArray("data") ?: return null
        return (0 until data.length())
            .mapNotNull { data.optJSONObject(it) }
            .bestArtistMatch(
                expected = name,
                tieBreak = compareBy({ it.optLong("nb_fan") }, { it.optLong("nb_album") }),
            ) { it.optString("name", "") }
            ?.let { artist ->
                DeezerArtistSearchResult(
                    id = artist.optLong("id"),
                    name = artist.optString("name", ""),
                    pictureSmall = artist.optString("picture_small").takeIfNotEmpty(),
                    pictureMedium = artist.optString("picture_medium").takeIfNotEmpty(),
                    pictureBig = artist.optString("picture_big").takeIfNotEmpty(),
                    pictureXl = artist.optString("picture_xl").takeIfNotEmpty(),
                )
            }
    }

    suspend fun getArtistAlbums(artistId: Long, limit: Int = 50): List<DeezerArtistAlbum> {
        val url = "$BASE_URL/artist/$artistId/albums?limit=$limit"
        val json = fetchJson(url) ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val album = data.getJSONObject(i)
            DeezerArtistAlbum(
                id = album.optLong("id"),
                title = album.optString("title", ""),
                releaseDate = album.optString("release_date").takeIfNotEmpty(),
                recordType = album.optString("record_type").takeIfNotEmpty(),
                coverSmall = album.optString("cover_small").takeIfNotEmpty(),
                coverMedium = album.optString("cover_medium").takeIfNotEmpty(),
            )
        }
    }

    /**
     * The album resource, one path segment past the search hit — carries `upc`/`label`/
     * `release_date`, none of which the search response returns. `genres` is read live (12/12
     * probed albums, 2026-08-12) but deliberately not parsed here: every case returned exactly one
     * coarse editorial tag, a strict parent of Last.fm's vote-weighted tags, so it would outrank a
     * real tag cloud in [com.landofoz.musicmeta.engine.GenreMerger] with none of its weight.
     */
    suspend fun getAlbum(albumId: Long): DeezerAlbum? {
        val json = fetchJson("$BASE_URL/album/$albumId") ?: return null
        return DeezerAlbum(
            id = json.optLong("id", albumId),
            upc = json.optString("upc").takeIfNotEmpty(),
            label = json.optString("label").takeIfNotEmpty(),
            releaseDate = json.optString("release_date").takeIfNotEmpty(),
        )
    }

    suspend fun getAlbumTracks(albumId: Long): List<DeezerTrack> {
        val url = "$BASE_URL/album/$albumId/tracks"
        val json = fetchJson(url) ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val track = data.getJSONObject(i)
            DeezerTrack(
                id = track.optLong("id"),
                title = track.optString("title", ""),
                trackPosition = track.optInt("track_position", 0),
                durationSec = track.optInt("duration", 0),
            )
        }
    }

    suspend fun getRelatedArtists(artistId: Long, limit: Int = 20): List<DeezerRelatedArtist> {
        val url = "$BASE_URL/artist/$artistId/related?limit=$limit"
        val json = fetchJson(url) ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val artist = data.getJSONObject(i)
            DeezerRelatedArtist(
                id = artist.optLong("id"),
                name = artist.optString("name", ""),
            )
        }
    }

    /**
     * Finds the track a human would mean by [title]/[artist], optionally narrowed by [album].
     *
     * Three queries run narrowest-first — field query with an album hint (when [album] is
     * supplied), field query without it, then a plain keyword query — falling through whenever a
     * tier yields no candidate that survives the [ArtistMatcher]/[TitleMatcher] filters. Whichever
     * tier produced an accepted pool, [rankTracks] applies the provider's tier order to it.
     */
    suspend fun searchTrack(title: String, artist: String, album: String? = null): DeezerTrackSearchResult? {
        val queries = buildList {
            album?.takeIf { it.isNotBlank() }?.let { add(advancedTrackUrl(artist, title, it)) }
            add(advancedTrackUrl(artist, title, album = null))
            add(plainTrackUrl(artist, title))
        }
        for (url in queries) {
            val candidates = fetchTrackPool(url)
                .orEmpty()
                .filter { ArtistMatcher.isMatch(artist, it.optJSONObject("artist")?.optString("name", "").orEmpty()) }
                .filter { TitleMatcher.equivalent(title, it.optString("title", "")) }
            if (candidates.isNotEmpty()) {
                return candidates.rankTracks(title, artist, album)?.toTrackSearchResult()
            }
        }
        return null
    }

    private suspend fun fetchTrackPool(url: String): List<JSONObject>? {
        val json = fetchJson(url) ?: return null
        val data = json.optJSONArray("data") ?: return null
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }
    }

    private fun plainTrackUrl(artist: String, title: String): String {
        val encoded = encodeQueryValue("$artist $title")
        return "$BASE_URL/search/track?q=$encoded&limit=$TRACK_SEARCH_LIMIT"
    }

    private fun advancedTrackUrl(artist: String, title: String, album: String?): String {
        val query = buildString {
            append("""artist:"$artist" track:"$title"""")
            if (album != null) append(""" album:"$album"""")
        }
        val encoded = encodeQueryValue(query)
        return "$BASE_URL/search/track?q=$encoded&limit=$TRACK_SEARCH_LIMIT"
    }

    private fun JSONObject.toTrackSearchResult(): DeezerTrackSearchResult {
        val trackArtist = optJSONObject("artist")
        return DeezerTrackSearchResult(
            id = optLong("id"),
            title = optString("title", ""),
            artistName = trackArtist?.optString("name", "").orEmpty(),
            previewUrl = optString("preview").takeIf { it.isNotBlank() },
            durationSec = optInt("duration").takeIf { it > 0 },
            albumTitle = optJSONObject("album")?.optString("title")?.takeIf { it.isNotBlank() },
            artistId = trackArtist?.optLong("id")?.takeIf { it != 0L },
        )
    }

    /**
     * Ranks a candidate pool for [title]/[artist] (already filtered to artist-name matches),
     * highest tier first, keeping pool order among ties (`maxWithOrNull` keeps the first maximum,
     * same convention as [com.landofoz.musicmeta.engine.bestArtistMatch]):
     *
     * 1. exact (case-insensitive) title match — separates "Blackened" from "Blackened 2020"
     * 2. artist name quality ([ArtistMatcher.matchQuality]) — a closer name beats a looser one
     * 3. album containment, when [album] is given — the hinted album's edition over any other
     * 4. no edition marker the request didn't ask for ([hasUnrequestedMarker]) — prefers
     *    "Harvester of Sorrow" over "Harvester Of Sorrow (Live In Mexico City)" when neither is an
     *    exact title match
     *
     * Tier 1 alone resolves most of tier 4's job — a live/remix title is rarely an exact string
     * match to begin with — but tier 4 still matters when *no* candidate has the exact title, so
     * ranking has to choose among approximations.
     */
    private fun List<JSONObject>.rankTracks(title: String, artist: String, album: String?): JSONObject? =
        map { it to it.trackRank(title, artist, album) }
            .maxWithOrNull(
                compareBy(
                    { it.second.exactTitle },
                    { it.second.artistQuality },
                    { it.second.albumMatch },
                    { it.second.cleanTitle },
                ),
            )
            ?.first

    private data class TrackRank(
        val exactTitle: Boolean,
        val artistQuality: Int,
        val albumMatch: Boolean,
        val cleanTitle: Boolean,
    )

    private fun JSONObject.trackRank(title: String, artist: String, album: String?): TrackRank {
        val candidateTitle = optString("title", "")
        val candidateArtist = optJSONObject("artist")?.optString("name", "").orEmpty()
        val candidateAlbum = optJSONObject("album")?.optString("title", "").orEmpty()
        return TrackRank(
            exactTitle = candidateTitle.trim().equals(title.trim(), ignoreCase = true),
            artistQuality = ArtistMatcher.matchQuality(artist, candidateArtist),
            albumMatch = album.isNullOrBlank() ||
                candidateAlbum.contains(album, ignoreCase = true) ||
                album.contains(candidateAlbum, ignoreCase = true),
            cleanTitle = !hasUnrequestedMarker(candidateTitle, title),
        )
    }

    /**
     * True when [candidateTitle] carries an edition marker that [requestedTitle] does not ask
     * for. A marker the request itself contains (a genuine request for the live take) is not
     * penalised. Two shapes are supported:
     *
     * - a parenthetical containing a live/remaster/demo/remix word — "(Live In Mexico City)"
     * - a bare suffix appended to the requested title — Deezer titles the "Blackened" remix
     *   "Blackened 2020" (no parentheses, marked only by the trailing year) and live/rehearsal
     *   takes as `" - Live at …"` dash suffixes, so the suffix is checked for the same marker
     *   words *and* for a bare year. The suffix check only fires when the candidate extends the
     *   requested title, so a title that legitimately contains a year ("1979") is never penalised
     *   by its own name.
     */
    private fun hasUnrequestedMarker(candidateTitle: String, requestedTitle: String): Boolean {
        val requestedLower = requestedTitle.trim().lowercase()
        val parenthetical = PARENTHETICAL_REGEX.findAll(candidateTitle).any { match ->
            val marker = match.groupValues[1].lowercase()
            UNREQUESTED_TITLE_MARKERS.any { marker.contains(it) && !requestedLower.contains(it) }
        }
        if (parenthetical) return true

        val candidateLower = candidateTitle.trim().lowercase()
        if (!candidateLower.startsWith(requestedLower)) return false
        val suffix = candidateLower.substring(requestedLower.length)
        return UNREQUESTED_TITLE_MARKERS.any { suffix.contains(it) && !requestedLower.contains(it) } ||
            (YEAR_REGEX.containsMatchIn(suffix) && !YEAR_REGEX.containsMatchIn(requestedLower))
    }

    suspend fun getArtistTop(artistId: Long, limit: Int = 10): List<DeezerTopTrack> {
        val url = "$BASE_URL/artist/$artistId/top?limit=$limit"
        val json = fetchJson(url) ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val track = data.getJSONObject(i)
            val artist = track.optJSONObject("artist")
            val album = track.optJSONObject("album")
            DeezerTopTrack(
                id = track.optLong("id"),
                title = track.optString("title", ""),
                artistName = artist?.optString("name", "").orEmpty(),
                albumTitle = album?.optString("title")?.takeIf { it.isNotBlank() },
                durationSec = track.optInt("duration", 0),
                rank = track.optInt("rank", 0),
            )
        }
    }

    suspend fun getArtistRadio(artistId: Long, limit: Int = 25): List<DeezerRadioTrack> {
        val url = "$BASE_URL/artist/$artistId/radio?limit=$limit"
        val json = fetchJson(url) ?: return emptyList()

        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).map { i ->
            val track = data.getJSONObject(i)
            val artist = track.optJSONObject("artist")
            val album = track.optJSONObject("album")
            DeezerRadioTrack(
                id = track.optLong("id"),
                title = track.optString("title", ""),
                artistName = artist?.optString("name", "").orEmpty(),
                albumTitle = album?.optString("title")?.takeIf { it.isNotBlank() },
                durationSec = track.optInt("duration", 0),
            )
        }
    }

    /** Fetches a single track by Deezer ID. Returns preview URL and metadata. */
    suspend fun getTrack(trackId: Long): DeezerTrackSearchResult? {
        val json = fetchJson("$BASE_URL/track/$trackId") ?: return null
        val artist = json.optJSONObject("artist")
        return DeezerTrackSearchResult(
            id = json.optLong("id"),
            title = json.optString("title", ""),
            artistName = artist?.optString("name", "").orEmpty(),
            previewUrl = json.optString("preview").takeIf { it.isNotBlank() },
            durationSec = json.optInt("duration").takeIf { it > 0 },
            albumTitle = json.optJSONObject("album")?.optString("title")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The single fetch every call above goes through: rate limit, HTTP-level transient
     * classification ([bodyOrThrowTransient]), then Deezer's own application-level one.
     *
     * Deezer answers application errors with **HTTP 200** and an `error` object in place of the
     * payload, so no HTTP-layer classification can see them. Treating that body as an empty result
     * would record a breaker *success* while answering "nothing" to everything (`docs/pitfalls.md` §4).
     *
     * The split: `code == 4` is the quota rejection, thrown as [IOException] so it travels the
     * provider's existing `catch { mapError(type, e) }` into `Error(ErrorKind.NETWORK)`. Any other
     * `error` object is a genuine "no such thing" and returns `null`, which stays `NotFound`.
     *
     * **`code == 4` is a heuristic, not a contract.** Deezer's own error-code table is login-gated,
     * so there is no citable official list; the quota shape is community-corroborated only, and may
     * change without notice. Widen it when another transient code is actually observed — not
     * speculatively.
     */
    private suspend fun fetchJson(url: String): JSONObject? = rateLimiter.execute {
        val json = httpClient.fetchJsonResult(url).bodyOrThrowTransient() ?: return@execute null
        val error = json.optJSONObject("error") ?: return@execute json
        if (error.optInt("code") == QUOTA_ERROR_CODE) {
            throw IOException("Deezer quota exceeded: ${error.optString("message")}")
        }
        null
    }

    companion object {
        const val BASE_URL = "https://api.deezer.com"

        /** The URL [searchAlbums] requests. */
        fun albumSearchUrl(query: String, limit: Int): String =
            "$BASE_URL/search/album?q=${encodeQueryValue(query)}&limit=$limit"

        /**
         * Schema-pin target, mirroring [searchAlbums]'s parse. `cover_xl` is the artwork the
         * album path serves, and `nb_tracks`/`record_type` are what an edition is chosen on.
         */
        val SCHEMA_PIN_TARGETS: List<SchemaTarget> = listOf(
            SchemaTarget(
                provider = "deezer",
                route = "album search",
                url = albumSearchUrl("OK Computer", limit = 5),
                requiredPaths = listOf(
                    "data[0].id",
                    "data[0].title",
                    "data[0].artist.name",
                    "data[0].cover_xl",
                    "data[0].nb_tracks",
                    "data[0].record_type",
                ),
            ),
        )

        /** Candidate pool size for artist search — enough hits for a ghost to be outvoted. */
        const val ARTIST_SEARCH_LIMIT = 10

        /** Candidate pool size for track search — enough hits for a wrong edition to be outranked. */
        const val TRACK_SEARCH_LIMIT = 5

        /** Deezer's "Quota limit exceeded" code, carried in a 200 response's `error` object. */
        const val QUOTA_ERROR_CODE = 4

        /** Matches a `(...)` group in a track title, to test its contents against [UNREQUESTED_TITLE_MARKERS]. */
        val PARENTHETICAL_REGEX = Regex("""\(([^)]*)\)""")

        /** Edition markers that make a title a worse match unless the request itself asked for one. */
        val UNREQUESTED_TITLE_MARKERS = listOf("live", "remaster", "demo", "remix")

        /** A bare year in a title suffix marks a reissue/remix edition, e.g. "Blackened 2020". */
        val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
    }
}
