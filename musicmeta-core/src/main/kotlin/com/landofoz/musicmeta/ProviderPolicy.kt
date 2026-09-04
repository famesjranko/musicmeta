package com.landofoz.musicmeta

/**
 * What a provider's public terms said about commercial use, on the date they were read.
 *
 * [UNVERIFIED] is a state, not a default: the terms could not be read at all, and no position may
 * be inferred from it.
 */
public enum class CommercialUse {
    /** The terms permit commercial use. */
    ALLOWED,

    /** Permitted only under stated conditions — a paid tier, a written agreement, badging rules. */
    CONDITIONAL,

    /** The terms forbid commercial use. */
    PROHIBITED,

    /** The terms are readable and say nothing about commercial use. */
    NOT_STATED,

    /** The terms could not be read on the policy's date. Infer nothing. */
    UNVERIFIED,
}

/**
 * Whether a provider's terms oblige a consumer to display something alongside its data.
 *
 * [UNVERIFIED] is a state, not a default: the terms could not be read at all.
 */
public enum class AttributionRequirement {
    /** The terms require a notice. */
    REQUIRED,

    /**
     * A notice is owed for some of the provider's data or uses and not others — whether the terms
     * enumerate the split (Apple: previews, not metadata) or leave it unresolved (MusicBrainz: core
     * versus supplementary). The entry's [ProviderPolicy.attributionNote] says which.
     */
    DEPENDS_ON_DATA,

    /** The terms are readable and require nothing. */
    NOT_REQUIRED,

    /** The terms are readable and do not address attribution. */
    NOT_SPECIFIED,

    /** The terms could not be read on the policy's date. Infer nothing. */
    UNVERIFIED,
}

/**
 * A dated snapshot of one provider's public terms, shipped as data so a consumer can render the
 * notices it owes and branch on commercial use without re-reading eleven terms pages.
 *
 * **Not legal advice, and not a compliance promise.** Each entry records what a public terms page
 * said on [asReadOn], with the URLs it was read from. Terms change and nothing here tracks them:
 * verify against [sourceUrls] before relying on any field. A consumer enabling a provider is bound
 * by that provider's terms, not by this type.
 *
 * Merged results attribute per item, not per result: `EnrichmentResult.provider` for a merged type
 * names a merger (`genre_merger`), never an upstream. Join each item's `sources` list — on
 * `GenreTag`, `SimilarArtist`, `SimilarTrack`, `TopTrack` — to [ProviderPolicies] by provider id to
 * find which notices are owed.
 *
 * @param providerId Matches `EnrichmentProvider.id`, and the ids in an item's `sources` list.
 * @param commercialUse The terms' position on commercial use.
 * @param commercialUseNote The terms' own words, or what could not be read. Never empty.
 * @param dataLicence How the terms licence the data, in their words. `null` means the licence could
 *   not be verified on [asReadOn] — never that the data is unlicensed.
 * @param attribution Whether a notice is owed.
 * @param attributionNote The obligation in the terms' own words, or what could not be read.
 *   Never empty.
 * @param attributionNotice Text that satisfies the obligation, ready to render — verbatim where the
 *   terms give the words (Discogs, iTunes), composed from them where the terms state a requirement
 *   without wording it (Last.fm, Wikipedia). `null` when no notice is owed or none could be read.
 * @param sourceUrls The pages read, in the order they were read.
 * @param asReadOn ISO-8601 date the pages were read.
 */
public data class ProviderPolicy(
    val providerId: String,
    val commercialUse: CommercialUse,
    val commercialUseNote: String,
    val dataLicence: String?,
    val attribution: AttributionRequirement,
    val attributionNote: String,
    val attributionNotice: String?,
    val sourceUrls: List<String>,
    val asReadOn: String,
)

/**
 * Terms snapshots for the providers musicmeta ships, keyed by `EnrichmentProvider.id`.
 *
 * A provider a consumer wrote is absent — musicmeta cannot know its terms. Read the caveats on
 * [ProviderPolicy] before rendering anything from here.
 */
public object ProviderPolicies {

    /** The date every entry's sources were read. */
    private const val AS_READ_ON: String = "2026-08-12"

    private val musicBrainz = ProviderPolicy(
        providerId = "musicbrainz",
        commercialUse = CommercialUse.CONDITIONAL,
        commercialUseNote = "\"Non-commercial use of this web service is free; please see our commercial plans " +
            "or contact us if you would like to use this service commercially.\" MetaBrainz counts a company " +
            "with a current or expected revenue stream, including a pre-revenue start-up, as commercial.",
        dataLicence = "\"The core data of the database is licensed under the CC0\"; \"The remaining portions... " +
            "are released under the Creative Commons Attribution-NonCommercial-ShareAlike 3.0 license\". The " +
            "page does not enumerate core versus supplementary fields.",
        attribution = AttributionRequirement.DEPENDS_ON_DATA,
        attributionNote = "None for the CC0 core data; credit and ShareAlike for the CC BY-NC-SA 3.0 " +
            "supplementary portions. Which side tags and genres fall on is unresolved.",
        attributionNotice = null,
        sourceUrls = listOf(
            "https://musicbrainz.org/doc/About/Data_License",
            "https://musicbrainz.org/doc/MusicBrainz_API",
            "https://metabrainz.org/supporters/account-type",
        ),
        asReadOn = AS_READ_ON,
    )

    private val coverArtArchive = ProviderPolicy(
        providerId = "coverartarchive",
        commercialUse = CommercialUse.NOT_STATED,
        commercialUseNote = "The pages read state no position on commercial use.",
        dataLicence = "\"All images are copyrighted by their respective copyright owners.\" The API metadata " +
            "carries no licence field, so no per-image licence can be propagated.",
        attribution = AttributionRequirement.NOT_SPECIFIED,
        attributionNote = "The pages read do not specify an attribution requirement.",
        attributionNotice = null,
        sourceUrls = listOf(
            "https://coverartarchive.org",
            "https://musicbrainz.org/doc/Cover_Art_Archive/API",
        ),
        asReadOn = AS_READ_ON,
    )

    private val listenBrainz = ProviderPolicy(
        providerId = "listenbrainz",
        commercialUse = CommercialUse.UNVERIFIED,
        commercialUseNote = "Could not verify: the terms page renders only under JavaScript.",
        dataLicence = null,
        attribution = AttributionRequirement.UNVERIFIED,
        attributionNote = "Could not verify: the terms page renders only under JavaScript.",
        attributionNotice = null,
        sourceUrls = listOf("https://listenbrainz.readthedocs.io/en/latest/users/api/index.html"),
        asReadOn = AS_READ_ON,
    )

    private val wikidata = ProviderPolicy(
        providerId = "wikidata",
        commercialUse = CommercialUse.ALLOWED,
        commercialUseNote = "Data access is allowed, free and unauthenticated.",
        dataLicence = "CC0 (the exact wording was not verbatim-confirmed on the page read).",
        attribution = AttributionRequirement.NOT_REQUIRED,
        attributionNote = "None required.",
        attributionNotice = null,
        sourceUrls = listOf("https://www.wikidata.org/wiki/Wikidata:Data_access"),
        asReadOn = AS_READ_ON,
    )

    private val wikipedia = ProviderPolicy(
        providerId = "wikipedia",
        commercialUse = CommercialUse.ALLOWED,
        commercialUseNote = "Reuse is allowed and free. The Wikimedia user-agent policy is binding: a request " +
            "must carry \"<client name>/<version> (<contact information>)\" and generic user agents \"may be " +
            "blocked\".",
        dataLicence = "Text is CC BY-SA 4.0 / GFDL; media are licensed per file.",
        attribution = AttributionRequirement.REQUIRED,
        attributionNote = "Reusers must \"Include licensing notice with each distributed copy\", plus one of: a " +
            "link to the article, a link to a stable copy, or the full author list. Render the notice beside a " +
            "link to the article, which carries its author history.",
        attributionNotice = "Text from Wikipedia, licensed under CC BY-SA 4.0.",
        sourceUrls = listOf("https://en.wikipedia.org/wiki/Wikipedia:Reusing_Wikipedia_content"),
        asReadOn = AS_READ_ON,
    )

    private val deezer = ProviderPolicy(
        providerId = "deezer",
        commercialUse = CommercialUse.PROHIBITED,
        commercialUseNote = "Use \"is strictly limited for a non-commercial purpose and in a non-commercial " +
            "environment\"; the developer \"shall not perceive, receive, generate, benefit or create directly " +
            "or indirectly, any moneys, incomes, revenues, data or any other consideration in connection with " +
            "the use of neither the Services themselves, nor any and all Content\". No separate-agreement " +
            "escape hatch appears on the terms page.",
        dataLicence = "\"Any reproduction and representation, in total or partially... without the express " +
            "authorization of DEEZER or right holders are strictly forbidden.\"",
        attribution = AttributionRequirement.NOT_SPECIFIED,
        attributionNote = "No attribution clause. The terms incorporate Deezer's Trademark Guidelines, whose " +
            "content could not be verified, and require that content \"shall not be associated, directly or " +
            "indirectly with any trademark, brand name, or logo\".",
        attributionNotice = null,
        sourceUrls = listOf("https://developers.deezer.com/termsofuse"),
        asReadOn = AS_READ_ON,
    )

    private val iTunes = ProviderPolicy(
        providerId = "itunes",
        commercialUse = CommercialUse.CONDITIONAL,
        commercialUseNote = "Conditional on Apple's badge, streaming-only and promotional rules. Apple's " +
            "governing terms document 404s, so those rules could not be confirmed against it.",
        dataLicence = "Search and lookup JSON may be cached — \"Large websites should set up caching logic for " +
            "the search and lookup requests\". The preview-media restriction could not be verified.",
        attribution = AttributionRequirement.DEPENDS_ON_DATA,
        attributionNote = "\"provided courtesy of iTunes\" is triggered by showing previews, not by showing " +
            "factual metadata; musicmeta surfaces no previews, so a consumer owes it only if it plays them " +
            "itself. The words are in attributionNotice for the consumer that does.",
        attributionNotice = "provided courtesy of iTunes",
        sourceUrls = listOf("https://performance-partners.apple.com/search-api"),
        asReadOn = AS_READ_ON,
    )

    private val lrcLib = ProviderPolicy(
        providerId = "lrclib",
        commercialUse = CommercialUse.UNVERIFIED,
        commercialUseNote = "Could not verify: no terms instrument is published at all.",
        dataLicence = null,
        attribution = AttributionRequirement.UNVERIFIED,
        attributionNote = "Could not verify: no terms instrument is published. The project's MIT licence covers " +
            "the server code, not the lyrics it serves, which are third-party copyrighted works regardless.",
        attributionNotice = null,
        sourceUrls = listOf("https://lrclib.net", "https://github.com/tranxuanthang/lrclib"),
        asReadOn = AS_READ_ON,
    )

    private val lastFm = ProviderPolicy(
        providerId = "lastfm",
        commercialUse = CommercialUse.CONDITIONAL,
        commercialUseNote = "\"You are permitted to use the Last.fm Data solely for non-commercial purposes and " +
            "for no other purpose\"; commercial use without a written agreement \"constitutes a material " +
            "breach\".",
        dataLicence = "Copy, adapt and distribute, capped by a Reasonable Usage Cap of \"a maximum of 100 MB\" " +
            "covering \"any use, storage, publication, distribution, communication, making available or " +
            "otherwise\"; no sub-licensing, no DRM; \"You will implement suitable caching in accordance with " +
            "the HTTP headers sent with web service responses.\"",
        attribution = AttributionRequirement.REQUIRED,
        attributionNote = "\"You must credit Last.fm and include links to the Last.fm site when You use the " +
            "Last.fm Data.\"",
        attributionNotice = "Data provided by Last.fm — https://www.last.fm/",
        sourceUrls = listOf("https://www.last.fm/api/tos"),
        asReadOn = AS_READ_ON,
    )

    private val fanartTv = ProviderPolicy(
        providerId = "fanarttv",
        commercialUse = CommercialUse.UNVERIFIED,
        commercialUseNote = "Could not verify: the terms page returns a Cloudflare 403 to a non-browser client.",
        dataLicence = null,
        attribution = AttributionRequirement.UNVERIFIED,
        attributionNote = "Could not verify: the terms page returns a Cloudflare 403 to a non-browser client.",
        attributionNotice = null,
        sourceUrls = listOf("https://fanart.tv/terms-of-use/"),
        asReadOn = AS_READ_ON,
    )

    private val discogs = ProviderPolicy(
        providerId = "discogs",
        commercialUse = CommercialUse.ALLOWED,
        commercialUseNote = "Allowed under a \"Limited, non-sublicensable, non-transferable, non-exclusive, " +
            "revocable\" licence, which prohibits \"Charging a fee to use or access any part of Your " +
            "application that integrates with Our API Content if we provide that access to users free of " +
            "charge\".",
        dataLicence = "The data dumps are CC0; whether images are carved out could not be verified.",
        attribution = AttributionRequirement.REQUIRED,
        attributionNote = "A disaffiliation notice, not a credit line.",
        attributionNotice = "This application uses Discogs' API but is not affiliated with, sponsored or " +
            "endorsed by Discogs.",
        sourceUrls = listOf(
            "https://support.discogs.com/hc/en-us/articles/360009334593",
            "https://www.discogs.com/developers",
        ),
        asReadOn = AS_READ_ON,
    )

    private val entries: List<ProviderPolicy> = listOf(
        musicBrainz,
        coverArtArchive,
        listenBrainz,
        wikidata,
        wikipedia,
        deezer,
        // Same upstream, same terms page, second provider id.
        deezer.copy(providerId = "deezer-similar-albums"),
        iTunes,
        lrcLib,
        lastFm,
        fanartTv,
        discogs,
    )

    /** Every shipped provider's policy, keyed by provider id. */
    public val all: Map<String, ProviderPolicy> = entries.associateBy { it.providerId }

    /** The policy for [providerId], or `null` if musicmeta ships no snapshot for it. */
    public operator fun get(providerId: String): ProviderPolicy? = all[providerId]
}
