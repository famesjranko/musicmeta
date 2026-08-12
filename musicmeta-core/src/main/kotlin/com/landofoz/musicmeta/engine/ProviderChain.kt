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

internal class ProviderChain(
    val type: EnrichmentType,
    private val providers: List<EnrichmentProvider>,
    private val circuitBreakers: Map<String, CircuitBreaker> =
        providers.associate { it.id to CircuitBreaker() },
    private val logger: EnrichmentLogger = EnrichmentLogger.NoOp,
) {
    /**
     * Collects ALL Success results from every eligible provider concurrently, and separately what
     * the providers that produced none of them did — see [ChainResults].
     * Used for mergeable types (e.g. GENRE, ARTIST_PHOTO) where multiple providers contribute data.
     * Respects availability, identifier requirements, and circuit breaker checks.
     */
    suspend fun resolveAll(
        request: EnrichmentRequest,
        identifierOnly: Boolean = false,
    ): ChainResults = coroutineScope {
        val (tripped, eligible) = providers
            .filter { couldAnswer(it, request.identifiers, identifierOnly) }
            .partition { isTripped(it) }

        val outcomes = eligible.map { provider ->
            async {
                val breaker = circuitBreakers[provider.id]
                val result = try {
                    provider.enrich(request, type)
                } catch (e: Exception) {
                    EnrichmentResult.Error(type, provider.id, e.message ?: "Unknown error", e)
                }
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
        ChainResults(successes, failure ?: outageOrNull(eligible.isNotEmpty(), tripped))
    }

    suspend fun resolve(
        request: EnrichmentRequest,
        identifierOnly: Boolean = false,
    ): EnrichmentResult {
        var lastFailure: EnrichmentResult? = null
        var answered = false
        val tripped = forEachEligible(request, identifierOnly) { _, breaker, result ->
            answered = true
            when (result) {
                is EnrichmentResult.Success -> { breaker?.recordSuccess(); return result }
                is EnrichmentResult.NotFound -> { breaker?.recordSuccess() }
                is EnrichmentResult.RateLimited -> { lastFailure = result }
                is EnrichmentResult.Error -> { breaker?.recordFailure(); lastFailure = result }
            }
        }
        return lastFailure
            ?: outageOrNull(answered, tripped)
            ?: EnrichmentResult.NotFound(type, "all_providers")
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

    /**
     * Iterates eligible providers, calling each and passing the result to [onResult].
     * Handles availability, identifier requirements, and circuit breaker checks.
     * Returns the providers skipped for an open breaker.
     */
    private suspend inline fun forEachEligible(
        request: EnrichmentRequest,
        identifierOnly: Boolean,
        onResult: (EnrichmentProvider, CircuitBreaker?, EnrichmentResult) -> Unit,
    ): List<EnrichmentProvider> {
        val tripped = mutableListOf<EnrichmentProvider>()
        for (provider in providers) {
            if (!couldAnswer(provider, request.identifiers, identifierOnly)) continue
            // Read as the walk reaches each provider, not up front: a breaker these calls share with
            // another type's chain can open mid-walk, and the fresher answer is the honest one.
            if (isTripped(provider)) { tripped.add(provider); continue }

            val breaker = circuitBreakers[provider.id]
            val result = try {
                provider.enrich(request, type)
            } catch (e: Exception) {
                EnrichmentResult.Error(type, provider.id, e.message ?: "Unknown error", e)
            }
            currentCoroutineContext().ensureActive() // see resolveAll — the same single guard

            onResult(provider, breaker, result)
        }
        return tripped
    }

    /**
     * Whether [provider] is in a position to answer at all, leaving its breaker out of it.
     *
     * [identifierOnly] narrows that to the providers whose capability for [type] is keyed on an
     * identifier — for a request that names no entity, where a name-search provider has nothing to
     * search with. Asking one anyway spends a live request on the empty string and lets whatever
     * ranks first for it answer as this request's entity.
     */
    private fun couldAnswer(
        provider: EnrichmentProvider,
        identifiers: EnrichmentIdentifiers,
        identifierOnly: Boolean = false,
    ): Boolean =
        provider.isAvailable &&
            hasRequiredIdentifiers(provider, identifiers) &&
            (!identifierOnly || requiresIdentifier(provider))

    /** Whether [provider]'s capability for [type] is keyed on an identifier rather than a name. */
    private fun requiresIdentifier(provider: EnrichmentProvider): Boolean =
        provider.capabilities.firstOrNull { it.type == type }
            ?.identifierRequirement
            ?.let { it != IdentifierRequirement.NONE } ?: false

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
