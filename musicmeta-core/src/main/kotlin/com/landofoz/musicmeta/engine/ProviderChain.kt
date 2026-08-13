package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentProvider
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.IdentifierRequirement
import com.landofoz.musicmeta.http.CircuitBreaker
import com.landofoz.musicmeta.http.RateLimitException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * What a whole chain had to say for a mergeable type: every provider's [EnrichmentResult.Success],
 * and separately the last [failure] any of them reported — or the chain's own, where an open breaker
 * meant nobody was asked. Both are reported because a caller merging [successes] cannot see what the
 * providers that contributed none of them did; a `null` [failure] alongside no successes is the one
 * case where every provider was asked and genuinely had nothing. Which of the two speaks for the
 * chain is the caller's decision, not this type's.
 */
internal data class ChainResults(
    val successes: List<EnrichmentResult.Success>,
    val failure: EnrichmentResult?,
)

/**
 * What actually happened while a chain walked its providers for one request: which providers were
 * called ([attemptedProviderIds]), which were skipped because the request lacked an identifier
 * their capability requires ([skippedForMissingIdentifier], keyed by provider id), and which were
 * skipped because their breaker was open ([skippedForOpenBreaker]). A provider excluded for
 * unavailability, or by [ProviderChain]'s identifier-only gate, is in none of these lists — it was
 * never in this chain's eligible set to begin with.
 *
 * [identifierIncomplete] is what a cache write-back checks before treating a chain's `NotFound` as
 * proof nobody had the data: a provider that was never asked cannot speak for the chain.
 *
 * [winningRequirement] is the [IdentifierRequirement] the provider that produced this walk's
 * `Success` declared for the type — provider execution evidence for
 * [com.landofoz.musicmeta.LookupProvenance]: a requirement is only satisfiable by actually consuming
 * the identifier it names, so it tells apart a canonical-id lookup from a name search even when the
 * request also carries identifiers that provider never touched. Null when no provider's `Success`
 * was captured this way, e.g. [resolveAllWithExecution]'s merged multi-provider walk.
 */
internal data class ChainExecution(
    val attemptedProviderIds: List<String>,
    val skippedForMissingIdentifier: Map<String, IdentifierRequirement>,
    val skippedForOpenBreaker: List<String>,
    val winningRequirement: IdentifierRequirement? = null,
) {
    val identifierIncomplete: Boolean get() = skippedForMissingIdentifier.isNotEmpty()
}

/**
 * The `RateLimited` a 429 owes the consumer. Every provider's broad `catch` runs `mapError` before
 * the engine sees anything, so the throttle arrives as an `Error` already; this is the one place it
 * is widened back out, keeping `mapError`'s published `Error` return type untouched.
 */
internal fun EnrichmentResult.asRateLimitedIfThrottled(): EnrichmentResult =
    if (this is EnrichmentResult.Error && errorKind == ErrorKind.RATE_LIMIT) {
        EnrichmentResult.RateLimited(type, provider, (cause as? RateLimitException)?.retryAfterMs)
    } else {
        this
    }

internal class ProviderChain(
    val type: EnrichmentType,
    private val providers: List<EnrichmentProvider>,
    private val circuitBreakers: Map<String, CircuitBreaker> =
        providers.associate { it.id to CircuitBreaker() },
    private val logger: EnrichmentLogger = EnrichmentLogger.NoOp,
) {
    /**
     * Collects ALL Success results from every eligible provider concurrently, and separately what
     * the providers that produced none of them did — see [ChainResults]. Discards the execution
     * ledger; callers that need it use [resolveAllWithExecution].
     */
    suspend fun resolveAll(
        request: EnrichmentRequest,
        identifierOnly: Boolean = false,
    ): ChainResults = resolveAllWithExecution(request, identifierOnly).first

    /**
     * [resolveAll], plus the [ChainExecution] the same walk produced — one ledger built alongside
     * the results, not a second pass over the providers.
     * Used for mergeable types (e.g. GENRE, ARTIST_PHOTO) where multiple providers contribute data.
     * Respects availability, identifier requirements, and circuit breaker checks.
     */
    suspend fun resolveAllWithExecution(
        request: EnrichmentRequest,
        identifierOnly: Boolean = false,
    ): Pair<ChainResults, ChainExecution> = coroutineScope {
        val missing = mutableMapOf<String, IdentifierRequirement>()
        val candidates = providers.filter { provider ->
            when {
                !provider.isAvailable -> false
                !hasRequiredIdentifiers(provider, request.identifiers) -> {
                    missing[provider.id] = requirementFor(provider)
                    false
                }
                else -> !identifierOnly || requiresIdentifier(provider)
            }
        }
        val (tripped, eligible) = candidates.partition { isTripped(it) }

        val outcomes = eligible.map { provider ->
            async {
                val breaker = circuitBreakers[provider.id]
                val result = try {
                    provider.enrich(request, type)
                } catch (e: Exception) {
                    EnrichmentResult.Error(type, provider.id, e.message ?: "Unknown error", e)
                }.asRateLimitedIfThrottled()
                // The one guard, and it is deliberately not a `catch (CancellationException)`.
                // ensureActive() throws only when *this* job is cancelled, so a cancelled caller
                // never records a breaker failure — while a CancellationException raised elsewhere
                // (a provider's own withTimeout expiring) stays a failure of that provider instead
                // of escaping to be misreported as the engine's deadline. It also covers a
                // consumer's provider that swallows the cancellation and returns an Error, which
                // no rethrow of ours could intercept. (#53)
                currentCoroutineContext().ensureActive()
                when (result) {
                    is EnrichmentResult.Success -> { breaker?.recordSuccess(); result }
                    is EnrichmentResult.NotFound -> { breaker?.recordSuccess(); null }
                    is EnrichmentResult.RateLimited -> {
                        // A failure, not a no-op: a throttled provider that records neither is a
                        // breaker that never opens under load, which is the collapse
                        // `bodyOrThrowTransient` throws to avoid (`docs/pitfalls.md` §4).
                        breaker?.recordFailure()
                        logger.debug(TAG, "${type.name}: ${provider.id} rate limited, skipping"); result
                    }
                    is EnrichmentResult.Error -> {
                        breaker?.recordFailure()
                        logger.debug(TAG, "${type.name}: ${provider.id} error: ${result.message}"); result
                    }
                }
            }
        }.awaitAll().filterNotNull()

        val successes = outcomes.filterIsInstance<EnrichmentResult.Success>()
        // The last failure, matching what `resolve` keeps: both walk the chain in priority order.
        val failure = outcomes.lastOrNull { it !is EnrichmentResult.Success }
        val results = ChainResults(successes, failure ?: outageOrNull(eligible.isNotEmpty(), tripped))
        val execution = ChainExecution(eligible.map { it.id }, missing, tripped.map { it.id })
        results to execution
    }

    /** [resolve], discarding the [ChainExecution] the same walk produced. */
    suspend fun resolve(
        request: EnrichmentRequest,
        identifierOnly: Boolean = false,
    ): EnrichmentResult = resolveWithExecution(request, identifierOnly).first

    /**
     * Whether a provider is in this chain's eligible set at all, and if not, which of the three
     * disjoint reasons: unavailable/identifier-only-filtered (never in the ledger — see
     * [ChainExecution]), a missing identifier, or an open breaker.
     */
    private sealed class Gate {
        object Ineligible : Gate()
        data class MissingIdentifier(val requirement: IdentifierRequirement) : Gate()
        object BreakerOpen : Gate()
        object Attempt : Gate()
    }

    private fun gateFor(
        provider: EnrichmentProvider,
        identifiers: EnrichmentIdentifiers,
        identifierOnly: Boolean,
    ): Gate = when {
        !provider.isAvailable -> Gate.Ineligible
        !hasRequiredIdentifiers(provider, identifiers) -> Gate.MissingIdentifier(requirementFor(provider))
        identifierOnly && !requiresIdentifier(provider) -> Gate.Ineligible
        isTripped(provider) -> Gate.BreakerOpen
        else -> Gate.Attempt
    }

    /**
     * Calls [provider], records the outcome against its breaker, and marks it attempted — the part
     * of [resolveWithExecution]'s walk that is the same regardless of what the caller does with the
     * result.
     */
    private suspend fun recordAttempt(
        provider: EnrichmentProvider,
        request: EnrichmentRequest,
        attempted: MutableList<String>,
    ): EnrichmentResult {
        val breaker = circuitBreakers[provider.id]
        val result = try {
            provider.enrich(request, type)
        } catch (e: Exception) {
            EnrichmentResult.Error(type, provider.id, e.message ?: "Unknown error", e)
        }.asRateLimitedIfThrottled()
        currentCoroutineContext().ensureActive() // see resolveAllWithExecution — the same single guard
        attempted.add(provider.id)
        if (result is EnrichmentResult.Success || result is EnrichmentResult.NotFound) {
            breaker?.recordSuccess()
        } else {
            breaker?.recordFailure()
        }
        return result
    }

    /** One provider's contribution to a [resolveWithExecution] walk: a `Success`, a failure, or neither. */
    private data class WalkStep(val success: EnrichmentResult.Success?, val failure: EnrichmentResult?)

    /** The three ledgers a [resolveWithExecution] walk accumulates, bundled to keep [walkStep] short. */
    private class WalkLedger {
        val attempted = mutableListOf<String>()
        val missing = mutableMapOf<String, IdentifierRequirement>()
        val tripped = mutableListOf<EnrichmentProvider>()
    }

    /**
     * [gateFor] one [provider], updates [ledger] in place, and — only for [Gate.Attempt] — calls it
     * via [recordAttempt]. Isolates [resolveWithExecution]'s own cyclomatic complexity to just its
     * loop and outcome assembly.
     */
    private suspend fun walkStep(
        provider: EnrichmentProvider,
        request: EnrichmentRequest,
        identifierOnly: Boolean,
        ledger: WalkLedger,
    ): WalkStep = when (val gate = gateFor(provider, request.identifiers, identifierOnly)) {
        Gate.Ineligible -> WalkStep(null, null)
        is Gate.MissingIdentifier -> { ledger.missing[provider.id] = gate.requirement; WalkStep(null, null) }
        Gate.BreakerOpen -> { ledger.tripped.add(provider); WalkStep(null, null) }
        Gate.Attempt -> when (val result = recordAttempt(provider, request, ledger.attempted)) {
            is EnrichmentResult.Success -> WalkStep(result, null)
            is EnrichmentResult.NotFound -> WalkStep(null, null)
            is EnrichmentResult.RateLimited, is EnrichmentResult.Error -> WalkStep(null, result)
        }
    }

    /**
     * Walks providers in priority order, returning the first `Success` and, alongside it, the
     * [ChainExecution] the walk built up to that point — the providers after a successful one were
     * never asked, and are absent from the ledger for the same reason they are absent from a
     * `NotFound` chain's result.
     *
     * Breaker state is read as the walk reaches each provider, not up front: a breaker these calls
     * share with another type's chain can open mid-walk, and the fresher answer is the honest one.
     */
    suspend fun resolveWithExecution(
        request: EnrichmentRequest,
        identifierOnly: Boolean = false,
    ): Pair<EnrichmentResult, ChainExecution> {
        val ledger = WalkLedger()
        var lastFailure: EnrichmentResult? = null
        var success: EnrichmentResult.Success? = null
        var winningProvider: EnrichmentProvider? = null

        for (provider in providers) {
            if (success != null) break
            val step = walkStep(provider, request, identifierOnly, ledger)
            if (step.success != null) { success = step.success; winningProvider = provider }
            if (step.failure != null) lastFailure = step.failure
        }

        val execution = ChainExecution(
            ledger.attempted,
            ledger.missing,
            ledger.tripped.map { it.id },
            winningRequirement = winningProvider?.let { requirementFor(it) },
        )
        val outcome = success
            ?: lastFailure
            ?: outageOrNull(ledger.attempted.isNotEmpty(), ledger.tripped)
            ?: EnrichmentResult.NotFound(type, "all_providers")
        return outcome to execution
    }

    /**
     * The `Error` owed to a chain that produced nothing because it never asked anyone: every
     * provider that could have answered was skipped for an open breaker. Without it the caller
     * reports a clean absence, and a consumer keying retry off [ErrorKind] reads an outage as
     * "this entity has no such data". `null` while any provider did run — one that answers has
     * spoken for the chain, and one skipped for an unmet identifier or a missing key is not an
     * outage.
     */
    private fun outageOrNull(
        answered: Boolean,
        tripped: List<EnrichmentProvider>,
    ): EnrichmentResult.Error? {
        if (answered || tripped.isEmpty()) return null
        return EnrichmentResult.Error(
            type, "all_providers",
            "Every provider for ${type.name} is in circuit-breaker cooldown " +
                "(${tripped.joinToString { it.id }}); the data was not looked up",
            errorKind = ErrorKind.NETWORK,
        )
    }

    /** Whether [provider]'s capability for [type] is keyed on an identifier rather than a name. */
    private fun requiresIdentifier(provider: EnrichmentProvider): Boolean =
        provider.capabilities.firstOrNull { it.type == type }
            ?.identifierRequirement
            ?.let { it != IdentifierRequirement.NONE } ?: false

    /** [provider]'s declared [IdentifierRequirement] for [type], or [IdentifierRequirement.NONE]. */
    private fun requirementFor(provider: EnrichmentProvider): IdentifierRequirement =
        provider.capabilities.firstOrNull { it.type == type }?.identifierRequirement
            ?: IdentifierRequirement.NONE

    /** Whether [provider]'s breaker is open. A provider with no breaker is never skipped. */
    private fun isTripped(provider: EnrichmentProvider): Boolean =
        circuitBreakers[provider.id]?.allowRequest() == false

    private fun hasRequiredIdentifiers(
        provider: EnrichmentProvider,
        identifiers: EnrichmentIdentifiers,
    ): Boolean {
        val capability = provider.capabilities.firstOrNull { it.type == type } ?: return true
        return when (capability.identifierRequirement) {
            IdentifierRequirement.NONE -> true
            IdentifierRequirement.MUSICBRAINZ_ID -> identifiers.musicBrainzId != null
            IdentifierRequirement.MUSICBRAINZ_RELEASE_GROUP_ID -> identifiers.musicBrainzReleaseGroupId != null
            IdentifierRequirement.WIKIDATA_ID -> identifiers.wikidataId != null
            IdentifierRequirement.WIKIPEDIA_TITLE -> identifiers.wikipediaTitle != null ||
                identifiers.wikidataId != null
            IdentifierRequirement.ANY_IDENTIFIER -> identifiers.musicBrainzId != null ||
                identifiers.musicBrainzReleaseGroupId != null ||
                identifiers.wikidataId != null ||
                identifiers.wikipediaTitle != null
        }
    }

    /**
     * Which [IdentifierRequirement]s caused >=1 provider in this chain to be skipped for
     * [identifiers], regardless of whether some *other* provider in the chain was eligible and ran.
     * Deliberately independent of [resolve] and [resolveAll]'s outcomes: a provider skipped for an
     * unresolved identifier is equally suspect whether the chain's overall result ends up `Success`
     * (another provider covered it), a genuine `NotFound` (another provider ran and had nothing —
     * e.g. Last.fm alongside a skipped Wikipedia for `ALBUM_DESCRIPTION`), or feeds a merger instead
     * of [resolve] directly. Availability and circuit-breaker state play no part — only the
     * identifier gate does.
     */
    fun skippedIdentifierRequirements(identifiers: EnrichmentIdentifiers): Set<IdentifierRequirement> =
        providers.mapNotNull { provider ->
            val requirement = provider.capabilities.firstOrNull { it.type == type }
                ?.identifierRequirement
                ?.takeIf { it != IdentifierRequirement.NONE }
                ?: return@mapNotNull null
            requirement.takeUnless { hasRequiredIdentifiers(provider, identifiers) }
        }.toSet()

    fun providers(): List<EnrichmentProvider> = providers

    private companion object {
        const val TAG = "ProviderChain"
    }
}
