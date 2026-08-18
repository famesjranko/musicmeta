package com.landofoz.musicmeta.testkit

import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.ProviderInfo

/**
 * Every (provider, declared capability) pair on a built engine. Read from
 * [EnrichmentEngine.getProviders], so a provider added tomorrow is covered without editing a test.
 *
 * The pair carries [ProviderInfo], not the provider: [ProviderInfo] is a flattened snapshot holding
 * no reference back to the `EnrichmentProvider`, which lives behind a `private val` on the
 * `internal` engine implementation and is reachable from no test. Filter a subject list on
 * [ProviderInfo.isAvailable] to skip a provider that is registered but has no key.
 *
 * **Not `ProviderCatalog.entries`** — its own KDoc calls itself "a static description, not a live
 * one" and names `getProviders` as "the authority for what *is* registered". A hand-written provider
 * list inside a test is the same defect the contract pattern exists to remove, one level up.
 */
internal fun eachProviderCapability(
    engine: EnrichmentEngine,
): List<Pair<ProviderInfo, ProviderCapability>> =
    engine.getProviders().flatMap { info -> info.capabilities.map { info to it } }
