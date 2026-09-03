package com.landofoz.musicmeta

/**
 * How an [ApiKeyConfig] field relates to a catalog entry's registration under
 * [EnrichmentEngine.Builder.withDefaultProviders].
 */
public sealed class KeyRequirement {
    /** The provider registers with no key. */
    public object None : KeyRequirement()

    /** The provider registers only when [key] returns non-null; otherwise it is absent. */
    public class Required(public val fieldName: String, public val key: (ApiKeyConfig) -> String?) : KeyRequirement()

    /** The provider registers regardless; a null [key] only disables part of its surface. */
    public class Optional(public val fieldName: String, public val key: (ApiKeyConfig) -> String?) : KeyRequirement()
}

/**
 * One provider [EnrichmentEngine.Builder.withDefaultProviders] *would* register, and what
 * [ApiKeyConfig] field, if any, gates that registration.
 *
 * @property id matches the id [EnrichmentEngine.getProviders] reports for the same provider,
 *   and a key of [ProviderPolicies.all].
 * @property displayName matches [EnrichmentProvider.displayName] for the same provider.
 */
public class ProviderCatalogEntry(
    public val id: String,
    public val displayName: String,
    public val keyRequirement: KeyRequirement,
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
            KeyRequirement.Optional("listenBrainzToken") { it.listenBrainzToken },
        ),
        ProviderCatalogEntry("lrclib", "LRCLIB", KeyRequirement.None),
        ProviderCatalogEntry("lastfm", "Last.fm", KeyRequirement.Required("lastFmKey") { it.lastFmKey }),
        ProviderCatalogEntry(
            "fanarttv", "Fanart.tv",
            KeyRequirement.Required("fanartTvProjectKey") { it.fanartTvProjectKey },
        ),
        ProviderCatalogEntry(
            "discogs", "Discogs",
            KeyRequirement.Required("discogsPersonalToken") { it.discogsPersonalToken },
        ),
    )
}
