package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKey
import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderInfo
import com.landofoz.musicmeta.SearchCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The provider id every Discogs-sourced result carries. */
internal const val DISCOGS_ID = "discogs"

/**
 * Whether this process serves a publicly reachable instance, where a provider's terms bind us to
 * what a stranger may be shown rather than to what a local operator may look at.
 *
 * `DEMO_PUBLIC=1` and nothing else: a deployment that merely omits a key is not a posture, because
 * the next operator can export it.
 */
internal fun publicPostureEnabled(value: String?): Boolean = value == "1"

/**
 * One restriction a public instance applies, the `DEMO_PUBLIC_ALLOW` token that lifts it, and how
 * the startup line words it while it is in force. Declaration order is the order both lists in
 * that line are printed in.
 */
internal enum class PublicRelaxation(val token: String, val restriction: String) {
    LASTFM("lastfm", "Last.fm not registered (API ToS 2.7: public use needs prior written approval)"),
    LISTENBRAINZ("listenbrainz", "ListenBrainz personal token withheld (account-scoped)"),
    DISCOGS_IMAGES("discogs-images", "Discogs images withheld (Restricted Data)"),
    DISCOGS_CACHE("discogs-cache", "Discogs-sourced data expires after 6h (API ToU)"),
}

/** The `DEMO_PUBLIC_ALLOW` token that lifts every restriction at once. */
internal const val RELAX_ALL_TOKEN = "all"

/**
 * The `DEMO_PUBLIC_ALLOW` token that lifts nothing — the safe posture, named. It exists so an
 * operator can express "no relaxations" as a value a secret store will hold (an empty payload is
 * rejected) rather than the one thing the unknown-token guard would refuse: a typo.
 */
internal const val RELAX_NONE_TOKEN = "none"

/** What one `DEMO_PUBLIC_ALLOW` value asked for, and which of its tokens named nothing. */
internal data class ParsedRelaxations(
    val recognised: Set<PublicRelaxation>,
    val unknown: List<String>,
)

/**
 * `DEMO_PUBLIC_ALLOW` read as a comma-separated token list. Surrounding whitespace and case are
 * normalised — neither can be a typo — and an empty element is skipped, so a trailing comma is
 * not an error. Anything else lands in [ParsedRelaxations.unknown] for the caller to refuse.
 */
internal fun parsePublicRelaxations(value: String?): ParsedRelaxations {
    val byToken = PublicRelaxation.entries.associateBy { it.token }
    val recognised = linkedSetOf<PublicRelaxation>()
    val unknown = mutableListOf<String>()
    value.orEmpty().split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .forEach { token ->
            when {
                token == RELAX_ALL_TOKEN -> recognised.addAll(PublicRelaxation.entries)
                token == RELAX_NONE_TOKEN -> Unit
                byToken.containsKey(token) -> recognised.add(byToken.getValue(token))
                else -> unknown.add(token)
            }
        }
    return ParsedRelaxations(recognised, unknown)
}

/** What a caller prints before refusing to start over [unknown]. */
internal fun unknownRelaxationMessage(unknown: List<String>): String {
    val valid = (PublicRelaxation.entries.map { it.token } + RELAX_ALL_TOKEN + RELAX_NONE_TOKEN).joinToString(", ")
    return "DEMO_PUBLIC_ALLOW: unrecognised ${if (unknown.size == 1) "token" else "tokens"} " +
        "${unknown.joinToString(", ")}. Expected any of: $valid."
}

/**
 * Which restrictions this instance is under: whether it serves the public at all, and which
 * restrictions an operator lifted with `DEMO_PUBLIC_ALLOW`. Every question below answers "not
 * restricted" while [enabled] is false, so an unset `DEMO_PUBLIC` takes the same branches it
 * always did whatever `DEMO_PUBLIC_ALLOW` holds.
 */
internal data class PublicPosture(
    val enabled: Boolean,
    val relaxations: Set<PublicRelaxation> = emptySet(),
) {

    private fun restricts(relaxation: PublicRelaxation) = enabled && relaxation !in relaxations

    /** Last.fm's API terms require prior written approval for a public page (2.7). */
    val withholdsLastFm: Boolean get() = restricts(PublicRelaxation.LASTFM)

    val withholdsListenBrainzToken: Boolean get() = restricts(PublicRelaxation.LISTENBRAINZ)

    val withholdsDiscogsImages: Boolean get() = restricts(PublicRelaxation.DISCOGS_IMAGES)

    val capsDiscogsFreshness: Boolean get() = restricts(PublicRelaxation.DISCOGS_CACHE)

    /** Provider ids this instance declines to register whatever credentials the environment carries. */
    val unregisteredProviderIds: Set<String>
        get() = if (withholdsLastFm) setOf("lastfm") else emptySet()

    /**
     * Provider ids whose credential this instance drops on purpose. Superset of
     * [unregisteredProviderIds]: ListenBrainz still registers keyless, only its account-scoped
     * personal token is withheld. The startup report reads this so a deliberate withholding is
     * never printed as a missing key.
     */
    val withheldCredentialIds: Set<String>
        get() = unregisteredProviderIds + if (withholdsListenBrainzToken) setOf("listenbrainz") else emptySet()

    /**
     * One line for the startup log, naming what is still restricted and what an operator relaxed.
     * Both halves are always present: a posture that says only what it forbids cannot be read as
     * evidence of what it permits.
     */
    val notice: String
        get() {
            val restricted = PublicRelaxation.entries.filter { restricts(it) }.joinToString("; ") { it.restriction }
            val relaxed = PublicRelaxation.entries.filter { it in relaxations }.joinToString(", ") { it.token }
            return "DEMO_PUBLIC=1, restricted: ${restricted.ifEmpty { "nothing" }}. " +
                "Relaxed by DEMO_PUBLIC_ALLOW: ${relaxed.ifEmpty { "nothing" }}."
        }
}

/**
 * [keys] with every credential [posture] withholds removed, so the engine cannot register a
 * provider or authenticate a call the posture forbids. Returns [keys] unchanged for a posture
 * that is not enabled.
 */
internal fun ApiKeyConfig.underPublicPosture(posture: PublicPosture): ApiKeyConfig =
    if (!posture.enabled) {
        this
    } else {
        this
            .let { if (posture.withholdsLastFm) it.without(ApiKey.LASTFM_API_KEY) else it }
            .let { if (posture.withholdsListenBrainzToken) it.without(ApiKey.LISTENBRAINZ_USER_TOKEN) else it }
    }

/**
 * An [EnrichmentEngine] that never lets a Discogs image reach a caller: Discogs images are
 * Restricted Data under their API terms and are non-transferable, while the metadata beside them
 * is CC0 and stays. Wrapping the engine rather than filtering each response is what makes that a
 * property of the instance instead of a property of the endpoints that happen to exist today.
 *
 * Everything else forwards untouched — including [search], because no Discogs capability answers
 * `searchCandidates`.
 */
internal class PublicPostureEngine(private val delegate: EnrichmentEngine) : EnrichmentEngine {

    override val cache: EnrichmentCache get() = delegate.cache

    override suspend fun enrich(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean,
    ): EnrichmentResults = delegate.enrich(request, types, forceRefresh).withoutDiscogsImages()

    override fun enrichProgressive(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean,
    ): Flow<EnrichmentResults> =
        delegate.enrichProgressive(request, types, forceRefresh).map { it.withoutDiscogsImages() }

    override suspend fun search(request: EnrichmentRequest, limit: Int): List<SearchCandidate> =
        delegate.search(request, limit)

    override fun getProviders(): List<ProviderInfo> = delegate.getProviders()

    override suspend fun invalidate(request: EnrichmentRequest, type: EnrichmentType?) =
        delegate.invalidate(request, type)

    override suspend fun isManuallySelected(request: EnrichmentRequest, type: EnrichmentType): Boolean =
        delegate.isManuallySelected(request, type)

    override suspend fun markManuallySelected(request: EnrichmentRequest, type: EnrichmentType) =
        delegate.markManuallySelected(request, type)

    override fun close() = delegate.close()
}

private fun EnrichmentResults.withoutDiscogsImages(): EnrichmentResults =
    copy(raw = raw.mapValues { (_, result) -> result.withoutDiscogsImages() })

/**
 * The same result with every Discogs image removed. A Discogs primary image is replaced by the
 * best surviving alternative, re-attributed to the provider that image actually came from, so
 * losing the merge winner costs the artwork's ranking rather than the artwork itself. A type left
 * with no image at all becomes [EnrichmentResult.NotFound] rather than vanishing from the map —
 * an absent key reads as "still pending" to the streaming endpoint.
 */
private fun EnrichmentResult.withoutDiscogsImages(): EnrichmentResult {
    if (this !is EnrichmentResult.Success) return this
    val artwork = data as? EnrichmentData.Artwork ?: return this
    val kept = artwork.alternatives.orEmpty().filterNot { it.provider == DISCOGS_ID }
    if (provider != DISCOGS_ID) {
        if (kept.size == artwork.alternatives.orEmpty().size) return this
        return copy(data = artwork.copy(alternatives = kept.takeIf { it.isNotEmpty() }))
    }
    val promoted = kept.firstOrNull() ?: return EnrichmentResult.NotFound(type, DISCOGS_ID)
    return copy(
        provider = promoted.provider,
        data = EnrichmentData.Artwork(
            url = promoted.url,
            thumbnailUrl = promoted.thumbnailUrl,
            sizes = promoted.sizes,
            alternatives = kept.drop(1).takeIf { it.isNotEmpty() },
        ),
    )
}
