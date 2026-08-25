package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.MusicBrainzEntityType
import com.landofoz.musicmeta.ProviderInfo
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.http.EnrichDeadline
import com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** [CachePersistence.writeBack]'s per-call facts, bundled to keep its parameter list short. */
internal data class WriteBackContext(
    val identityResolution: IdentityResolution,
    val negativeCacheHits: Set<EnrichmentType>,
    val chainExecutions: Map<EnrichmentType, ChainExecution>,
    val filterEmptied: Set<EnrichmentType>,
    val staleDerived: Set<EnrichmentType>,
)

/** The cache layer's own outcome for a call — split off [CachePersistence.readCacheLayer]'s three collections. */
internal data class CacheLayer(
    val results: Map<EnrichmentType, EnrichmentResult>,
    val uncachedTypes: Set<EnrichmentType>,
    val negativeCacheHits: Set<EnrichmentType>,
)

/**
 * The mutable state one [runProgressiveFanOut] call threads through
 * [DefaultEnrichmentEngine.resolveUncachedTypes] — [board] and [identityHolder] are written from
 * the moment a call starts; [identityResolution]/[resolvedRequest]/[chainExecutions] stay unset
 * (`null`/empty) unless [resolveUncachedTypes] returns normally, which is exactly
 * [runProgressiveFanOut]'s own completed/not-completed distinction.
 */
internal class RunSession(
    val board: SettlementBoard,
    val identityHolder: IdentityHolder,
) {
    /**
     * Set once identity resolution has settled; until then [identityResolution] is null and a
     * caller reading it knows resolution never ran (a timeout before it started).
     */
    var identityResolved: Boolean = false

    /**
     * This call's resolution, or null before it settles. Reads [identityHolder] rather than holding
     * a second copy: the holder is where a contradiction found later in the fan-out is applied, and
     * a snapshot taken from a private copy would report the status the resolver concluded instead
     * of what the call went on to learn.
     */
    val identityResolution: IdentityResolution?
        get() = if (identityResolved) identityHolder.current else null
    var resolvedRequest: EnrichmentRequest? = null

    /**
     * What identity resolution established about the name this call's fan-out searches, or `null`
     * when it established nothing about it. Read by [DefaultEnrichmentEngine.finalizeResult] and the
     * mergeable walk to stamp a name-searched result's [LookupProvenance]; see [observedProvenance]
     * for why this is not the call's [CanonicalStatus].
     */
    var nameEvidence: LookupProvenance? = null
    var chainExecutions: Map<EnrichmentType, ChainExecution> = emptyMap()

    /**
     * Types whose [DefaultEnrichmentEngine.finalizeResult] turned a `Success` into `NotFound` by
     * catalog filtering this call — whether that `Success` was this call's own live answer or a
     * stale-cache substitute re-filtered on the way out. Read by [CachePersistence.writeBack]
     * so that emptiness, which describes the local catalog rather than a provider, is never mistaken
     * for a live "providers had nothing" answer. Concurrent: different types settle from different
     * coroutines, and each writes only its own type, but the set itself must survive that without
     * tearing.
     */
    val filterEmptied: MutableSet<EnrichmentType> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Types whose finalized result this call is a [CachePersistence.applyStaleCacheToType]
     * substitute (set in [DefaultEnrichmentEngine.finalizeResult]), or a
     * [DefaultEnrichmentEngine.synthesizeComposite] output derived from a dependency in this same
     * set (propagated there, since [SettlementBoard.await] hands a composite its dependency's
     * finalized value with no marker of its own for where that value came from). A stale substitute
     * is a past call's snapshot, not this call's own answer — [CachePersistence.writeBack]
     * must not negative-cache a `NotFound` that descends from one. Concurrent for the same reason
     * as [filterEmptied].
     */
    val staleDerived: MutableSet<EnrichmentType> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()
}

/** [DefaultEnrichmentEngine.streamResolveTypes]'s per-call facts, bundled to keep its parameter list short. */
internal data class ResolveContext(
    val board: SettlementBoard,
    val request: EnrichmentRequest,
    val identityResult: EnrichmentResult?,
    val canonicalStatus: CanonicalStatus,
    val session: RunSession,
    /**
     * Types already settled on [board] before the fan-out — cache hits and identity fast-path
     * payloads. The dependency closure must not relaunch one: a composite consumes it through
     * [SettlementBoard.await] regardless, so a relaunch is only a second upstream spend whose
     * write-back overwrites the settled entry.
     */
    val preSettled: Set<EnrichmentType>,
)

/**
 * The best-known [IdentityResolution] for one [DefaultEnrichmentEngine.enrichProgressive] run, read
 * by every emission and by [DefaultEnrichmentEngine.finalizeResult]'s stale-cache/provenance steps.
 * Written once, by the single coroutine that runs identity resolution, before any settlement that
 * depends on it can start — [Volatile] is the visibility guarantee for the concurrent per-type
 * settlements that read it afterward without taking [SettlementBoard]'s lock.
 */
internal class IdentityHolder(@Volatile private var resolution: IdentityResolution) {

    /**
     * Set once a provider reports that a supplied identifier named a different entity
     * ([SuppliedIdentifierContradiction]). Latching: a later provider recovering by name does not
     * make the identifier good again.
     */
    @Volatile
    var contradicted: Boolean = false

    /**
     * The resolution as reported, with [CanonicalStatus.CONTRADICTED] outranking whatever the
     * resolver concluded. A request carrying a usable name recovers by searching it, so the status
     * would otherwise read `RESOLVED` and hide the bad identifier — the one fact on this call the
     * caller cannot learn any other way. Applied here rather than at each emission so every
     * snapshot, progressive and terminal, agrees without a second copy of the rule.
     */
    var current: IdentityResolution
        get() = if (contradicted) resolution.copy(status = CanonicalStatus.CONTRADICTED) else resolution
        set(value) { resolution = value }
}

internal class DefaultEnrichmentEngine(
    private val registry: ProviderRegistry,
    override val cache: EnrichmentCache,
    internal val config: EnrichmentConfig,
    private val logger: EnrichmentLogger = EnrichmentLogger.NoOp,
    mergers: List<ResultMerger> = listOf(GenreMerger),
    synthesizers: List<CompositeSynthesizer> = listOf(TimelineSynthesizer),
    /** The dispatcher [detachedScope] runs on — injectable so a test can swap it; never used elsewhere. */
    detachedDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : EnrichmentEngine {

    private val mergers: Map<EnrichmentType, ResultMerger> = mergers.associateBy { it.type }
    private val synthesizers: Map<EnrichmentType, CompositeSynthesizer> = synthesizers.associateBy { it.type }

    private val mergeableTypes: Set<EnrichmentType> get() = mergers.keys

    /** Every cache read and write this engine makes, and the rules deciding which of them happen. */
    internal val cachePersistence = CachePersistence(cache, config, logger)

    /**
     * The composite dependency graph, read from each synthesizer exactly once and never again.
     *
     * A `val`, not a `get()`: [CompositeSynthesizer.dependencies] is a property a consumer
     * implements, so nothing stops it answering differently on two reads. [compositeSubTypesOf]
     * builds the [SettlementBoard]'s key set from this graph and [streamResolveTypes] schedules
     * against it, and a board built from one answer while the scheduler works from another is a
     * `board.await()` on a key that does not exist. Snapshotting here is what makes "one graph"
     * true of a run rather than only of the source line that computes it.
     */
    private val compositeDependencies: Map<EnrichmentType, Set<EnrichmentType>> =
        this.synthesizers.mapValues { it.value.dependencies.toSet() }

    init {
        // Checked against the snapshot above, not against a fresh read of the synthesizers: a graph
        // validated in one read and used from another is not the graph that was validated.
        requireAcyclic(compositeDependencies)
        requireDisjointRoles(compositeDependencies.keys, this.mergers.keys)
    }

    // Complete-and-cache: a collector cancelling enrichProgressive() detaches from the fan-out
    // already in flight, which keeps running here to completion (and still writes back) instead of
    // being cancelled with the collector. SupervisorJob so one detached run's own failure never
    // cancels a sibling sharing this scope. Owned by, and scoped to, this engine instance — never
    // GlobalScope — so close() has something concrete to cancel; see close()'s KDoc for what
    // never calling it costs. progressiveRuns is the dedupe/lifecycle bookkeeping for what runs on
    // it — a second enrichProgressive() call whose requested types and forceRefresh match an
    // in-flight run's key attaches to it instead of starting a second fan-out; forceRefresh is part
    // of that key, not a filter on top of it, so a forced call never attaches to an unforced run's
    // data and vice versa — see progressiveDedupeKey.
    // The handler is the backstop for the rare case a detached run's failure is genuinely uncaught
    // (nothing deeper in the chain already caught and logged it) and no collector is left attached
    // to relay it anywhere — without this it would otherwise reach the platform's default
    // uncaught-exception handler instead of this engine's own logger. Untested: see
    // VERIFICATION.md "Known gaps".
    private val detachedScope = CoroutineScope(
        SupervisorJob() + detachedDispatcher +
            CoroutineExceptionHandler { _, throwable ->
                logger.warn(TAG, "Uncaught exception in a detached enrichProgressive run", throwable)
            },
    )
    internal val progressiveRuns = ProgressiveRunRegistry(detachedScope)

    /**
     * The detached-run dedupe map's current size — read by tests for eviction. Test-only internal
     * accessor, not a public API: eviction is a memory-boundedness invariant, and a subsequent
     * call's own correctness doesn't prove the map stayed bounded, so there is no externally
     * observable proxy for what this reads directly.
     */
    internal suspend fun inFlightDetachedRunCount(): Int = progressiveRuns.inFlightCount()

    /**
     * Releases the scope backing [enrichProgressive]'s complete-and-cache detachment. Any detached
     * run still in flight is abandoned: it stops at its next suspension point, and — like a timed-
     * out run — writes nothing back. A collector still attached to that run (via
     * [enrichProgressive]'s relay) receives one final snapshot of whatever had settled and then
     * completes, rather than hanging on a fan-out that will never reach its own terminal.
     *
     * This is a hard shutdown, not a drain: it does not wait for in-flight work to finish. Never
     * called automatically. Skipping it costs at most one shared dispatcher (`Dispatchers.Default`,
     * not a dedicated thread pool) plus whatever detached runs [enrichProgressive]'s dedupe has not
     * yet coalesced away — bounded by how many distinct request/types/forceRefresh keys are
     * genuinely in flight at once, never by how many times a collector cancelled and re-called for
     * the *same* key.
     *
     * A call for a request/types/forceRefresh key not already in flight when this runs never starts
     * a fan-out at all: [ProgressiveRunRegistry.attachOrStart] answers it directly with every
     * requested type present — a type the cache already holds keeps its real result, and only the
     * genuinely uncached remainder is stamped `Error(ErrorKind.ENGINE_CLOSED)` — the same per-type
     * completeness `enrich()` documents for a timeout.
     */
    override fun close() {
        // markClosed is suspend only because it shares attachOrStart's mutex; close() itself must
        // stay a plain fun to satisfy EnrichmentEngine's interface. runBlocking here blocks only on
        // that mutex, and attachOrStart's own critical section is the registration decision alone —
        // never the onClosed abandonment work that decision leads to, which can suspend on consumer
        // code (see attachOrStart's KDoc) — so this returns as soon as any in-progress attachOrStart
        // call finishes deciding, never blocked on that call's own suspending work. That bound is
        // what makes "mark closed" and "check closed, then register" unable to interleave: either
        // fully completes before this call returns, or fully starts after.
        runBlocking { progressiveRuns.markClosed() }
        detachedScope.cancel()
    }

    override suspend fun enrich(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean,
    ): EnrichmentResults = enrichProgressive(request, types, forceRefresh).last()

    // CONFLATED sits outside channelFlow's own (rendezvous by default) channel so a slow collector
    // never makes the producer wait to send the next settlement — a naive channelFlow default here
    // reaches back into provider fan-out and slows every call down, streamed or not, since enrich()
    // now runs through this same path.
    //
    // Complete-and-cache: cancelling collection of the returned Flow detaches the collector from
    // the fan-out already in flight — that fan-out is a child of [detachedScope], not of this
    // channelFlow producer, so cancelling here never reaches it. Bounded work may continue briefly
    // after a cancelled collection: until the in-flight run for this request/types/forceRefresh key
    // completes or times out, whichever comes first; a later, distinct call never joins more than
    // one such run. The write-back still happens, so a subsequent equivalent call is typically a
    // cache hit — see [close] for what happens to a run still in flight when the engine itself is
    // shut down.
    @OptIn(ExperimentalCoroutinesApi::class) // transformWhile, used only to relay a detached run
    override fun enrichProgressive(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean,
    ): Flow<EnrichmentResults> = channelFlow {
        val cacheLayer = cachePersistence.readCacheLayer(request, types, forceRefresh)
        if (cacheLayer.uncachedTypes.isEmpty()) {
            // Disabled identity resolution outranks a cache hit as the reason nothing was
            // attempted, matching resolveUncachedTypes's own precedence for the live path — the
            // same engine config must answer identically whether the cache was warm or cold.
            val cacheHitStatus = if (config.enableIdentityResolution) {
                CanonicalStatus.NOT_ATTEMPTED_CACHE_HIT
            } else {
                CanonicalStatus.NOT_ATTEMPTED_DISABLED
            }
            val identity = IdentityResolution(request.identifiers, cacheHitStatus)
            // A partial cache hit re-runs catalog filtering per type via settle()/finalizeResult
            // below; a full cache hit must do the same rather than replay whatever
            // EnrichmentResult.Success.isCatalogDegraded happened to be true at write time — that
            // value is call-scoped, not a stored fact (see its KDoc), so a since-recovered or
            // since-failed CatalogProvider must be reflected on every read, not just a partial one.
            val filtered = cacheLayer.results.mapValues { (type, result) ->
                applyCatalogFilteringToType(type, result, config.catalogProvider, config.catalogFilterMode, logger)
            }
            send(EnrichmentResults(filtered, types, identity))
            return@channelFlow
        }

        val run = attachOrStartDetachedRun(request, types, forceRefresh, cacheLayer)

        // Relay every snapshot the detached run produces, stopping once the terminal one has been
        // forwarded. Cancelling this collector cancels only this collect() — the detached run keeps
        // producing into run.shared regardless, so a later caller who re-issues the same call still
        // finds it (or its cache write-back) there.
        run.shared.transformWhile { snapshot ->
            emit(snapshot)
            !(run.terminal.isCompleted && snapshot === run.terminal.getCompleted())
        }.collect { snapshot -> send(snapshot) }
    }.buffer(Channel.CONFLATED)

    /**
     * The dedupe key for [enrichProgressive]'s detached-run coalescing: two calls whose *requested*
     * ([types]) types resolve to the same [entityKeyFor] set under the same [forceRefresh] attach to
     * the same run rather than starting a second fan-out. forceRefresh is baked into the key itself,
     * not checked separately, so a forced call can never attach to an unforced run's in-flight data
     * (it would get data it asked to bypass) — it only ever attaches to another forced call for the
     * same types, or starts its own new run.
     *
     * Keyed on the full [types], not [CacheLayer.uncachedTypes]: a run's terminal snapshot is shaped
     * by the requested types the call that *started* it passed in (see [runProgressiveFanOut]), and
     * every attached collector — [enrichProgressive]'s relay does no per-caller reshaping — gets that
     * same snapshot verbatim. Keying on the uncached set let two calls with genuinely different
     * `types` collide whenever cache state happened to make their *uncached* sets coincide (e.g. one
     * type already cached for a narrower call, still uncached for a wider one asking for it too) —
     * the narrower call would silently inherit types it never asked for, and the wider call could
     * come back missing a requested-but-cached type. Keying on `types` itself means two calls only
     * ever share a run when their requested sets are identical, so cache-state drift between two
     * differently-shaped calls now naturally starts two runs instead of colliding into one — that
     * was never a real coalescing win, only ever a bug waiting on the right cache state. Two calls
     * with identical `types` over the same cache state still coalesce exactly as before.
     *
     * Keyed on [types], not the full [request] — a shortcut: two requests naming the same entity two
     * different ways (an MBID-bearing request and a name-only request that resolves to it) get
     * separate runs even though their fan-out would answer the same cache keys either way. Safe
     * (never under-coalesces two genuinely different entities), just sometimes misses a coalescing
     * opportunity a full-identity key would catch.
     */
    private fun progressiveDedupeKey(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean,
    ): String = types.map { entityKeyFor(request, it) }.sorted().joinToString("|") + "#force=$forceRefresh"

    /**
     * Attaches to an in-flight detached run for this key, or starts one, via [progressiveRuns].
     * Only the call that actually starts a new run performs [forceRefresh]'s invalidate pass — a
     * call that is about to attach to an existing run must not invalidate, or its invalidation can
     * race that run's own write-back and wipe data it just fetched —
     * [ProgressiveRunRegistry.attachOrStart]'s own contract is what guarantees only the starting
     * call's body runs.
     */
    private suspend fun attachOrStartDetachedRun(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean,
        cacheLayer: CacheLayer,
    ): ProgressiveRunRegistry.ProgressiveRun {
        val dedupeKey = progressiveDedupeKey(request, types, forceRefresh)
        return progressiveRuns.attachOrStart(
            dedupeKey,
            onClosed = { newRun -> abandonedSnapshot(request, types, cacheLayer, newRun) },
        ) { newRun ->
            if (forceRefresh) cachePersistence.invalidateForRefresh(request, types)
            runProgressiveFanOut(request, types, forceRefresh, cacheLayer, newRun)
        }
    }

    /**
     * Identity resolution, the identity fast-path settle, and [streamResolveTypes]'s fan-out — the
     * whole body [runProgressiveFanOut]'s deadline wraps. [session] carries what survives the deadline
     * back to the caller; its `identityResolution`/`resolvedRequest`/`chainExecutions` stay unset
     * until this returns normally.
     */
    internal suspend fun resolveUncachedTypes(
        request: EnrichmentRequest,
        forceRefresh: Boolean,
        cacheLayer: CacheLayer,
        session: RunSession,
        settle: suspend (EnrichmentType, EnrichmentResult, ChainExecution?) -> Unit,
    ) {
        // TransientIdentifierMarker: this call's record of which IdentifierRequirements a transient
        // left unresolved this run, read back by reclassifyTransientGap.
        // ProviderCallScope: this call's home for whatever a provider memoizes across the types of
        // one request, so nothing it holds can survive to answer the next call.
        // EnrichDeadline carries the budget down to DefaultHttpClient, so a 429 retry can decline to
        // sleep past this deadline — an expiry mid-fan-out loses every provider's in-flight work.
        withContext(
            EnrichDeadline(config.enrichTimeoutMs) + TransientIdentifierMarker() +
                ProviderCallScope() + ResolvedEntityNames() + SuppliedIdentifierContradiction(),
        ) {
            var identityResult: EnrichmentResult? = null
            val identityEnabled = config.enableIdentityResolution
            val identityNeeded =
                identityEnabled && needsIdentityResolution(request, cacheLayer.uncachedTypes, registry)
            val fastPathResults = mutableMapOf<EnrichmentType, EnrichmentResult>()
            val fastPathRemaining = cacheLayer.uncachedTypes.toMutableSet()
            val enrichedRequest = if (identityNeeded) {
                resolveIdentity(request, fastPathResults, fastPathRemaining)
                    .also { identityResult = it.second }.first
            } else request
            session.resolvedRequest = enrichedRequest

            // The canonical-name alias could not be invalidated above: the request named no entity
            // then, and the name it is aliased under is the one resolution just learned.
            if (forceRefresh && namesNoEntity(request) && !namesNoEntity(enrichedRequest)) {
                cachePersistence.invalidateResolvedNameAlias(enrichedRequest, cacheLayer.uncachedTypes)
            }
            // The same channel the name backfill reads, so the canonical names a consumer is handed
            // are the ones the fan-out was built from — no second resolution path.
            val resolution = buildIdentityResolution(
                identityResult,
                enrichedRequest,
                currentCoroutineContext()[ResolvedEntityNames]?.resolved(),
                notAttemptedStatus = when {
                    !identityEnabled -> CanonicalStatus.NOT_ATTEMPTED_DISABLED
                    !identityNeeded -> CanonicalStatus.NOT_ATTEMPTED_IDENTIFIER_TRUSTED
                    else -> CanonicalStatus.NOT_ATTEMPTED_NO_PROVIDER
                },
            )
            session.identityHolder.current = resolution
            session.identityResolved = true
            session.nameEvidence = identityNameEvidence(identityResult, request, enrichedRequest)

            for ((type, raw) in fastPathResults) settle(type, raw, null)

            // Canonical suggestions describe only MusicBrainz's own lookup, not a global admission
            // decision — every provider still gets its independent eligibility check inside
            // ProviderChain, including the missing-identifier skips session.chainExecutions below
            // records for the cache write-back.
            streamResolveTypes(
                ResolveContext(
                    session.board,
                    enrichedRequest,
                    identityResult,
                    resolution.status,
                    session,
                    preSettled = cacheLayer.results.keys + fastPathResults.keys,
                ),
                fastPathRemaining,
                settle,
            )
            session.chainExecutions = session.board.snapshotExecutions()
        }
    }

    /**
     * Logs a run that stopped before every requested type settled, keeping [logger] private from a
     * cross-file caller. Both reasons — the [EnrichmentConfig.enrichTimeoutMs] deadline and a fault
     * that escaped the fan-out — are the same fact to a reader of the log.
     *
     * `warn`, not a new `error` level: [EnrichmentLogger] carries only `debug` and `warn`, and
     * adding a third method to a public interface is a break this is not worth.
     */
    internal fun logRunStoppedEarly(reason: String, cause: Throwable? = null) =
        logger.warn(TAG, reason, cause)

    /**
     * [runProgressiveFanOut]'s backfill for a run that stopped before every requested type settled
     * — a timed-out deadline or an abandonment (the engine `close()`d, or
     * [ProgressiveRunRegistry.attachOrStart]'s immediately-abandoned case for a call arriving after
     * `close()`). Every type [board] never settled becomes the [errorFor] result for its type.
     */
    internal suspend fun stampStragglers(
        types: Set<EnrichmentType>,
        board: SettlementBoard,
        settle: suspend (EnrichmentType, EnrichmentResult, ChainExecution?) -> Unit,
        errorFor: (EnrichmentType) -> EnrichmentResult.Error,
    ) {
        val settledSoFar = board.snapshotResults()
        for (type in types) {
            if (type !in settledSoFar) settle(type, errorFor(type), null)
        }
    }

    /**
     * Every type a composite among [types] would resolve as a dependency, transitively — a
     * composite dependency that is itself a composite pulls in its own dependencies too, and so on.
     * The one place this graph is walked: both [SettlementBoard] construction
     * ([runProgressiveFanOut], [abandonedSnapshot]) and [streamResolveTypes]'s own scheduling call
     * this, so the two never see a different notion of "everything this call touches". Cycle-free
     * by construction — [EnrichmentEngine.Builder.build] refuses a cyclic [compositeDependencies]
     * graph before an engine carrying it can exist.
     */
    internal fun compositeSubTypesOf(types: Set<EnrichmentType>): Set<EnrichmentType> {
        val closure = mutableSetOf<EnrichmentType>()
        val frontier = ArrayDeque(types)
        while (frontier.isNotEmpty()) {
            val next = compositeDependencies[frontier.removeFirst()].orEmpty()
            for (dep in next) if (closure.add(dep)) frontier.add(dep)
        }
        return closure
    }

    /**
     * One type's whole post-processing pass, run inside [SettlementBoard.settle]'s lock: catalog
     * filtering, then provenance stamping, then stale-cache resolution — the order that lets a
     * `Success` demoted to `NotFound` by filtering flow untouched through stamping (which only
     * reads `Success`) and into stale-cache resolution (which only reads `Error`/`RateLimited`), so
     * the three never contend over the same result.
     *
     * A stale-cache substitution hands back a *different* `Success` object, read straight from the
     * cache rather than produced by this call's own filtering pass above — so it is run back through
     * [applyCatalogFilteringToType] before returning, the same normalize-at-entry every other serve
     * gets. Without this, a persisted `isCatalogDegraded = true` (written by a call whose
     * `CatalogProvider` threw) would replay verbatim on a call with no `CatalogProvider` configured
     * at all, which that field's own KDoc says should be structurally impossible.
     *
     * [raw] is not always this call's own live answer — [runProgressiveFanOut] also replays a cache
     * hit through this same function, so filtering can empty a `Success` this call never asked a
     * provider about at all. Either way, catalog filtering is never evidence a provider said
     * nothing: any time it turns a `Success` into `NotFound`, [session]'s [RunSession.filterEmptied]
     * records that so [CachePersistence.writeBack] never negative-caches it.
     */
    internal suspend fun finalizeResult(
        request: EnrichmentRequest,
        type: EnrichmentType,
        raw: EnrichmentResult,
        execution: ChainExecution?,
        session: RunSession,
    ): EnrichmentResult {
        if (currentCoroutineContext()[SuppliedIdentifierContradiction]?.seen() == true) {
            session.identityHolder.contradicted = true
        }
        val filtered = applyCatalogFilteringToType(type, raw, config.catalogProvider, config.catalogFilterMode, logger)
        if (raw is EnrichmentResult.Success && filtered is EnrichmentResult.NotFound) {
            session.filterEmptied.add(type)
        }
        val stamped = stampProvenanceOne(filtered, execution, session.nameEvidence)
        val stale = cachePersistence.applyStaleCacheToType(request, type, stamped)
        if (stale === stamped) return stale
        // stale !== stamped: the stale-cache substitution fired, so this is a *different* Success
        // read from a past call, not this call's live answer. Recorded unconditionally (not just
        // when re-filtering below empties it) so a composite synthesized from this dependency can
        // inherit the fact even when this type's own substitute stays a non-empty Success — and
        // this membership alone is what bars writeBack from negative-caching a NotFound the
        // re-filter produces.
        session.staleDerived.add(type)
        return applyCatalogFilteringToType(type, stale, config.catalogProvider, config.catalogFilterMode, logger)
    }

    override fun enrichBatch(
        requests: List<EnrichmentRequest>,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean,
    ): Flow<Pair<EnrichmentRequest, EnrichmentResults>> = flow {
        for (request in requests) {
            emit(request to enrich(request, types, forceRefresh))
        }
    }

    override suspend fun invalidate(request: EnrichmentRequest, type: EnrichmentType?) {
        val types = if (type != null) listOf(type) else EnrichmentType.entries
        // Resolved once for every type: an identifier-only request's alias key is the *canonical*
        // name, which nothing on the request carries, so it has to be asked for.
        val named = canonicallyNamed(request)
        for (t in types) cachePersistence.invalidateKeys(request, named, t)
    }

    override suspend fun isManuallySelected(request: EnrichmentRequest, type: EnrichmentType): Boolean =
        cache.isManuallySelected(entityKeyFor(request, type), type)

    override suspend fun markManuallySelected(request: EnrichmentRequest, type: EnrichmentType) {
        cache.markManuallySelected(entityKeyFor(request, type), type)
    }

    // search() has no deadline of its own, but its providers use the same typed HTTP calls, so
    // without a budget a 429 there retries against DefaultHttpClient's 120s standalone ceiling.
    // enrichTimeoutMs is the budget a consumer already stated for a call of this kind.
    override suspend fun search(request: EnrichmentRequest, limit: Int): List<SearchCandidate> =
        withContext(EnrichDeadline(config.enrichTimeoutMs)) { searchCandidates(request, limit) }

    private suspend fun searchCandidates(request: EnrichmentRequest, limit: Int): List<SearchCandidate> {
        val identity = registry.identityProvider()
        val primary = if (identity != null) {
            try {
                identity.searchCandidates(request, limit)
            } catch (e: Exception) {
                // ensureActive() throws only when *this* job is cancelled. A CancellationException
                // from elsewhere — a provider's own withTimeout — falls through and is handled as
                // the failure it is, rather than escaping to be reported as our deadline. Plain
                // `catch (CancellationException) { throw e }` gets that second case wrong. (#53)
                currentCoroutineContext().ensureActive()
                logger.warn(TAG, "Identity search failed: ${e.message}", e)
                emptyList()
            }
        } else emptyList()

        if (primary.size >= limit) return primary.take(limit)

        val remaining = limit - primary.size
        val supplemental = registry.searchProviders().flatMap { provider ->
            if (!provider.isAvailable) return@flatMap emptyList()
            try {
                provider.searchCandidates(request, remaining)
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive() // as above
                logger.warn(TAG, "Search failed for ${provider.id}: ${e.message}", e)
                emptyList()
            }
        }

        val primaryTitles = primary.map { "${it.title}:${it.artist}".lowercase() }.toSet()
        val unique = supplemental.filter {
            "${it.title}:${it.artist}".lowercase() !in primaryTitles
        }
        return (primary + unique).take(limit)
    }

    override fun getProviders(): List<ProviderInfo> = registry.providerInfos()

    /** The probe behind [com.landofoz.musicmeta.discoverMbidEntityType], which holds its contract. */
    internal suspend fun discoverEntityType(mbid: String): MusicBrainzEntityType? {
        val musicBrainz = registry.identityProvider() as? MusicBrainzProvider
            ?: error("No MusicBrainz identity provider is registered; nothing can resolve an MBID's type")
        // An ambient scope is the caller's own call, and joining it is the whole saving: a probe
        // whose entity that call has already looked up costs nothing. Installing one unconditionally
        // would hand every probe a cold memo instead.
        return if (currentCoroutineContext()[ProviderCallScope] != null) {
            musicBrainz.discoverEntityType(mbid)
        } else {
            withContext(ProviderCallScope()) { musicBrainz.discoverEntityType(mbid) }
        }
    }

    /**
     * [request] with the names identity resolution would fill in, for a request that names none —
     * the only way to reach the canonical-name alias [CachePersistence.writeBack] left behind, since the caller's
     * own request carries no name to derive it from.
     *
     * Costs the identity lookup, and only for an identifier-only request. A failure degrades to the
     * request as given: an alias that outlives one failed invalidation is recoverable, and throwing
     * out of `invalidate()` for a transient is not.
     */
    // SwallowedException: the degrade is the contract above; the exception has no second reader.
    @Suppress("SwallowedException")
    private suspend fun canonicallyNamed(request: EnrichmentRequest): EnrichmentRequest {
        if (!namesNoEntity(request)) return request
        val provider = registry.identityProvider() ?: return request
        val names = ResolvedEntityNames()
        return try {
            withContext(ProviderCallScope() + names) { provider.resolveIdentity(request) }
            request.withBackfilledNames(names.resolved())
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn(TAG, "Could not name an identifier-only request to invalidate its alias")
            request
        }
    }

    /** Returns the enriched request and the raw identity result (for composite type synthesis). */
    private suspend fun resolveIdentity(
        request: EnrichmentRequest,
        results: MutableMap<EnrichmentType, EnrichmentResult>,
        uncachedTypes: MutableSet<EnrichmentType>,
    ): Pair<EnrichmentRequest, EnrichmentResult?> {
        val provider = registry.identityProvider() ?: return request to null
        // The rethrow is not optional. An earlier note here claimed the bare catch was safe because
        // "cancellation re-asserts at the next suspension point" — that is not a guarantee. Kotlin
        // cancellation is cooperative: a suspend function may return without ever suspending again,
        // and this one only happened to be caught downstream by resolveTypes()'s coroutineScope.
        // It also logged a cancelled call as a provider failure. (#53)
        val result = try {
            provider.resolveIdentity(request)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn(TAG, "Identity resolution failed: ${e.message}", e)
            // Tells reclassifyTransientGap "skipped because this run's identity resolution
            // hiccupped" from "skipped because the request genuinely has none of these".
            currentCoroutineContext()[TransientIdentifierMarker]?.markAllConcreteIdentifiers()
            // Must not collapse to null: null identity means "not attempted" and reads as
            // confident. GENRE matches the type the identity provider itself reports under.
            // mapError is the one classifier: consumers key retry policy off ErrorKind, so an
            // AUTH or PARSE failure must not arrive here as UNKNOWN.
            return request to provider.mapError(EnrichmentType.GENRE, e).asRateLimitedIfThrottled()
        }
        if (result !is EnrichmentResult.Success) {
            logger.debug(TAG, "Identity resolution returned ${result::class.simpleName}")
            return request to result
        }

        val resolved = result.resolvedIdentifiers
        if (resolved == null) {
            logger.debug(TAG, "Identity resolution returned no resolvedIdentifiers")
            if (result.data is EnrichmentData.Metadata) {
                for (type in IDENTITY_TYPES) {
                    if (type in uncachedTypes && type !in mergeableTypes && result.data.answers(type)) {
                        results[type] = result.copy(type = type); uncachedTypes.remove(type)
                    }
                }
            }
            return request to result
        }

        logger.debug(
            TAG,
            "Identity resolved: mbid=${resolved.musicBrainzId}, " +
                "wikidataId=${resolved.wikidataId}, wpTitle=${resolved.wikipediaTitle}",
        )

        val mergedIds = EnrichmentIdentifiers(
            musicBrainzId = resolved.musicBrainzId ?: request.identifiers.musicBrainzId,
            musicBrainzReleaseGroupId =
            resolved.musicBrainzReleaseGroupId ?: request.identifiers.musicBrainzReleaseGroupId,
            wikidataId = resolved.wikidataId ?: request.identifiers.wikidataId,
            isrc = resolved.isrc ?: request.identifiers.isrc,
            barcode = resolved.barcode ?: request.identifiers.barcode,
            wikipediaTitle = resolved.wikipediaTitle ?: request.identifiers.wikipediaTitle,
            extra = request.identifiers.extra + resolved.extra,
        )

        if (result.data is EnrichmentData.Metadata) {
            // A type the identity payload does not answer is left in uncachedTypes on purpose, so
            // the provider chain still gets its turn at it rather than inheriting an empty Success.
            for (type in IDENTITY_TYPES) {
                if (type in uncachedTypes && type !in mergeableTypes && result.data.answers(type)) {
                    results[type] = EnrichmentResult.Success(
                        type = type,
                        provenance = result.provenance,
                        data = result.data,
                        provider = result.provider,
                        confidence = result.confidence,
                        resolvedIdentifiers = mergedIds,
                    )
                    uncachedTypes.remove(type)
                }
            }
        }

        // Backfill after mergedIds, into blank name fields only: a request built by
        // EnrichmentRequest.forTrackByMbid and its siblings carries an identifier and no name, and
        // every provider but MusicBrainz searches by name. See [withBackfilledNames] for why a name
        // the caller did supply is never overwritten.
        val names = currentCoroutineContext()[ResolvedEntityNames]?.resolved()
        return request.withIdentifiers(mergedIds).withBackfilledNames(names) to result
    }

    /**
     * The incremental seam [enrich] and [enrichProgressive] share: every type in [types] plus its
     * transitive composite dependencies ([compositeSubTypesOf]) is launched in one fan-out, with no
     * barrier between a composite and the types it depends on. A regular or mergeable type settles
     * [settle] as its own `launch {}` completes; a composite type is driven by exactly one coroutine
     * that [SettlementBoard.await]s its own dependencies — however many launches deep — and calls
     * [settle] for its own type once it has them, so it cannot be double-synthesized by two
     * dependents racing to check whether every dependency is in, and a composite whose own
     * dependencies are already fast settles as soon as they land rather than waiting on an unrelated
     * slow type. `coroutineScope` itself is the completion guarantee this replaces `joinAll()` with:
     * it does not return until every child launched below — regular, mergeable, and composite alike
     * — has completed, composites included, since a composite's `board.await()` keeps its coroutine
     * a live child of this scope until its own dependencies settle.
     */
    private suspend fun streamResolveTypes(
        context: ResolveContext,
        types: Set<EnrichmentType>,
        settle: suspend (EnrichmentType, EnrichmentResult, ChainExecution?) -> Unit,
    ) = coroutineScope {
        val board = context.board
        val request = context.request
        val identityResult = context.identityResult
        val canonicalStatus = context.canonicalStatus
        val session = context.session
        // A request identity resolution could not name — an identifier-only one whose identifier
        // MusicBrainz holds nothing under — reaches every provider with a blank title and artist.
        // The name-search providers are asked for nothing at all in that state, so they are not
        // asked: a type no identifier-keyed provider can serve is an honest NotFound, never a live
        // search for the empty string and never an Error.
        //
        // An album or track request with a blank artist is gated the same way. A name search
        // carrying no artist cannot identify an entity, so a provider that only searches by name
        // is asked nothing. This is the one place `identifierOnly` feeds every regular and
        // mergeable chain, so gating here covers every name-searching provider at once;
        // MusicBrainz is guarded in `MusicBrainzEnricher` instead, because its identity-resolution
        // call does not come through this chain.
        val identifierOnly = namesNoEntity(request) || artistBlanksNameSearch(request)

        // The one closure, matching SettlementBoard's own construction (runProgressiveFanOut,
        // abandonedSnapshot): everything this call's board can be awaited for. A type's role —
        // composite, mergeable, or regular — is decided from whether a synthesizer or merger is
        // registered for it, never from whether types (the caller's own request) named it, so a
        // composite dependency reached only transitively is classified exactly as it would be if
        // requested directly.
        // The closure re-adds a dependency that is also a requested type — and such a type may have
        // settled from cache or the identity fast path already, in which case its board deferred is
        // complete and a composite reads that settlement; launching it again would only spend
        // upstream and overwrite the settled value. The subtraction never touches the board's key
        // set, which was built from the full closure before anything settled.
        val allTypes = types + compositeSubTypesOf(types) - context.preSettled
        val compositeTypes = allTypes.filter { it in compositeDependencies }.toSet()
        val mergeableTypesInScope = (allTypes - compositeTypes).filter { it in mergeableTypes }.toSet()
        val regularTypes = allTypes - compositeTypes - mergeableTypesInScope

        for (type in regularTypes) {
            launch {
                val (result, execution) = resolveRegularType(request, type, identifierOnly)
                settle(type, result, execution)
            }
        }
        for (mergeType in mergeableTypesInScope) {
            launch {
                val (result, execution) =
                    resolveMergeableType(mergeType, request, identifierOnly, session.nameEvidence)
                settle(mergeType, result, execution)
            }
        }
        for (compositeType in compositeTypes) {
            launch {
                val (result, execution) =
                    synthesizeComposite(board, compositeType, identityResult, request, session)
                settle(compositeType, result, execution)
            }
        }
    }

    /** One regular (non-merge, non-composite) type's chain walk — [streamResolveTypes]'s first-tier fan-out. */
    private suspend fun resolveRegularType(
        request: EnrichmentRequest,
        type: EnrichmentType,
        identifierOnly: Boolean,
    ): Pair<EnrichmentResult, ChainExecution?> {
        val chain = registry.chainFor(type)
        val (result, execution) = chain?.resolveWithExecution(request, identifierOnly)
            ?: (EnrichmentResult.NotFound(type, "no_provider") to null)
        return reclassifyTransientGap(chain, request.identifiers, type, gate(result)) to execution
    }

    /** One mergeable type's collect-all walk and merge — [streamResolveTypes]'s first-tier fan-out. */
    private suspend fun resolveMergeableType(
        mergeType: EnrichmentType,
        request: EnrichmentRequest,
        identifierOnly: Boolean,
        nameEvidence: LookupProvenance?,
    ): Pair<EnrichmentResult, ChainExecution?> {
        val chain = registry.chainFor(mergeType)
        val (allResults, execution) = chain?.resolveAllWithExecution(request, identifierOnly) ?: (null to null)
        // Stamped per contributor before merging: a collect-all walk has no single winner for
        // ChainExecution.winningRequirement to name, so each contributor's own provider is asked
        // instead — the merger reads real observed provenance, never null.
        val filtered = allResults?.successes.orEmpty()
            .mapNotNull { gate(it) as? EnrichmentResult.Success }
            .map { stampContributorProvenance(it, chain, nameEvidence) }
        val merger = mergers[mergeType]
        val merged = if (merger == null) {
            EnrichmentResult.NotFound(mergeType, "no_merger")
        } else {
            // Also gated on the way out: a merger may return one of its inputs as-is, or merge to
            // nothing. Confidence is not re-filtered — the inputs already passed, and a merger's own
            // provider id is not a consumer's override key.
            demoteUnanswered(guardedStrategy(logger, mergeType, "merger") { merger.merge(filtered) })
        }
        // The merger sees only successes, so "nobody succeeded" reaches it as the same empty list
        // whether the providers had nothing or never answered. The chain's own failure tells those
        // apart — but only where no provider produced a Success at all, so a result this gate
        // dropped for confidence still counts as the chain having been answered. That is the test
        // [ProviderChain.resolve] applies to its own lastFailure.
        val outcome = if (merged is EnrichmentResult.NotFound && allResults?.successes.isNullOrEmpty()) {
            allResults?.failure ?: merged
        } else {
            merged
        }
        return reclassifyTransientGap(chain, request.identifiers, mergeType, outcome) to execution
    }

    /**
     * One composite type's synthesis, from its dependencies as [board] settled them — never from a
     * shared results map, which is what lets this run concurrently with unrelated types settling.
     */
    private suspend fun synthesizeComposite(
        board: SettlementBoard,
        compositeType: EnrichmentType,
        identityResult: EnrichmentResult?,
        request: EnrichmentRequest,
        session: RunSession,
    ): Pair<EnrichmentResult, ChainExecution?> {
        val dependencies = compositeDependencies[compositeType].orEmpty()
        val depSettled = dependencies.associateWith { board.await(it) }
        val depExecutions = depSettled.values.mapNotNull { it.execution }
        // A dependency's taint is set inside finalizeResult as it settles, before board.await
        // above can return it — but board.await hands back only the finalized EnrichmentResult,
        // with no record of where it came from, so the taint must be copied onto compositeType
        // here for writeBack to see it. `any`, not `all`, deliberately: one stale or
        // filter-emptied dependency among fresh ones still means the composite's answer rests on
        // a value that is not this call's own, so its NotFound proves no absence.
        if (dependencies.any { it in session.staleDerived }) session.staleDerived.add(compositeType)
        if (dependencies.any { it in session.filterEmptied }) session.filterEmptied.add(compositeType)
        // A composite type has no chain of its own, so this is the only ChainExecution it ever
        // earns — folding each dependency's skips in is what makes a skip-for-missing-identifier
        // visible to writeBack's identifierIncomplete check one layer up.
        val execution = ChainExecution(
            attemptedProviderIds = depExecutions.flatMap { it.attemptedProviderIds },
            skippedForMissingIdentifier = depExecutions
                .fold(emptyMap()) { acc, dep -> acc + dep.skippedForMissingIdentifier },
            skippedForOpenBreaker = depExecutions.flatMap { it.skippedForOpenBreaker },
        )
        val synthesizer = synthesizers[compositeType]
        val result = if (synthesizer == null) {
            EnrichmentResult.NotFound(compositeType, "no_composite_handler")
        } else {
            // TimelineSynthesizer returns Success even with no events, and CompositeSynthesizer is a
            // public extension point — a consumer's synthesizer has the same freedom.
            val depResults = depSettled.mapValues { it.value.result }
            demoteUnanswered(
                guardedStrategy(logger, compositeType, "synthesizer") {
                    synthesizer.synthesize(depResults, identityResult, request)
                },
            )
        }
        return result to execution
    }

    private fun filterByConfidence(result: EnrichmentResult): EnrichmentResult {
        if (result !is EnrichmentResult.Success) return result
        val override = config.confidenceOverrides[result.provider]
        val effective = override ?: result.confidence
        if (effective < config.minConfidence) {
            return EnrichmentResult.NotFound(result.type, result.provider)
        }
        return if (override != null) result.copy(confidence = override) else result
    }

    /** Both per-result gates, for anything arriving from a provider chain. */
    private fun gate(result: EnrichmentResult): EnrichmentResult = demoteUnanswered(filterByConfidence(result))

    /**
     * Turns a [type]'s `NotFound` into an `Error` when it rode on a provider skipped for an
     * identifier requirement that a transient — somewhere else in this run — left unresolved,
     * rather than genuinely absent. Independent of [ProviderChain.resolve]/
     * [ProviderChain.resolveAll]'s own return values on purpose: a chain can have skipped one
     * provider for an unresolved identifier while a *different*, unrelated provider in the same
     * chain was still eligible, ran, and returned its own genuine `NotFound` (e.g. Last.fm
     * alongside a skipped Wikipedia for `ALBUM_DESCRIPTION`) — the chain's own result would be
     * indistinguishable from "everyone genuinely had nothing" in that case.
     *
     * Benign race: [TransientIdentifierMarker.mark] and this read both run inside the same
     * `enrich()` call's `coroutineScope`, launched as sibling `async` children — a type resolved
     * concurrently with, but *before*, the mark that would have covered it can miss the mark and
     * fall through to a plain `NotFound` instead of `Error`. That degrades to a plain `NotFound`
     * instead of inventing a false `Error` for a genuinely-absent identifier.
     */
    private suspend fun reclassifyTransientGap(
        chain: ProviderChain?,
        identifiers: EnrichmentIdentifiers,
        type: EnrichmentType,
        result: EnrichmentResult,
    ): EnrichmentResult {
        if (result !is EnrichmentResult.NotFound || chain == null) return result
        val marker = currentCoroutineContext()[TransientIdentifierMarker] ?: return result
        val skipped = chain.skippedIdentifierRequirements(identifiers)
        return if (skipped.isNotEmpty() && marker.matches(skipped)) {
            EnrichmentResult.Error(
                type, "engine",
                "A prerequisite identifier lookup failed transiently this run",
                errorKind = ErrorKind.NETWORK,
            )
        } else {
            result
        }
    }

    /**
     * The second per-result gate: a `Success` whose payload does not [answers] the type it claims to
     * answer is a `NotFound` — empty, or, within the five types [EnrichmentData.Metadata] answers
     * field-by-field, one filling a different type's field. `PayloadAnswersTypeCoverageTest` pins
     * that a `Metadata` filled across those five answers every `EnrichmentType`, the rest leniently.
     * [filterByConfidence] cannot do this — confidence scores identification, and a perfect identity
     * match on an entity carrying no data scores 1.0. Sited here rather than in each provider because
     * every provider had the same hole. [answers] gates everything that can reach a consumer: chain
     * results, merger and synthesizer output, the identity fan-out, and both cache paths — on the
     * cache read as a *miss*, so the entry is refetched rather than pinned.
     */
    private fun demoteUnanswered(result: EnrichmentResult): EnrichmentResult =
        if (result is EnrichmentResult.Success && !result.data.answers(result.type)) {
            EnrichmentResult.NotFound(result.type, result.provider)
        } else {
            result
        }

    companion object {
        private const val TAG = "EnrichmentEngine"

        private val IDENTITY_TYPES = setOf(
            EnrichmentType.GENRE, EnrichmentType.LABEL, EnrichmentType.RELEASE_DATE,
            EnrichmentType.RELEASE_TYPE, EnrichmentType.COUNTRY,
        )

        /** @see entityKeyFor */
        fun entityKeyFor(request: EnrichmentRequest, type: EnrichmentType): String =
            com.landofoz.musicmeta.engine.entityKeyFor(request, type)

        /** @see entityKeyForName */
        fun entityKeyForName(request: EnrichmentRequest, type: EnrichmentType): String =
            com.landofoz.musicmeta.engine.entityKeyForName(request, type)
    }
}
