package com.landofoz.musicmeta.testkit

import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.ProviderInfo

/**
 * Every (provider, declared capability) pair on a built engine. Read from
 * [EnrichmentEngine.getProviders], so a provider added tomorrow is covered without editing a test.
 *
 * **The return type here is `ProviderInfo`, correcting `architecture.md` § 2 P6's earlier claim.**
 * That claim said `engine.providers` was public and typed `List<EnrichmentProvider>`, citing an
 * committed api-dump line — which is `getProviders ()Ljava/util/List;`, an erased JVM descriptor naming no
 * element type, read as though it were a declaration. The declaration is
 * `fun getProviders(): List<ProviderInfo>` at `EnrichmentEngine.kt:94`, and [ProviderInfo]
 * (`:324-331`) is a flattened snapshot — `id`, `displayName`, `capabilities`, `requiresApiKey`,
 * `isAvailable`, `isEnabled` — holding no reference back to the `EnrichmentProvider` instance, which
 * lives behind a `private val` on the `internal` engine implementation and is reachable from no test.
 *
 * [ProviderInfo] is the better subject anyway: `isEnabled` and `isAvailable` let a child filter
 * honestly rather than asserting against providers that are registered but inert.
 *
 * **Not `ProviderCatalog.entries`** — its own KDoc calls itself "a static description, not a live
 * one" and names `getProviders` as "the authority for what *is* registered". A hand-written provider
 * list inside a test is the same defect the contract pattern exists to remove, one level up.
 */
internal fun eachProviderCapability(
    engine: EnrichmentEngine,
): List<Pair<ProviderInfo, ProviderCapability>> =
    engine.getProviders().flatMap { info -> info.capabilities.map { info to it } }
