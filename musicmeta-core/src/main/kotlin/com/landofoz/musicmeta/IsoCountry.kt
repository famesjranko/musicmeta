package com.landofoz.musicmeta

import java.util.Locale

/**
 * Normalises an upstream's country wording to the ISO 3166-1 alpha-2 code
 * [EnrichmentData.Metadata.country] reports.
 *
 * Providers disagree about the shape: MusicBrainz sends alpha-2, iTunes alpha-3, Discogs an
 * English country name. The codes, their alpha-3 equivalents and the English names all come from
 * [Locale], so no country table is maintained here; [ALIASES] covers only wording the JDK does not
 * recognise.
 */
internal object IsoCountry {

    /** The alpha-2 code for [raw], or null when it names no single country. */
    fun alpha2OrNull(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val upper = value.uppercase(Locale.ROOT)
        return ALIASES[upper]
            ?: upper.takeIf { it in ALPHA2_CODES }
            ?: ALPHA3_TO_ALPHA2[upper]
            ?: NAME_TO_ALPHA2[upper]
    }

    /**
     * As [alpha2OrNull], but returns [raw] untouched when it names no single country. For a field
     * where the upstream's own wording — Discogs' "Europe", MusicBrainz's "XW" — is worth more to
     * a consumer than nothing.
     */
    fun alpha2OrKeep(raw: String?): String? = alpha2OrNull(raw) ?: raw

    /**
     * Wording no [Locale] lookup resolves. "UK" is not an ISO code (the code is "GB") but is what
     * Discogs writes, and the JDK's display name for CZ moved to "Czechia".
     */
    private val ALIASES = mapOf(
        "UK" to "GB",
        "CZECH REPUBLIC" to "CZ",
    )

    private val ALPHA2_CODES: Set<String> = Locale.getISOCountries().toSet()

    private val ALPHA3_TO_ALPHA2: Map<String, String> =
        ALPHA2_CODES.mapNotNull { code ->
            localeFor(code).isO3Country
                .takeIf { it.isNotBlank() }
                ?.let { it.uppercase(Locale.ROOT) to code }
        }.toMap()

    private val NAME_TO_ALPHA2: Map<String, String> =
        ALPHA2_CODES.associateBy { localeFor(it).getDisplayCountry(Locale.ENGLISH).uppercase(Locale.ROOT) }

    private fun localeFor(code: String): Locale = Locale.Builder().setRegion(code).build()
}
