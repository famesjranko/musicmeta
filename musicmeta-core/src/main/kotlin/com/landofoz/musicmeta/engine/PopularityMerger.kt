package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType

/**
 * Keeps every popularity source instead of the first one that answers.
 *
 * Without it `ProviderChain.resolve` returns on the first `Success`, and the losing provider's
 * payload is discarded whole — not as a duplicate number, but field by field. Last.fm carries
 * listeners and plays, ListenBrainz carries listen and user counts, and MusicBrainz carries a
 * community rating and no counts at all, so whichever won left the others' fields null and a
 * consumer rendering "Listener count unavailable" was reading a gap the losing provider had filled.
 *
 * Two outputs, and the distinction is the point. `signals` is every source's own claim, unblended
 * and never summed — a scrobble count, a listen count and a 1–5 rating are different units. The
 * flat fields are the derived convenience: each takes the first source that reported it, walking
 * providers in chain (priority) order, so a caller wanting one number still gets the best-ranked
 * source's rather than an average of incomparable things.
 */
internal class PopularityMerger(override val type: EnrichmentType) : ResultMerger {

    override fun merge(results: List<EnrichmentResult.Success>): EnrichmentResult {
        if (results.isEmpty()) return EnrichmentResult.NotFound(type, "all_providers")

        val payloads = results.mapNotNull { it.data as? EnrichmentData.Popularity }
        if (payloads.isEmpty()) return results.first()

        val merged = EnrichmentData.Popularity(
            listenCount = payloads.firstNotNullOfOrNull { it.listenCount },
            listenerCount = payloads.firstNotNullOfOrNull { it.listenerCount },
            rank = payloads.firstNotNullOfOrNull { it.rank },
            topTracks = payloads.firstNotNullOfOrNull { it.topTracks?.takeIf(List<*>::isNotEmpty) },
            signals = payloads.flatMap { it.signals },
        )

        return EnrichmentResult.Success(
            type = type,
            data = merged,
            provider = "popularity_merger",
            confidence = results.maxOf { it.confidence },
            resolvedIdentifiers = results.firstNotNullOfOrNull { it.resolvedIdentifiers },
        )
    }
}
