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
public data class EnrichmentResults(
    /** Raw per-type results. */
    val raw: Map<EnrichmentType, EnrichmentResult>,
    /** Types that were requested in the enrich() call. */
    val requestedTypes: Set<EnrichmentType>,
    /** Identity resolution outcome. [IdentityResolution.status] carries why, if it was not attempted. */
    val identity: IdentityResolution,
) {

    // --- Result-level access ---

    /** Get the raw [EnrichmentResult] for a type (for error diagnostics). */
    public fun result(type: EnrichmentType): EnrichmentResult? = raw[type]

    /** Whether this type was included in the request. Distinguishes "not requested" from "not found." */
    public fun wasRequested(type: EnrichmentType): Boolean = type in requestedTypes

    // --- Generic typed accessor ---

    /** Type-safe accessor for any [EnrichmentData] subclass. Returns `null` if not found or wrong type. */
    public inline fun <reified T : EnrichmentData> get(type: EnrichmentType): T? =
        (raw[type] as? EnrichmentResult.Success)?.data as? T

    // --- Artwork accessors ---

    public fun albumArt(): EnrichmentData.Artwork? = get(EnrichmentType.ALBUM_ART)
    public fun artistPhoto(): EnrichmentData.Artwork? = get(EnrichmentType.ARTIST_PHOTO)

    // --- Text ---

    public fun biography(): EnrichmentData.Biography? = get(EnrichmentType.ARTIST_BIO)
    public fun albumDescription(): EnrichmentData.Biography? = get(EnrichmentType.ALBUM_DESCRIPTION)

    /** Returns synced lyrics if available, falling back to plain lyrics. */
    public fun lyrics(): EnrichmentData.Lyrics? =
        get(EnrichmentType.LYRICS_SYNCED) ?: get(EnrichmentType.LYRICS_PLAIN)

    // --- Relationships ---

    public fun credits(): EnrichmentData.Credits? = get(EnrichmentType.CREDITS)
    public fun similarArtists(): EnrichmentData.SimilarArtists? = get(EnrichmentType.SIMILAR_ARTISTS)
    public fun similarAlbums(): EnrichmentData.SimilarAlbums? = get(EnrichmentType.SIMILAR_ALBUMS)
    public fun discography(): EnrichmentData.Discography? = get(EnrichmentType.ARTIST_DISCOGRAPHY)

    // --- Recommendations ---

    public fun topTracks(): EnrichmentData.TopTracks? = get(EnrichmentType.ARTIST_TOP_TRACKS)
    public fun radio(): EnrichmentData.RadioPlaylist? = get(EnrichmentType.ARTIST_RADIO)
    public fun radioDiscovery(): EnrichmentData.RadioPlaylist? = get(EnrichmentType.ARTIST_RADIO_DISCOVERY)
    public fun trackPreview(): EnrichmentData.TrackPreview? = get(EnrichmentType.TRACK_PREVIEW)

    // --- Statistics ---

    public fun artistPopularity(): EnrichmentData.Popularity? = get(EnrichmentType.ARTIST_POPULARITY)
    public fun trackPopularity(): EnrichmentData.Popularity? = get(EnrichmentType.TRACK_POPULARITY)
    public fun similarTracks(): EnrichmentData.SimilarTracks? = get(EnrichmentType.SIMILAR_TRACKS)
    public fun trackMetadata(): EnrichmentData.TrackMetadata? = get(EnrichmentType.TRACK_METADATA)

    // --- Metadata field accessors (unwrapped, with GENRE→ALBUM_METADATA fallback) ---

    public fun genres(): List<String> =
        metadata(EnrichmentType.GENRE)?.genres
            ?: metadata(EnrichmentType.ALBUM_METADATA)?.genres.orEmpty()

    public fun genreTags(): List<GenreTag> =
        metadata(EnrichmentType.GENRE)?.genreTags
            ?: metadata(EnrichmentType.ALBUM_METADATA)?.genreTags.orEmpty()

    public fun label(): String? =
        metadata(EnrichmentType.LABEL)?.label
            ?: metadata(EnrichmentType.ALBUM_METADATA)?.label

    public fun releaseDate(): String? =
        metadata(EnrichmentType.RELEASE_DATE)?.releaseDate
            ?: metadata(EnrichmentType.ALBUM_METADATA)?.releaseDate

    public fun releaseType(): String? =
        metadata(EnrichmentType.RELEASE_TYPE)?.releaseType
            ?: metadata(EnrichmentType.ALBUM_METADATA)?.releaseType

    /**
     * ISO 3166-1 alpha-2 where the upstream supplies a country (`GB`). Where it names no current
     * ISO country the upstream's own wording passes through — Discogs' region labels (`Europe`)
     * and historical states (`Yugoslavia`), MusicBrainz's `XE`/`XW`; null when no country-level
     * area exists.
     */
    public fun country(): String? =
        metadata(EnrichmentType.COUNTRY)?.country
            ?: metadata(EnrichmentType.ALBUM_METADATA)?.country

    // --- Internal ---

    private fun metadata(type: EnrichmentType): EnrichmentData.Metadata? =
        (raw[type] as? EnrichmentResult.Success)?.data as? EnrichmentData.Metadata
}
