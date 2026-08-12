package com.landofoz.musicmeta

import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.engine.ArtworkMerger
import com.landofoz.musicmeta.engine.CompositeSynthesizer
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.GenreAffinityMatcher
import com.landofoz.musicmeta.engine.GenreMerger
import com.landofoz.musicmeta.engine.PopularityMerger
import com.landofoz.musicmeta.engine.ProviderRegistry
import com.landofoz.musicmeta.engine.SimilarArtistMerger
import com.landofoz.musicmeta.engine.SimilarTrackMerger
import com.landofoz.musicmeta.engine.TimelineSynthesizer
import com.landofoz.musicmeta.engine.TopTrackMerger
import com.landofoz.musicmeta.engine.requireRegistrableProviderId
import com.landofoz.musicmeta.http.DefaultHttpClient
import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.provider.coverartarchive.CoverArtArchiveProvider
import com.landofoz.musicmeta.provider.deezer.DeezerApi
import com.landofoz.musicmeta.provider.deezer.DeezerProvider
import com.landofoz.musicmeta.provider.deezer.SimilarAlbumsProvider
import com.landofoz.musicmeta.provider.discogs.DiscogsProvider
import com.landofoz.musicmeta.provider.fanarttv.FanartTvProvider
import com.landofoz.musicmeta.provider.itunes.ITunesProvider
import com.landofoz.musicmeta.provider.lastfm.LastFmProvider
import com.landofoz.musicmeta.provider.listenbrainz.ListenBrainzProvider
import com.landofoz.musicmeta.provider.lrclib.LrcLibProvider
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import com.landofoz.musicmeta.provider.wikidata.WikidataProvider
import com.landofoz.musicmeta.provider.wikipedia.WikipediaProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val TAG = "EnrichmentEngine"

/**
 * Providers whose published policy requires contact information in the User-Agent.
 * `ProviderPolicies["wikipedia"].commercialUseNote` carries the Wikimedia clause as data; the
 * MusicBrainz requirement is quoted in `docs/providers.md`'s User-Agent section, not in its policy
 * entry. Wikidata is here as a Wikimedia-hosted API under that same Wikimedia policy — an
 * extension of the Wikipedia evidence rather than a clause cited for Wikidata itself.
 */
private val CONTACT_REQUIRING_PROVIDERS = setOf("musicbrainz", "wikipedia", "wikidata")

interface EnrichmentEngine {

    /**
     * Enriches a music entity with the requested data types.
     *
     * **On timeout the result is partial, not empty.** When [EnrichmentConfig.enrichTimeoutMs]
     * expires, whatever was already fetched is returned as it stands, and every type with no result
     * yet becomes an [EnrichmentResult.Error] with [ErrorKind.TIMEOUT] and provider `"engine"` — the
     * presence of such an entry is how a caller tells a truncated run from a complete one. A
     * returned [EnrichmentResult.Success] from a truncated run may have skipped later processing:
     * catalog filtering, notably, may not have reached it. Nothing a timed-out run produced is
     * written to the cache for that reason, so the next call re-fetches.
     *
     * @param forceRefresh When true, bypasses the cache for the requested types and fetches fresh
     *   data from providers. Existing cache entries (including manual selections) are cleared first
     *   on a best-effort basis: if the cache throws while clearing, the failure is logged and fresh
     *   data is still fetched, rather than being surfaced to the caller.
     */
    suspend fun enrich(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean = false,
    ): EnrichmentResults

    /**
     * Enriches multiple requests sequentially, emitting each result as it completes.
     *
     * Results are emitted as a cold [Flow]. Cancelling collection (e.g., via [take])
     * stops processing remaining requests cooperatively.
     *
     * Cache hits return immediately without rate-limiter delay because the
     * underlying [enrich] call short-circuits on cached data.
     */
    fun enrichBatch(
        requests: List<EnrichmentRequest>,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean = false,
    ): Flow<Pair<EnrichmentRequest, EnrichmentResults>> = flow {
        for (request in requests) {
            emit(request to enrich(request, types, forceRefresh))
        }
    }

    suspend fun search(
        request: EnrichmentRequest,
        limit: Int = 10,
    ): List<SearchCandidate>

    fun getProviders(): List<ProviderInfo>

    val cache: EnrichmentCache

    /**
     * Invalidates cached data for a request. Pass a specific [type] or null to clear all types.
     *
     * For an identifier-only request (no names supplied), this costs one identity lookup to reach
     * the canonical-name alias the result was also cached under. If that lookup fails transiently,
     * the alias may survive the invalidation — retry, or enrich with `forceRefresh` instead.
     */
    suspend fun invalidate(request: EnrichmentRequest, type: EnrichmentType? = null)

    /** Whether the user has manually selected data for this request/type (e.g., picked artwork). */
    suspend fun isManuallySelected(request: EnrichmentRequest, type: EnrichmentType): Boolean

    /** Marks data as manually selected by the user, protecting it from automatic overwrites. */
    suspend fun markManuallySelected(request: EnrichmentRequest, type: EnrichmentType)

    class Builder {
        private val providers = mutableListOf<EnrichmentProvider>()
        private var cache: EnrichmentCache? = null
        private var httpClient: HttpClient? = null
        private var config: EnrichmentConfig = EnrichmentConfig()
        private var contact: String? = null

        /**
         * The User-Agent the client [withDefaultProviders] built carries, or null when it built
         * none. What [build] warns from: the config it composes is not what the wire sends.
         */
        private var defaultProvidersUserAgent: String? = null
        private var logger: EnrichmentLogger = EnrichmentLogger.NoOp
        private var apiKeyConfig: ApiKeyConfig? = null
        private val mergers = mutableListOf<com.landofoz.musicmeta.engine.ResultMerger>(
            GenreMerger, SimilarArtistMerger, SimilarTrackMerger,
            ArtworkMerger(EnrichmentType.ARTIST_PHOTO),
            ArtworkMerger(EnrichmentType.ALBUM_ART),
            TopTrackMerger,
            PopularityMerger(EnrichmentType.ARTIST_POPULARITY),
            PopularityMerger(EnrichmentType.TRACK_POPULARITY),
        )
        private val synthesizers = mutableListOf<CompositeSynthesizer>(TimelineSynthesizer, GenreAffinityMatcher)

        /** @throws IllegalArgumentException if the id is already registered, or reserved by the engine. */
        fun addProvider(provider: EnrichmentProvider) = apply {
            requireRegistrableProviderId(provider.id, providers.map { it.id })
            providers.add(provider)
        }
        fun cache(cache: EnrichmentCache) = apply { this.cache = cache }
        fun httpClient(client: HttpClient) = apply { this.httpClient = client }
        fun config(config: EnrichmentConfig) = apply { this.config = config }

        /**
         * A URL or email address a provider's operators can reach you at, folded into the
         * User-Agent as `MusicEnrichmentEngine/1.0 ( contact )`.
         *
         * MusicBrainz and Wikimedia both require the User-Agent to carry contact information;
         * without it MusicBrainz throttles you against a shared anonymous pool and Wikimedia may
         * answer 403. This is the short way to comply — see [EnrichmentConfig.DEFAULT_USER_AGENT].
         *
         * **Ignored when [config] carries a User-Agent of its own**, which is then used verbatim:
         * a caller who writes the whole string owns all of it, contact included. Like
         * [apiKeys] and [config], read by [withDefaultProviders] when you call it, so it must come
         * first — call it after and the client is already built with the contactless default, which
         * [build] warns about. It cannot reach a client passed to [httpClient] at all: set the
         * User-Agent on that client instead, which [build] also warns about.
         *
         * @throws IllegalArgumentException if [contact] is blank, carries a line break (which the
         *   connection rejects per request), or carries a parenthesis (which closes the User-Agent
         *   comment the policies read).
         */
        fun contact(contact: String) = apply {
            requireUsableContact(contact)
            this.contact = contact
        }
        fun logger(logger: EnrichmentLogger) = apply { this.logger = logger.guarded() }
        fun apiKeys(config: ApiKeyConfig) = apply { this.apiKeyConfig = config }
        fun catalog(provider: CatalogProvider, mode: CatalogFilterMode = CatalogFilterMode.UNFILTERED) = apply {
            this.config = this.config.copy(catalogProvider = provider, catalogFilterMode = mode)
        }
        fun addMerger(merger: com.landofoz.musicmeta.engine.ResultMerger) = apply { mergers.add(merger) }
        fun addSynthesizer(synthesizer: CompositeSynthesizer) = apply { synthesizers.add(synthesizer) }

        fun withDefaultProviders() = apply {
            val cfg = effectiveConfig()
            val client = httpClient ?: DefaultHttpClient(cfg.userAgent).also {
                // What the wire will carry from here on, whatever a later contact() composes.
                defaultProvidersUserAgent = cfg.userAgent
            }

            // One limiter per host. RateLimiter holds its mutex across block(), so a shared
            // instance makes unrelated hosts' round-trips sequential; rate limits are per-host
            // and no host here asks to be throttled against another's traffic (#50).
            // Each interval says which of three it is — published, measured or judgement.
            // Do not read a judgement figure as a documented one.
            val musicBrainzLimiter = RateLimiter(1100) // published 2026-07-27: 1 req/sec
            val listenBrainzLimiter = RateLimiter(400) // measured 2026-07-27: 30 req/10s, with headroom
            val coverArtArchiveLimiter = RateLimiter(100) // judgement 2026-07-27: CAA documents no limit
            val wikidataLimiter = RateLimiter(100) // judgement 2026-07-27
            val wikipediaLimiter = RateLimiter(100) // judgement 2026-07-27
            val deezerLimiter = RateLimiter(100) // judgement 2026-07-27; one host, both Deezer providers share it
            val lrcLibLimiter = RateLimiter(100) // judgement 2026-07-27
            val lastFmLimiter = RateLimiter(200) // judgement 2026-07-27: no published figure (API ToS §4.4)
            val fanartTvLimiter = RateLimiter(100) // judgement 2026-07-27
            // measured 2026-07-28: x-discogs-ratelimit: 60 req/min authenticated. 1100ms
            // (~54/min) is headroom below that bucket.
            val discogsLimiter = RateLimiter(1100)

            // Always-available providers (no API key needed)
            addProvider(MusicBrainzProvider(client, musicBrainzLimiter))
            addProvider(CoverArtArchiveProvider(client, coverArtArchiveLimiter))
            addProvider(WikidataProvider(client, wikidataLimiter))
            addProvider(WikipediaProvider(client, wikipediaLimiter, wikidataLimiter))
            addProvider(DeezerProvider(client, deezerLimiter,
                radioLimit = cfg.radioLimit))
            val deezerApi = DeezerApi(client, deezerLimiter)
            addProvider(SimilarAlbumsProvider(deezerApi))
            addProvider(ITunesProvider(client)) // its own RateLimiter(3000) by constructor default
            addProvider(ListenBrainzProvider(
                httpClient = client,
                rateLimiter = listenBrainzLimiter,
                authToken = apiKeyConfig?.listenBrainzToken,
                config = cfg,
            ))
            addProvider(LrcLibProvider(client, lrcLibLimiter))

            // Key-requiring providers (only added if key is provided)
            val keys = apiKeyConfig
            if (keys != null) {
                keys.lastFmKey?.let {
                    addProvider(LastFmProvider(it, client, lastFmLimiter))
                }
                keys.fanartTvProjectKey?.let {
                    addProvider(FanartTvProvider(it, client, fanartTvLimiter))
                }
                keys.discogsPersonalToken?.let {
                    addProvider(DiscogsProvider(it, client, discogsLimiter))
                }
            }
        }

        fun build(): EnrichmentEngine {
            val cfg = effectiveConfig()
            warnAboutUserAgentOnTheWire(cfg)
            val registry = ProviderRegistry(providers, cfg.priorityOverrides, logger)
            return DefaultEnrichmentEngine(
                registry = registry,
                cache = cache ?: InMemoryEnrichmentCache(),
                config = cfg,
                logger = logger,
                mergers = mergers.toList(),
                synthesizers = synthesizers.toList(),
            )
        }

        /**
         * The config as the engine and its providers see it: [contact] folded into the User-Agent,
         * unless the caller supplied a User-Agent of their own.
         */
        private fun effectiveConfig(): EnrichmentConfig {
            val contact = this.contact ?: return config
            if (config.userAgent != EnrichmentConfig.DEFAULT_USER_AGENT) return config
            return config.copy(userAgent = EnrichmentConfig.userAgentWithContact(contact))
        }

        /**
         * At most one warning per condition per `build()`, so a consumer hears it at startup rather
         * than once per request. Warns from what the wire will carry wherever the engine builds the
         * client itself, which the composed config alone does not say: composing a contact after
         * [withDefaultProviders] has already built the client reads as compliant to a config-only
         * check. A client supplied to [httpClient] carries a User-Agent this class cannot read, so
         * the contactless-default warning stays silent for a build whose only client is that one;
         * pairing [contact] with a caller-supplied [httpClient] draws a warning of its own, since
         * this class cannot verify what User-Agent that client actually sends.
         */
        private fun warnAboutUserAgentOnTheWire(cfg: EnrichmentConfig) {
            val affected = providers.map { it.id }.filter { it in CONTACT_REQUIRING_PROVIDERS }
            if (affected.isEmpty()) return
            if ((httpClient == null || defaultProvidersUserAgent != null) &&
                cfg.userAgent == EnrichmentConfig.DEFAULT_USER_AGENT
            ) {
                logger.warn(
                    TAG,
                    "User-Agent \"${EnrichmentConfig.DEFAULT_USER_AGENT}\" carries no contact information, " +
                        "which the policies of these registered providers require " +
                        "(${affected.joinToString(", ")}): MusicBrainz throttles anonymous user agents " +
                        "against one shared pool and Wikimedia may answer 403. Pass a contact URL or email " +
                        "to EnrichmentEngine.Builder.contact(), or set EnrichmentConfig.userAgent.",
                )
            }
            if (defaultProvidersUserAgent == EnrichmentConfig.DEFAULT_USER_AGENT &&
                cfg.userAgent != EnrichmentConfig.DEFAULT_USER_AGENT
            ) {
                logger.warn(
                    TAG,
                    "withDefaultProviders() built the HTTP client before the User-Agent was set, so the " +
                        "wire still carries the contactless default " +
                        "\"${EnrichmentConfig.DEFAULT_USER_AGENT}\" and \"${cfg.userAgent}\" reaches no " +
                        "provider (${affected.joinToString(", ")} require contact information). Call " +
                        "contact() before withDefaultProviders().",
                )
            }
            if (httpClient != null && contact != null) {
                logger.warn(
                    TAG,
                    "contact() cannot alter a caller-supplied client's User-Agent: the client passed to " +
                        "httpClient() sends whatever it was built with, so \"$contact\" reaches no " +
                        "provider (${affected.joinToString(", ")} require contact information). Set the " +
                        "User-Agent where that client is constructed (OkHttpEnrichmentClient and " +
                        "DefaultHttpClient both take it as a constructor argument).",
                )
            }
        }
    }
}

data class SearchCandidate(
    val title: String,
    val artist: String?,
    val year: String?,
    val country: String?,
    val releaseType: String?,
    val score: Int,
    val thumbnailUrl: String?,
    val identifiers: EnrichmentIdentifiers,
    val provider: String,
    /** MusicBrainz disambiguation comment (e.g., "British rock band" vs "Canadian band"). */
    val disambiguation: String? = null,
)

data class ProviderInfo(
    val id: String,
    val displayName: String,
    val capabilities: List<ProviderCapability>,
    val requiresApiKey: Boolean,
    val isAvailable: Boolean,
    val isEnabled: Boolean = true,
)
