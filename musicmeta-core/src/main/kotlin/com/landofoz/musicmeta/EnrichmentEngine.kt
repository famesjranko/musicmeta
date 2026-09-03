package com.landofoz.musicmeta

import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import com.landofoz.musicmeta.engine.CompositeSynthesizer
import com.landofoz.musicmeta.engine.DEFAULT_MERGERS
import com.landofoz.musicmeta.engine.DEFAULT_SYNTHESIZERS
import com.landofoz.musicmeta.engine.DefaultEnrichmentEngine
import com.landofoz.musicmeta.engine.ProviderRegistry
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

private const val TAG = "EnrichmentEngine"

/**
 * Providers whose published policy requires contact information in the User-Agent.
 * `ProviderPolicies["wikipedia"].commercialUseNote` carries the Wikimedia clause as data; the
 * MusicBrainz requirement is quoted in `docs/providers.md`'s User-Agent section, not in its policy
 * entry. Wikidata is here as a Wikimedia-hosted API under that same Wikimedia policy — an
 * extension of the Wikipedia evidence rather than a clause cited for Wikidata itself.
 */
private val CONTACT_REQUIRING_PROVIDERS = setOf("musicbrainz", "wikipedia", "wikidata")

public interface EnrichmentEngine {

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
     *
     * **Cancelling the calling coroutine is complete-and-cache, not abort-and-forfeit.** This is
     * `enrichProgressive(...).last()` against one shared resolution path (see that method's own
     * KDoc), so cancelling the coroutine that called [enrich] detaches from the fan-out already
     * under way rather than aborting it: that fan-out keeps running, unattended, until it settles or
     * [EnrichmentConfig.enrichTimeoutMs] expires, and its cache write-back still happens. There is no
     * per-call abort: a detached run keeps spending rate-limiter and circuit-breaker budget unwatched,
     * and [close] can only stop it by shutting the whole engine down, never one call or request.
     */
    public suspend fun enrich(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean = false,
    ): EnrichmentResults

    /**
     * [enrich], as a cumulative stream: each emission is a complete, valid [EnrichmentResults]
     * snapshot of everything settled so far, filtered to [types] — never a per-result diff. A
     * caller derives what is still pending as `types - results.raw.keys`; a type present in
     * [EnrichmentResults.raw] has settled, and a settlement with no answer is a
     * [EnrichmentResult.NotFound] entry there rather than a missing key. Only the last emission has
     * an empty derived-pending set, so a collector that stops early never mistakes an intermediate
     * snapshot for the finished one.
     *
     * A settled type is catalog-filtered, provenance-stamped, and stale-cache-resolved before the
     * emission that first carries it, so an intermediate is safe to render — but a type's value can
     * still change before the terminal emission (e.g. cache write-back may alter what a later,
     * unrelated call would have served it). Emission order between types carries no contract: two
     * runs of the same request may settle types in a different sequence.
     *
     * Two values exist specifically for a pre-terminal read: [EnrichmentResults.identity]`.status`
     * can be [CanonicalStatus.RESOLVING] on an intermediate emission — identity resolution runs
     * concurrently with everything else and is never backdated once it lands — and a settled
     * recommendation-type [EnrichmentResult.Success] can carry
     * [EnrichmentResult.Success.isCatalogDegraded] set, meaning this call's own [CatalogProvider]
     * threw and that type reached you unranked. [CanonicalStatus.RESOLVING] is never seen on
     * [enrich]'s return or on this stream's terminal emission — it always resolves to a real status
     * by then. `isCatalogDegraded`, in contrast, can be `true` on the terminal emission too: it is
     * not a "still settling" signal, it is a "settled, but filtering failed" signal.
     *
     * [enrich] is `enrichProgressive(...).last()` against one shared resolution path, so the
     * terminal emission here and [enrich]'s return are always identical, including on a timeout.
     * The default implementation below runs [enrich] once and emits its result as the sole,
     * terminal snapshot — the cadence a third-party [EnrichmentEngine] gets for free without
     * overriding this method.
     *
     * **Cancellation is complete-and-cache, not abort-and-forfeit.** Cancelling collection (e.g.
     * `take(1)`, or leaving composition) detaches the collector from the fan-out already under way;
     * that fan-out keeps running, unattended, until it settles or [EnrichmentConfig.enrichTimeoutMs]
     * expires — bounded to the one run for this exact request/[types]/[forceRefresh] combination, so
     * a caller who cancels and re-calls the same thing N times in a row never multiplies upstream
     * traffic by N. Its cache write-back still happens, so a subsequent equivalent call is typically
     * a cache hit, not a re-fetch. This costs real work continuing after you stop watching it: there
     * is no per-call abort, a detached run keeps spending rate-limiter and circuit-breaker budget
     * unwatched, and [close] can only stop it by shutting the whole engine down, never one call.
     * A third-party implementor relying on the default single-emission body above has nothing to
     * detach from: there is no cancellation cost to document for it.
     */
    public fun enrichProgressive(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean = false,
    ): Flow<EnrichmentResults> = flow {
        emit(enrich(request, types, forceRefresh))
    }

    /**
     * Releases resources an implementation may hold for [enrichProgressive]'s complete-and-cache
     * detachment (a scope that fan-out already in flight when a collector cancels keeps running
     * on). The default here is a no-op: nothing above needs releasing without that mechanism.
     *
     * [DefaultEnrichmentEngine][com.landofoz.musicmeta.engine.DefaultEnrichmentEngine] overrides
     * this as a hard shutdown, not a drain: any detached run still in flight is abandoned rather
     * than waited for, and writes nothing back — the same rule a timed-out run already follows. A
     * still-attached collector, or a later call this engine had never seen before shutdown, is
     * released with every requested type present: settled types keep their real result, and every
     * unsettled one becomes an [EnrichmentResult.Error] with [ErrorKind.ENGINE_CLOSED] — the same
     * per-type completeness [enrich] documents for a timeout. Never called automatically; skipping
     * it costs at most one shared dispatcher and whatever detached runs are genuinely in flight,
     * never an unbounded amount.
     */
    public fun close() {}

    /**
     * Enriches multiple requests sequentially, emitting each result as it completes.
     *
     * Results are emitted as a cold [Flow]. Cancelling collection (e.g., via [take])
     * stops processing remaining requests cooperatively.
     *
     * Cache hits return immediately without rate-limiter delay because the
     * underlying [enrich] call short-circuits on cached data.
     */
    public fun enrichBatch(
        requests: List<EnrichmentRequest>,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean = false,
    ): Flow<Pair<EnrichmentRequest, EnrichmentResults>> = flow {
        for (request in requests) {
            emit(request to enrich(request, types, forceRefresh))
        }
    }

    /**
     * [enrichBatch], as a cumulative per-request stream: composed from [enrichProgressive] rather
     * than [enrich], so each emission is one request's own cumulative snapshot of everything
     * settled so far for it — the same derivation (`types - results.raw.keys`), cadence (only a
     * request's last emission has an empty derived-pending set) and complete-and-cache contract
     * [enrichProgressive] documents, carried through unmodified per request.
     *
     * Requests are still processed one at a time, in [requests] order — [enrichBatch]'s existing
     * sequential bound, reused rather than replaced: this method's payoff is a row that fills in
     * progressively as its own entity's types settle, not every row filling in at once, and
     * concurrent fan-out across [requests] would multiply upstream traffic the way
     * [enrichProgressive]'s own KDoc already flags for one entity's repeated collection. Cancelling
     * collection (e.g. [take]) stops processing remaining requests cooperatively, exactly as
     * [enrichBatch] does; a request already mid-stream when cancellation reaches it detaches under
     * [enrichProgressive]'s own complete-and-cache rule and keeps running regardless.
     *
     * The same request appearing twice in [requests], or in a concurrently-running
     * [enrichBatchProgressive]/[enrichProgressive] call for the same request/[types]/[forceRefresh],
     * does not double-fetch: every occurrence coalesces through the one run registry
     * [enrichProgressive] itself uses.
     *
     * The default implementation below calls [enrichProgressive] once per request; a third-party
     * [EnrichmentEngine] that only overrides [enrich] gets exactly one (terminal) snapshot per
     * request for free, matching [enrichProgressive]'s own default cadence.
     */
    public fun enrichBatchProgressive(
        requests: List<EnrichmentRequest>,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean = false,
    ): Flow<Pair<EnrichmentRequest, EnrichmentResults>> = flow {
        for (request in requests) {
            emitAll(enrichProgressive(request, types, forceRefresh).map { request to it })
        }
    }

    /**
     * Searches for entities matching [request], for a manual-search UI where the caller lets a
     * user pick the right match from a list.
     *
     * **Do not sort the result by [SearchCandidate.score].** The identity provider returns an
     * upstream relevance score and a supplemental provider that has no such number returns a flat
     * constant, so sorting on it ranks unlike scales. The list arrives in provenance order — the
     * identity provider's candidates, then whatever the others uniquely added — and is never
     * re-sorted.
     *
     * **This call has no hard deadline.** Unlike [enrich] it never truncates, so against a merely
     * slow upstream it can outlast [EnrichmentConfig.enrichTimeoutMs]; a caller who needs search
     * bounded must impose the bound itself, with `withTimeout` or equivalent. That timeout does
     * bound a rate-limit retry here — both how long it sleeps and whether it is attempted at all.
     *
     * **Not [enrich]:** it bypasses the cache, identity resolution, confidence gating, and result
     * merging entirely. Every call re-asks providers over the network; nothing here reads or
     * writes [cache].
     *
     * Asks the provider whose [EnrichmentProvider.isIdentityProvider] is true first, for up to
     * [limit] candidates. Only if that returns fewer does it also ask every other provider whose
     * [EnrichmentProvider.isAvailable] is true for the remainder. A candidate whose lowercased
     * `title:artist` duplicates one the identity provider already returned is dropped. That dedup
     * runs only against the identity provider, so two supplemental providers can both return the
     * same entity — dedupe in the UI if that matters. A provider that throws is logged and
     * contributes no candidates rather than failing the call.
     */
    public suspend fun search(
        request: EnrichmentRequest,
        limit: Int = 10,
    ): List<SearchCandidate>

    /**
     * Every provider the engine was built with, whether or not it can currently run.
     *
     * Each [ProviderInfo] is a snapshot: [ProviderInfo.isAvailable] reads the provider's state at
     * the moment of the call, so a provider that resolves its key lazily reports differently on a
     * later call.
     */
    public fun getProviders(): List<ProviderInfo>

    public val cache: EnrichmentCache

    /**
     * Invalidates cached data for a request. Pass a specific [type] or null to clear all types.
     *
     * For an identifier-only request (no names supplied), this costs one identity lookup to reach
     * the canonical-name alias the result was also cached under. If that lookup fails transiently,
     * the alias may survive the invalidation — retry, or enrich with `forceRefresh` instead.
     */
    public suspend fun invalidate(request: EnrichmentRequest, type: EnrichmentType? = null)

    /** Whether the user has manually selected data for this request/type (e.g., picked artwork). */
    public suspend fun isManuallySelected(request: EnrichmentRequest, type: EnrichmentType): Boolean

    /** Marks data as manually selected by the user, protecting it from automatic overwrites. */
    public suspend fun markManuallySelected(request: EnrichmentRequest, type: EnrichmentType)

    public class Builder {
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
        private val mergers = DEFAULT_MERGERS.toMutableList()
        private val synthesizers = DEFAULT_SYNTHESIZERS.toMutableList()

        /** @throws IllegalArgumentException if the id is already registered, or reserved by the engine. */
        public fun addProvider(provider: EnrichmentProvider): Builder = apply {
            requireRegistrableProviderId(provider.id, providers.map { it.id })
            providers.add(provider)
        }
        public fun cache(cache: EnrichmentCache): Builder = apply { this.cache = cache }
        public fun httpClient(client: HttpClient): Builder = apply { this.httpClient = client }
        public fun config(config: EnrichmentConfig): Builder = apply { this.config = config }

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
        public fun contact(contact: String): Builder = apply {
            requireUsableContact(contact)
            this.contact = contact
        }
        public fun logger(logger: EnrichmentLogger): Builder = apply { this.logger = logger.guarded() }
        public fun apiKeys(config: ApiKeyConfig): Builder = apply { this.apiKeyConfig = config }
        public fun catalog(
            provider: CatalogProvider,
            mode: CatalogFilterMode = CatalogFilterMode.UNFILTERED,
        ): Builder = apply {
            this.config = this.config.copy(catalogProvider = provider, catalogFilterMode = mode)
        }
        public fun addMerger(merger: com.landofoz.musicmeta.engine.ResultMerger): Builder =
            apply { mergers.add(merger) }
        public fun addSynthesizer(synthesizer: CompositeSynthesizer): Builder = apply { synthesizers.add(synthesizer) }

        public fun withDefaultProviders(): Builder = apply {
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
                authToken = apiKeyConfig?.get(ApiKey.LISTENBRAINZ_USER_TOKEN),
                config = cfg,
            ))
            addProvider(LrcLibProvider(client, lrcLibLimiter))

            // Key-requiring providers (only added if key is provided)
            val keys = apiKeyConfig
            if (keys != null) {
                keys[ApiKey.LASTFM_API_KEY]?.let {
                    addProvider(LastFmProvider(it, client, lastFmLimiter))
                }
                keys[ApiKey.FANARTTV_PROJECT_KEY]?.let {
                    addProvider(FanartTvProvider(it, client, fanartTvLimiter))
                }
                keys[ApiKey.DISCOGS_PERSONAL_TOKEN]?.let {
                    addProvider(DiscogsProvider(it, client, discogsLimiter))
                }
            }
        }

        /**
         * @throws IllegalArgumentException if the registered [CompositeSynthesizer]s form a
         *   dependency cycle, a synthesizer depending on its own type included — the message names
         *   every type on the cycle — or if a type is registered as both a composite and a
         *   mergeable, whose [ResultMerger] could then never run.
         */
        public fun build(): EnrichmentEngine {
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

public data class SearchCandidate(
    val title: String,
    val artist: String?,
    val year: String?,
    /**
     * ISO 3166-1 alpha-2 where the upstream names a current ISO country (`GB`), otherwise the
     * upstream's own label — MusicBrainz's `XE`/`XW` reach a consumer here. Null on an iTunes or
     * Deezer candidate and on any recording search hit, none of which carries a release country.
     * The same rule as [EnrichmentData.Metadata.country].
     */
    val country: String?,
    val releaseType: String?,
    val score: Int,
    val thumbnailUrl: String?,
    val identifiers: EnrichmentIdentifiers,
    val provider: String,
    /** MusicBrainz disambiguation comment (e.g., "British rock band" vs "Canadian band"). */
    val disambiguation: String? = null,
)

/** One provider's identity and current capability, as [EnrichmentEngine.getProviders] reports it. */
public data class ProviderInfo(
    /**
     * Matches [EnrichmentProvider.id]. An [EnrichmentResult] names this id too, except where a
     * merger or synthesizer produced it and stamped its own `_merger`/`_synthesizer` suffix.
     */
    val id: String,
    /** Name for a settings screen or provider picker; never used to look the provider up. */
    val displayName: String,
    /** What this provider can supply, and the [IdentifierRequirement] each capability needs. */
    val capabilities: List<ProviderCapability>,
    /** Whether the provider needs a caller-supplied API key at all, regardless of whether one was given. */
    val requiresApiKey: Boolean,
    /**
     * Whether the provider can run right now — the provider's own answer, not a derived one. Every
     * built-in gates it on a key: false exactly when [requiresApiKey] is true and none was given.
     */
    val isAvailable: Boolean,
)
