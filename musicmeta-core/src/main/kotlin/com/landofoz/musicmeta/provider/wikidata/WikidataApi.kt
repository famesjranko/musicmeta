package com.landofoz.musicmeta.provider.wikidata

import com.landofoz.musicmeta.drift.SchemaTarget
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.http.bodyOrThrowTransient
import com.landofoz.musicmeta.provider.encodePathSegment
import com.landofoz.musicmeta.provider.encodeQueryValue
import org.json.JSONObject

/**
 * Fetches artist properties from Wikidata: P18 (image), P569 (birth date),
 * P570 (death date), P495 (country of origin), P106 (occupation), P856 (official website),
 * and the external-id claims P434 (MusicBrainz), P1953 (Discogs), P1902 (Spotify),
 * P2850 (Apple Music). Constructs Wikimedia Commons thumbnail URLs from image filenames.
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
     * Response shape: claims live under `entities.<id>.claims`, and an entity Wikidata holds no
     * statements for still carries a `claims` object — an empty one. So a *present* entity with no
     * `claims` key is the payload having changed shape, not an artist with nothing recorded, which
     * is the pair [WikidataProperties.UnreadableShape] and [WikidataProperties.NoClaims].
     *
     * An id Wikidata does not hold comes back as `entities.<id>` carrying a `missing` marker, which
     * is [WikidataProperties.NoEntity]. An id Wikidata has **merged away** is not that: it still
     * answers under the id that was asked for, carrying the target's claims and naming the target
     * only in the inner `id` field beside a `redirects` object. `redirects=yes` is the parameter
     * default, so reading `entities.<id>` resolves a merged id in one request.
     *
     * A top-level `error` key is [WikidataProperties.UnreadableShape] too. It is how this route
     * rejects a *request* — `param-invalid` is the shape that regressed the old `wbgetclaims` call
     * — and it arrives at HTTP 200, so nothing else in the stack can see it.
     *
     * No body at all is [WikidataProperties.UnreadableShape] for the same reason:
     * [bodyOrThrowTransient] hands back `null` for a 4xx, and this route answers an id it does not
     * hold at 200 (§31). A 4xx here is a statement about the *request* — a parameter this route
     * stopped accepting — so reading any of these as "this entity has no properties" would blank
     * `ARTIST_PHOTO`, `COUNTRY` and `ARTIST_LINKS` while reporting the provider healthy.
     */
    suspend fun getEntityProperties(
        wikidataId: String,
        imageSize: Int = DEFAULT_IMAGE_SIZE,
    ): WikidataProperties = rateLimiter.execute {
        val json = httpClient.fetchJsonResult(entityPropertiesUrl(wikidataId)).bodyOrThrowTransient()
            ?: return@execute WikidataProperties.UnreadableShape
        if (json.has("error")) return@execute WikidataProperties.UnreadableShape
        val entity = json.optJSONObject("entities")?.optJSONObject(wikidataId)
        if (entity == null || entity.has("missing")) return@execute WikidataProperties.NoEntity
        val claims = entity.optJSONObject("claims") ?: return@execute WikidataProperties.UnreadableShape
        val properties = parseEntityProperties(claims, imageSize)
        if (properties == NO_PROPERTIES) WikidataProperties.NoClaims else WikidataProperties.Claims(properties)
    }

    /**
     * Fetch one entity's English Wikipedia article title from its sitelinks.
     *
     * `sitefilter=enwiki` is what keeps the answer English-only: no other language can come back,
     * so a caller cannot resolve a title whose article it has no use for.
     *
     * Response shape: an entity with an English article carries
     * `entities.<id>.sitelinks.enwiki.title`; one without carries `sitelinks` as `{}`; an id
     * Wikidata does not hold carries `entities.<id>` with a `missing` marker and no `sitelinks` at
     * all. A `sitelinks` object absent from a present entity is therefore this route moving rather
     * than an artist without a page, which is the pair [EnwikiSitelink.UnreadableShape] and
     * [EnwikiSitelink.NoArticle] keep apart.
     *
     * An id Wikidata has since merged away still answers under the id that was **asked for**,
     * carrying the target's `sitelinks` and naming the target only in the inner `id` field beside a
     * `redirects` object. `redirects=yes` is the parameter default, and reading `entities.<id>`
     * therefore resolves a merged id without a second request. Keying off `redirects.to`, or off
     * the sole entity, would be wrong on both counts: it is not where the answer lives, and one
     * request may name up to fifty ids.
     *
     * No body at all is [EnwikiSitelink.UnreadableShape], not [EnwikiSitelink.NoEntity]:
     * [bodyOrThrowTransient] hands back `null` for a 4xx, and this route answers an id it does not
     * hold at 200 (§31). A 4xx here is a statement about the *request* — a parameter this route
     * stopped accepting — so classifying it as "Wikidata has no such entity" would report a route
     * that stopped serving us as an artist without a page.
     */
    suspend fun getEnwikiSitelink(wikidataId: String): EnwikiSitelink = rateLimiter.execute {
        val json = httpClient.fetchJsonResult(enwikiSitelinkUrl(wikidataId)).bodyOrThrowTransient()
            ?: return@execute EnwikiSitelink.UnreadableShape
        val entity = json.optJSONObject("entities")?.optJSONObject(wikidataId)
        if (entity == null || entity.has("missing")) return@execute EnwikiSitelink.NoEntity
        val sitelinks = entity.optJSONObject("sitelinks") ?: return@execute EnwikiSitelink.UnreadableShape
        val title = sitelinks.optJSONObject("enwiki")?.optString("title")?.takeIf { it.isNotBlank() }
        if (title == null) EnwikiSitelink.NoArticle else EnwikiSitelink.Title(title)
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
            countryOfOrigin = countryQid?.let { COUNTRY_MAP[it] },
            occupation = occupationQid?.let { OCCUPATION_MAP[it] ?: it },
            musicBrainzArtistId = extractStringValue(claims, "P434"),
            discogsArtistId = extractStringValue(claims, "P1953"),
            spotifyArtistId = extractStringValue(claims, "P1902"),
            iTunesArtistId = extractStringValue(claims, "P2850"),
            officialWebsite = extractStringValue(claims, "P856"),
        )
    }

    /**
     * Extract a string value from the preferred (or first) claim.
     *
     * `optString` renders a non-string value as JSON text rather than returning nothing, so a
     * property whose datavalue is an object would parse as `{"id":"Q5"}`. Only a genuine string
     * is accepted here.
     */
    private fun extractStringValue(claims: JSONObject, property: String): String? {
        val claim = selectClaim(claims, property) ?: return null
        return (
            claim
                .optJSONObject("mainsnak")
                ?.optJSONObject("datavalue")
                ?.opt("value") as? String
            )?.takeIf { it.isNotBlank() }
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

    /**
     * Select the preferred-rank claim, or fall back to the first — skipping deprecated ones, which
     * are statements Wikidata records as retracted (a superseded identifier, a disproven date).
     *
     * Single-valued properties only. A multi-valued property (P136 genre, P527 members) would be
     * silently reduced to one of its values here; read the whole array instead.
     */
    private fun selectClaim(claims: JSONObject, property: String): JSONObject? {
        val array = claims.optJSONArray(property) ?: return null
        val usable = (0 until array.length())
            .map { array.getJSONObject(it) }
            .filterNot { it.optString("rank") == "deprecated" }
        return usable.firstOrNull { it.optString("rank") == "preferred" } ?: usable.firstOrNull()
    }

    private fun buildCommonsUrl(filename: String, size: Int): String {
        val encoded = encodePathSegment(filename.replace(' ', '_'))
        val ext = filename.substringAfterLast('.', "").lowercase()
        val suffix = if (ext in NON_RASTER_FORMATS) ".png" else ""
        return "$COMMONS_BASE_URL/$encoded$suffix?width=$size"
    }

    companion object {
        /**
         * What a `claims` object holding nothing this mapper reads parses to. Compared by value
         * rather than field by field, so a property added to [WikidataEntityProperties] is counted
         * without anyone remembering to count it.
         */
        private val NO_PROPERTIES = WikidataEntityProperties(null, null, null, null, null)

        const val BASE_URL = "https://www.wikidata.org/w/api.php"
        const val COMMONS_BASE_URL = "https://commons.wikimedia.org/wiki/Special:FilePath"

        /** The URL [getEntityProperties] requests. */
        fun entityPropertiesUrl(wikidataId: String): String =
            "$BASE_URL?action=wbgetentities&ids=${encodeQueryValue(wikidataId)}&props=claims&format=json"

        /** The URL [getEnwikiSitelink] requests. */
        fun enwikiSitelinkUrl(wikidataId: String): String =
            "$BASE_URL?action=wbgetentities&ids=${encodeQueryValue(wikidataId)}" +
                "&props=sitelinks&sitefilter=enwiki&format=json"

        /**
         * Schema-pin targets, mirroring [parseEntityProperties] and [getEnwikiSitelink].
         *
         * The entity-properties paths are property ids under `claims`, not the `labels` a reader of
         * the entity page would expect: `props=claims` returns no `labels` key at all, and this
         * mapper reads none. Q44190 is Radiohead, which carries all three of the pinned external-id
         * claims and an English article.
         *
         * The sitelink route pins `sitelinks` as well as the title inside it, because those are the
         * two levels [getEnwikiSitelink] tells apart: an empty `sitelinks` is an artist with no
         * English article and is not drift, so only its *absence* may be reported, and the pin says
         * which of the two moved rather than leaving a report that reads as either.
         *
         * The same route is pinned a second time at a **merged** id, Q53265341, which is where the
         * paths say something the Q44190 pin cannot: that Wikidata still keys a merged entity's
         * answer under the id that was asked for. Nothing else watches that. Every path here is
         * required, so it takes an id that is a redirect today — `redirects.to` is absent from
         * every answer that is not one, and pinning it at Q44190 would report drift daily. If
         * Wikidata ever splits this id again, the pin says so once and takes a new one.
         */
        val SCHEMA_PIN_TARGETS: List<SchemaTarget> = listOf(
            SchemaTarget(
                provider = "wikidata",
                route = "entity properties",
                url = entityPropertiesUrl("Q44190"),
                requiredPaths = listOf(
                    "entities.Q44190.claims.P434[0].mainsnak.datavalue.value",
                    "entities.Q44190.claims.P18[0].mainsnak.datavalue.value",
                    "entities.Q44190.claims.P1953[0].mainsnak.datavalue.value",
                ),
            ),
            SchemaTarget(
                provider = "wikidata",
                route = "enwiki sitelink",
                url = enwikiSitelinkUrl("Q44190"),
                requiredPaths = listOf(
                    "entities.Q44190.sitelinks",
                    "entities.Q44190.sitelinks.enwiki.title",
                ),
            ),
            SchemaTarget(
                provider = "wikidata",
                route = "enwiki sitelink of a merged id",
                url = enwikiSitelinkUrl("Q53265341"),
                requiredPaths = listOf(
                    "entities.Q53265341.redirects.to",
                    "entities.Q53265341.sitelinks.enwiki.title",
                ),
            ),
        )
        const val DEFAULT_IMAGE_SIZE = 1200
        val NON_RASTER_FORMATS = setOf("svg", "tif", "tiff")

        /**
         * P495 country Q-id to the ISO 3166-1 alpha-2 code `Metadata.country` promises. A Q-id
         * absent here has no code to report, so it maps to null rather than to itself — a Q-id is
         * an identifier, never a country.
         */
        val COUNTRY_MAP = mapOf(
            "Q30" to "US", "Q145" to "GB", "Q142" to "FR", "Q183" to "DE",
            "Q17" to "JP", "Q38" to "IT", "Q29" to "ES", "Q16" to "CA",
            "Q408" to "AU", "Q36" to "PL", "Q159" to "RU",
            "Q213" to "CZ", "Q211" to "LV",
            "Q31" to "BE", "Q55" to "NL", "Q212" to "UA",
        )

        val OCCUPATION_MAP = mapOf(
            "Q177220" to "singer", "Q639669" to "musician", "Q36834" to "composer",
            "Q488205" to "singer-songwriter", "Q183945" to "record producer",
        )
    }
}
