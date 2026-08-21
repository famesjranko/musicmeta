package com.landofoz.musicmeta.demoweb

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
 * Provider ids a public instance never registers, whatever credentials the environment carries.
 * Last.fm's API terms require prior written approval for a public page (2.7).
 */
internal val PUBLIC_UNREGISTERED_PROVIDER_IDS = setOf("lastfm")

/**
 * Provider ids whose credential a public instance drops on purpose. Superset of
 * [PUBLIC_UNREGISTERED_PROVIDER_IDS]: ListenBrainz still registers keyless, only its
 * account-scoped personal token is withheld. The startup report reads this so a deliberate
 * withholding is never printed as a missing key.
 */
internal val PUBLIC_WITHHELD_CREDENTIAL_IDS = PUBLIC_UNREGISTERED_PROVIDER_IDS + "listenbrainz"

/** One line, printed at startup, naming every provider a public instance limits and why. */
internal const val PUBLIC_POSTURE_NOTICE =
    "DEMO_PUBLIC=1: Last.fm not registered (API ToS 2.7 — public use needs prior written approval); " +
        "ListenBrainz personal token withheld (account-scoped); Discogs images withheld " +
        "(Restricted Data) and Discogs-sourced data expires after 6h (API ToU)."

/**
 * [keys] with every credential a public instance withholds removed, so the engine cannot register
 * a provider or authenticate a call the posture forbids. Returns [keys] unchanged when [enabled]
 * is false.
 */
internal fun ApiKeyConfig.underPublicPosture(enabled: Boolean): ApiKeyConfig =
    if (!enabled) this else copy(lastFmKey = null, listenBrainzToken = null)

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
