package com.landofoz.musicmeta

/** How an [ApiKey] gates a catalog entry's registration under [EnrichmentEngine.Builder.withDefaultProviders]. */
public sealed class KeyRequirement {
    /** The provider registers with no key. */
    public object None : KeyRequirement()

    /** The provider registers only when [ApiKeyConfig.get] of [key] is non-null; otherwise it is absent. */
    public data class Required(val key: ApiKey) : KeyRequirement()

    /** The provider registers regardless; a missing [key] only disables part of its surface. */
    public data class Optional(val key: ApiKey) : KeyRequirement()
}

/**
 * One provider [EnrichmentEngine.Builder.withDefaultProviders] *would* register, and which
 * [ApiKey], if any, gates that registration.
 *
 * @property id matches the id [EnrichmentEngine.getProviders] reports for the same provider,
 *   and a key of [ProviderPolicies.all].
 * @property displayName matches [EnrichmentProvider.displayName] for the same provider.
 */
public data class ProviderCatalogEntry(
    val id: String,
    val displayName: String,
    val keyRequirement: KeyRequirement,
)

/**
 * The providers [EnrichmentEngine.Builder.withDefaultProviders] would register, independent of
 * any [ApiKeyConfig].
 *
 * This is a static description, not a live one: it does not reflect whether a given engine
 * instance actually registered, enabled or could reach each provider. [EnrichmentEngine.getProviders]
 * on a built engine stays the authority for what *is* registered.
 */
public object ProviderCatalog {
    public val entries: List<ProviderCatalogEntry> = listOf(
        ProviderCatalogEntry("musicbrainz", "MusicBrainz", KeyRequirement.None),
        ProviderCatalogEntry("coverartarchive", "Cover Art Archive", KeyRequirement.None),
        ProviderCatalogEntry("wikidata", "Wikidata", KeyRequirement.None),
        ProviderCatalogEntry("wikipedia", "Wikipedia", KeyRequirement.None),
        ProviderCatalogEntry("deezer", "Deezer", KeyRequirement.None),
        ProviderCatalogEntry("deezer-similar-albums", "Deezer Similar Albums", KeyRequirement.None),
        ProviderCatalogEntry("itunes", "iTunes", KeyRequirement.None),
        ProviderCatalogEntry(
            "listenbrainz", "ListenBrainz",
            KeyRequirement.Optional(ApiKey.LISTENBRAINZ_USER_TOKEN),
        ),
        ProviderCatalogEntry("lrclib", "LRCLIB", KeyRequirement.None),
        ProviderCatalogEntry("lastfm", "Last.fm", KeyRequirement.Required(ApiKey.LASTFM_API_KEY)),
        ProviderCatalogEntry("fanarttv", "Fanart.tv", KeyRequirement.Required(ApiKey.FANARTTV_PROJECT_KEY)),
        ProviderCatalogEntry("discogs", "Discogs", KeyRequirement.Required(ApiKey.DISCOGS_PERSONAL_TOKEN)),
    )
}
