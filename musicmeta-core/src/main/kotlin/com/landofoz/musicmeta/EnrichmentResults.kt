package com.landofoz.musicmeta

/**
 * Structured result from [EnrichmentEngine.enrich].
 *
 * Wraps the raw result map with identity resolution info, requested types,
 * and type-safe accessors that eliminate double-casting boilerplate.
 *
 * For error diagnostics on a specific type, use [result] to get the raw
 * [EnrichmentResult], or check [wasRequested] to distinguish "not requested"
 * from "not found."
 */
data class EnrichmentResults(
    /** Raw per-type results. */
    val raw: Map<EnrichmentType, EnrichmentResult>,
    /** Types that were requested in the enrich() call. */
    val requestedTypes: Set<EnrichmentType>,
    /** Identity resolution outcome. `null` when identity resolution was not attempted. */
    val identity: IdentityResolution?,
) {

    // --- Result-level access ---

    /** Get the raw [EnrichmentResult] for a type (for error diagnostics). */
    fun result(type: EnrichmentType): EnrichmentResult? = raw[type]

    /** Whether this type was included in the request. Distinguishes "not requested" from "not found." */
    fun wasRequested(type: EnrichmentType): Boolean = type in requestedTypes

    // --- Generic typed accessor ---

    /** Type-safe accessor for any [EnrichmentData] subclass. Returns `null` if not found or wrong type. */
    inline fun <reified T : EnrichmentData> get(type: EnrichmentType): T? =
        (raw[type] as? EnrichmentResult.Success)?.data as? T

    // --- Artwork accessors ---

    fun albumArt(): EnrichmentData.Artwork? = get(EnrichmentType.ALBUM_ART)
    fun artistPhoto(): EnrichmentData.Artwork? = get(EnrichmentType.ARTIST_PHOTO)

    // --- Text ---

    fun biography(): EnrichmentData.Biography? = get(EnrichmentType.ARTIST_BIO)

    /** Returns synced lyrics if available, falling back to plain lyrics. */
    fun lyrics(): EnrichmentData.Lyrics? =
        get(EnrichmentType.LYRICS_SYNCED) ?: get(EnrichmentType.LYRICS_PLAIN)

    // --- Relationships ---

    fun credits(): EnrichmentData.Credits? = get(EnrichmentType.CREDITS)
    fun similarArtists(): EnrichmentData.SimilarArtists? = get(EnrichmentType.SIMILAR_ARTISTS)
    fun similarAlbums(): EnrichmentData.SimilarAlbums? = get(EnrichmentType.SIMILAR_ALBUMS)
    fun discography(): EnrichmentData.Discography? = get(EnrichmentType.ARTIST_DISCOGRAPHY)

    // --- Recommendations ---

    fun topTracks(): EnrichmentData.TopTracks? = get(EnrichmentType.ARTIST_TOP_TRACKS)
    fun radio(): EnrichmentData.RadioPlaylist? = get(EnrichmentType.ARTIST_RADIO)

    // --- Statistics ---

    fun artistPopularity(): EnrichmentData.Popularity? = get(EnrichmentType.ARTIST_POPULARITY)
    fun trackPopularity(): EnrichmentData.Popularity? = get(EnrichmentType.TRACK_POPULARITY)
    fun similarTracks(): EnrichmentData.SimilarTracks? = get(EnrichmentType.SIMILAR_TRACKS)

    // --- Metadata field accessors (unwrapped, with GENRE→ALBUM_METADATA fallback) ---

    fun genres(): List<String> =
        metadata(EnrichmentType.GENRE)?.genres
            ?: metadata(EnrichmentType.ALBUM_METADATA)?.genres
            ?: emptyList()

    fun genreTags(): List<GenreTag> =
        metadata(EnrichmentType.GENRE)?.genreTags
            ?: metadata(EnrichmentType.ALBUM_METADATA)?.genreTags
            ?: emptyList()

    fun label(): String? =
        metadata(EnrichmentType.LABEL)?.label
            ?: metadata(EnrichmentType.ALBUM_METADATA)?.label

    fun releaseDate(): String? =
        metadata(EnrichmentType.RELEASE_DATE)?.releaseDate
            ?: metadata(EnrichmentType.ALBUM_METADATA)?.releaseDate

    fun releaseType(): String? =
        metadata(EnrichmentType.RELEASE_TYPE)?.releaseType
            ?: metadata(EnrichmentType.ALBUM_METADATA)?.releaseType

    fun country(): String? =
        metadata(EnrichmentType.COUNTRY)?.country
            ?: metadata(EnrichmentType.ALBUM_METADATA)?.country

    // --- Internal ---

    private fun metadata(type: EnrichmentType): EnrichmentData.Metadata? =
        (raw[type] as? EnrichmentResult.Success)?.data as? EnrichmentData.Metadata
}
