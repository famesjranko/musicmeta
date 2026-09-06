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
     * No usable entity in the answer — the id is marked `missing`, the response is keyed under a
     * different id because the requested one is a redirect, or the request was shed with a 4xx.
     */
    data object NoEntity : EnwikiSitelink

    /** The entity is present and carries no `sitelinks` object, which this route always returns. */
    data object UnreadableShape : EnwikiSitelink
}
