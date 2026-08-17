package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.KeyRequirement
import com.landofoz.musicmeta.ProviderCatalog
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import java.io.File
import java.util.concurrent.atomic.AtomicReference

private const val CONTACT = "https://github.com/famesjranko/musicmeta"

/**
 * Where [main] reads one [ApiKeyConfig] field from — the `secrets.properties` key, then the env var
 * as fallback — joined to the [ProviderCatalog] id whose `KeyRequirement` selector reads that same
 * field. The single place these names live: the startup missing-key message reads [envVar] off
 * whichever entries the catalog's own selector calls missing, rather than re-deriving the list.
 */
internal class KeySpec(val catalogId: String, val secretsKey: String, val envVar: String)

private val LASTFM = KeySpec("lastfm", "lastfm.apikey", "LASTFM_API_KEY")
private val FANARTTV = KeySpec("fanarttv", "fanarttv.apikey", "FANARTTV_API_KEY")
private val DISCOGS = KeySpec("discogs", "discogs.token", "DISCOGS_TOKEN")
private val LISTENBRAINZ = KeySpec("listenbrainz", "listenbrainz.token", "LISTENBRAINZ_TOKEN")

internal val KEY_SPECS = listOf(LASTFM, FANARTTV, DISCOGS, LISTENBRAINZ)

private fun KeySpec.resolve(secrets: Map<String, String>) = secrets[secretsKey] ?: env(envVar)

fun main() {
    val port = env("PORT")?.toIntOrNull() ?: 8099
    val secrets = loadSecrets()
    val keys = ApiKeyConfig(
        lastFmKey = LASTFM.resolve(secrets),
        fanartTvProjectKey = FANARTTV.resolve(secrets),
        discogsPersonalToken = DISCOGS.resolve(secrets),
        listenBrainzToken = LISTENBRAINZ.resolve(secrets),
    )
    val cache = InMemoryEnrichmentCache()

    fun buildEngine(cacheMode: CacheMode): EnrichmentEngine {
        // This demo exists to show what every provider returns, so a type dropped to meet the
        // library's 30s default deadline is the failure, not a slow answer — and 30s can't hold
        // that once MusicBrainz's one-request-a-second limiter serialises concurrent cold
        // enrichments into a single queue, so wall clock scales with simultaneous visitors while
        // the deadline doesn't. 120s is headroom for that, not a measured figure.
        val config = EnrichmentConfig(
            enrichTimeoutMs = 120_000L,
            cacheMode = cacheMode,
        )
        return EnrichmentEngine.Builder()
            .apiKeys(keys)
            .config(config)
            .cache(cache)
            .contact(CONTACT)
            .withDefaultProviders()
            .build()
    }

    // The same shared `cache` above every rebuild passes to `buildEngine` is what makes the
    // toggle honest: swapping the engine held here does not clear it, so a STALE_IF_ERROR swap
    // has entries a NETWORK_FIRST run already warmed to serve as fallbacks. `withDefaultProviders()`
    // does rebuild per-host rate limiters and circuit breakers fresh on every swap, though — those
    // do not survive.
    val engineRef = AtomicReference(buildEngine(CacheMode.NETWORK_FIRST))
    val cacheModeRef = AtomicReference(CacheMode.NETWORK_FIRST)

    // Missingness reads the catalog's own selector against the built config, not the raw sources —
    // Required and Optional are different failures. A missing Required key means the provider never
    // registered at all; a missing Optional token (ListenBrainz) means it registered and answers
    // everything except artist radio discovery, so lumping it in with "skipped" would be false.
    val catalogById = ProviderCatalog.entries.associateBy { it.id }
    val missingRequired = KEY_SPECS.filter { spec ->
        val requirement = catalogById.getValue(spec.catalogId).keyRequirement
        requirement is KeyRequirement.Required && requirement.key(keys) == null
    }.map { it.envVar }
    val missingOptional = KEY_SPECS.filter { spec ->
        val requirement = catalogById.getValue(spec.catalogId).keyRequirement
        requirement is KeyRequirement.Optional && requirement.key(keys) == null
    }.map { it.envVar }
    if (missingRequired.isNotEmpty()) {
        println(
            "No key set for: ${missingRequired.joinToString(", ")} — those providers are skipped. " +
                "See secrets.properties / README.",
        )
    }
    if (missingOptional.isNotEmpty()) {
        println(
            "No token set for: ${missingOptional.joinToString(", ")} — registered, but artist radio " +
                "discovery is off. See secrets.properties / README.",
        )
    }

    startServer(engineRef, cacheModeRef, ::buildEngine, keys, port)
    println("musicmeta web demo running at http://localhost:$port")
}

private fun env(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }

/**
 * Every `secrets.properties` on the search path merged, the nearer file winning per key — not the
 * first that exists, which cannot tell a template from a configuration (`docs/pitfalls.md` §13).
 *
 * A key present but blank is dropped rather than returned as `""`: every caller reads
 * `secrets[k] ?: env(k)`, and an empty string is non-null, so it would beat the environment variable
 * it is meant to defer to.
 */
private fun loadSecrets(): Map<String, String> =
    listOf(File("../secrets.properties"), File("secrets.properties"))
        .filter { it.exists() }
        .fold(mutableMapOf<String, String>()) { merged, file -> merged.apply { putAll(file.readKeys()) } }

private fun File.readKeys(): Map<String, String> = readLines()
    .filter { it.contains('=') && !it.trimStart().startsWith('#') }
    .associate { line -> val (k, v) = line.split('=', limit = 2); k.trim() to v.trim() }
    .filterValues { it.isNotEmpty() }
