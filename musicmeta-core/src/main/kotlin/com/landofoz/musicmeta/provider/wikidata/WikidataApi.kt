package com.landofoz.musicmeta.provider.wikidata

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.bodyOrThrowTransient
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Fetches artist properties from Wikidata: P18 (image), P569 (birth date),
 * P570 (death date), P495 (country of origin), P106 (occupation).
 * Constructs Wikimedia Commons thumbnail URLs from image filenames.
 */
internal class WikidataApi(
    private val httpClient: HttpClient,
    private val rateLimiter: RateLimiter,
) {

    /**
     * Fetch expanded entity properties in a single API call.
     * Returns birth/death dates, country of origin, occupation, and image URL.
     *
     * Uses `wbgetentities` with `props=claims`, not `wbgetclaims`: `wbgetclaims`'s `property`
     * parameter is single-valued, so a pipe-delimited "P18|P569|..." list is rejected with
     * `param-invalid` — at HTTP 200, so [bodyOrThrowTransient] never sees it. `wbgetentities`
     * returns the full claims set for the entity in one call, no property-list encoding needed.
     *
     * Response shape: claims live under `entities.<id>.claims`. A missing or invalid id comes
     * back as either `entities.<id>` with no `claims` key (bare "missing" marker) or a top-level
     * `error` key with no `entities` key at all — both fall through the `optJSONObject` chain
     * below to `null`, same as an empty claims object.
     */
    suspend fun getEntityProperties(
        wikidataId: String,
        imageSize: Int = DEFAULT_IMAGE_SIZE,
    ): WikidataEntityProperties? = rateLimiter.execute {
        val url = "$BASE_URL?action=wbgetentities&ids=$wikidataId&props=claims&format=json"
        val json = httpClient.fetchJsonResult(url).bodyOrThrowTransient() ?: return@execute null
        val claims = json.optJSONObject("entities")
            ?.optJSONObject(wikidataId)
            ?.optJSONObject("claims")
            ?: return@execute null
        parseEntityProperties(claims, imageSize)
    }

    private fun parseEntityProperties(
        claims: JSONObject,
        imageSize: Int,
    ): WikidataEntityProperties {
        val imageFilename = extractStringValue(claims, "P18")
        val imageUrl = imageFilename?.let { buildCommonsUrl(it, imageSize) }
        val birthDate = extractTimeValue(claims, "P569")
        val deathDate = extractTimeValue(claims, "P570")
        val countryQid = extractEntityId(claims, "P495")
        val occupationQid = extractEntityId(claims, "P106")
        return WikidataEntityProperties(
            imageUrl = imageUrl,
            birthDate = birthDate,
            deathDate = deathDate,
            countryOfOrigin = countryQid?.let { COUNTRY_MAP[it] ?: it },
            occupation = occupationQid?.let { OCCUPATION_MAP[it] ?: it },
        )
    }

    /** Extract a string value from the preferred (or first) claim. */
    private fun extractStringValue(claims: JSONObject, property: String): String? {
        val claim = selectClaim(claims, property) ?: return null
        return claim
            .optJSONObject("mainsnak")
            ?.optJSONObject("datavalue")
            ?.optString("value")
            ?.takeIf { it.isNotBlank() }
    }

    /** Extract a time value (e.g. +1968-10-07T00:00:00Z) and return date part. */
    private fun extractTimeValue(claims: JSONObject, property: String): String? {
        val claim = selectClaim(claims, property) ?: return null
        val time = claim
            .optJSONObject("mainsnak")
            ?.optJSONObject("datavalue")
            ?.optJSONObject("value")
            ?.optString("time")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        // Strip leading + and take date before T
        return time.removePrefix("+").substringBefore("T").takeIf { it.isNotBlank() }
    }

    /** Extract an entity ID (e.g. Q30) from a wikibase-entityid claim. */
    private fun extractEntityId(claims: JSONObject, property: String): String? {
        val claim = selectClaim(claims, property) ?: return null
        return claim
            .optJSONObject("mainsnak")
            ?.optJSONObject("datavalue")
            ?.optJSONObject("value")
            ?.optString("id")
            ?.takeIf { it.isNotBlank() }
    }

    /** Select the preferred-rank claim, or fall back to the first. */
    private fun selectClaim(claims: JSONObject, property: String): JSONObject? {
        val array = claims.optJSONArray(property) ?: return null
        if (array.length() == 0) return null
        val preferred = (0 until array.length())
            .map { array.getJSONObject(it) }
            .firstOrNull { it.optString("rank") == "preferred" }
        return preferred ?: array.getJSONObject(0)
    }

    private fun buildCommonsUrl(filename: String, size: Int): String {
        val encoded = URLEncoder.encode(filename.replace(' ', '_'), "UTF-8")
        val ext = filename.substringAfterLast('.', "").lowercase()
        val suffix = if (ext in NON_RASTER_FORMATS) ".png" else ""
        return "$COMMONS_BASE_URL/$encoded$suffix?width=$size"
    }

    private companion object {
        const val BASE_URL = "https://www.wikidata.org/w/api.php"
        const val COMMONS_BASE_URL = "https://commons.wikimedia.org/wiki/Special:FilePath"
        const val DEFAULT_IMAGE_SIZE = 1200
        val NON_RASTER_FORMATS = setOf("svg", "tif", "tiff")

        val COUNTRY_MAP = mapOf(
            "Q30" to "US", "Q145" to "UK", "Q142" to "France", "Q183" to "Germany",
            "Q17" to "Japan", "Q38" to "Italy", "Q29" to "Spain", "Q16" to "Canada",
            "Q408" to "Australia", "Q36" to "Poland", "Q159" to "Russia",
            "Q211" to "Czech Republic", "Q31" to "Belgium", "Q55" to "Netherlands",
        )

        val OCCUPATION_MAP = mapOf(
            "Q177220" to "singer", "Q639669" to "musician", "Q36834" to "composer",
            "Q488205" to "singer-songwriter", "Q183945" to "record producer",
        )
    }
}
