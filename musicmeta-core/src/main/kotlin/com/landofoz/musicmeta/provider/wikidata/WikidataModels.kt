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

/**
 * What `wbgetentities&props=claims` said about one entity's properties.
 *
 * The four cases are what the answer can distinguish, not a classification imposed on it: an entity
 * Wikidata holds nothing for still carries a `claims` object, so a *missing* one is the response
 * having changed shape rather than an artist with nothing recorded. Every case but [Claims] leaves
 * the caller without properties, so none of them changes an enrichment answer — the distinction
 * exists so a route that stopped serving us is reported as that rather than as three capabilities
 * the artist has nothing for.
 */
internal sealed interface WikidataProperties {

    /** The entity's claims, parsed, holding at least one property this mapper reads. */
    data class Claims(val value: WikidataEntityProperties) : WikidataProperties

    /**
     * The entity is present and its `claims` object holds none of the properties read here: the
     * artist has nothing recorded that this provider can answer with.
     */
    data object NoClaims : WikidataProperties

    /**
     * Wikidata answered, and holds no entity under the requested id — it is marked `missing`, or
     * the answer is keyed under a different id because the requested one is now a redirect.
     */
    data object NoEntity : WikidataProperties

    /**
     * The answer is not one this route can be read from: a top-level `error` key, an entity present
     * but carrying no `claims` object, or no body at all. All three are the route having moved or
     * having rejected the request, never a fact about the artist, so this is the one case the
     * caller logs.
     */
    data object UnreadableShape : WikidataProperties
}

/**
 * What `wbgetentities&props=sitelinks&sitefilter=enwiki` said about one entity's English article.
 *
 * The four cases are what the answer can distinguish, not a classification imposed on it: an entity
 * with no English article carries an empty `sitelinks` object, so a *missing* one is the response
 * having changed shape rather than the artist having no page. Every case but [Title] leaves the
 * caller with no title, so none of them changes an enrichment answer — the distinction exists so a
 * drift is reported as a drift instead of reading as an artist Wikipedia has never heard of.
 */
internal sealed interface EnwikiSitelink {

    /** The entity's English Wikipedia article title. */
    data class Title(val value: String) : EnwikiSitelink

    /** `sitelinks` came back empty: the entity exists and has no English article. */
    data object NoArticle : EnwikiSitelink

    /**
     * Wikidata answered, and holds no entity under the requested id — it is marked `missing`, or
     * the answer is keyed under a different id because the requested one is now a redirect.
     */
    data object NoEntity : EnwikiSitelink

    /**
     * The answer is not one this route can be read from: an entity present but carrying no
     * `sitelinks` object, or no body at all. Both are the route having moved, never a fact about
     * the artist, so this is the one case the caller logs.
     */
    data object UnreadableShape : EnwikiSitelink
}
