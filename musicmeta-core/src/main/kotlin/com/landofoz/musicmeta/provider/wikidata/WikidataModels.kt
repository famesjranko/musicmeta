package com.landofoz.musicmeta.provider.wikidata

internal data class WikidataEntityProperties(
    val imageUrl: String?,
    val birthDate: String?,
    val deathDate: String?,
    val countryOfOrigin: String?,
    val occupation: String?,
    /** P434. */
    val musicBrainzArtistId: String? = null,
    /** P1953. */
    val discogsArtistId: String? = null,
    /** P1902 — absent on most long-tail acts. */
    val spotifyArtistId: String? = null,
    /** P2850 — absent on most long-tail acts. */
    val iTunesArtistId: String? = null,
    /** P856, the artist's official website. */
    val officialWebsite: String? = null,
)
