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
import kotlin.system.exitProcess

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
    val requested = parsePublicRelaxations(env("DEMO_PUBLIC_ALLOW"))
    val posture = PublicPosture(publicPostureEnabled(env("DEMO_PUBLIC")), requested.recognised)
    // Refused rather than ignored, and only where the variable does anything: a relaxation is an
    // operator's explicit choice, so a token that names nothing is a choice this process cannot
    // carry out. Failing here leaves the previous deployment serving; accepting the typo would
    // leave a posture nobody chose, which is what DEMO_PUBLIC exists to rule out.
    if (posture.enabled && requested.unknown.isNotEmpty()) {
        System.err.println(unknownRelaxationMessage(requested.unknown))
        exitProcess(64)
    }
    val keys = ApiKeyConfig(
        lastFmKey = LASTFM.resolve(secrets),
        fanartTvProjectKey = FANARTTV.resolve(secrets),
        discogsPersonalToken = DISCOGS.resolve(secrets),
        listenBrainzToken = LISTENBRAINZ.resolve(secrets),
    ).underPublicPosture(posture)
    val cache = InMemoryEnrichmentCache().let { if (posture.capsDiscogsFreshness) DiscogsFreshnessCache(it) else it }

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
        val engine = EnrichmentEngine.Builder()
            .apiKeys(keys)
            .config(config)
            .cache(cache)
            .contact(CONTACT)
            .withDefaultProviders()
            .build()
        return if (posture.withholdsDiscogsImages) PublicPostureEngine(engine) else engine
    }

    // The same shared `cache` above every rebuild passes to `buildEngine` is what makes the
    // toggle honest: swapping the engine held here does not clear it, so a STALE_IF_ERROR swap
    // has entries a NETWORK_FIRST run already warmed to serve as fallbacks. `withDefaultProviders()`
    // does rebuild per-host rate limiters and circuit breakers fresh on every swap, though — those
    // do not survive.
    val engineRef = AtomicReference(buildEngine(CacheMode.NETWORK_FIRST))
    val cacheModeRef = AtomicReference(CacheMode.NETWORK_FIRST)

    val missing = missingCredentials(keys, posture.withheldCredentialIds)
    if (posture.enabled) println(posture.notice)
    if (missing.required.isNotEmpty()) {
        println(
            "No key set for: ${missing.required.joinToString(", ")} — those providers are skipped. " +
                "See secrets.properties / README.",
        )
    }
    if (missing.optional.isNotEmpty()) {
        println(
            "No token set for: ${missing.optional.joinToString(", ")} — registered, but artist radio " +
                "discovery is off. See secrets.properties / README.",
        )
    }

    startServer(
        engineRef,
        cacheModeRef,
        ::buildEngine,
        keys,
        port,
        unregisteredProviderIds = posture.unregisteredProviderIds,
    )
    println("musicmeta web demo running at http://localhost:$port")
}

/** The env vars a startup message names, split by what their absence actually costs. */
internal data class MissingCredentials(val required: List<String>, val optional: List<String>)

/**
 * Which [KEY_SPECS] env vars the built [keys] leaves unset, ignoring [withheldIds] — a credential
 * dropped on purpose is not one an operator forgot, and printing it as missing invites them to
 * "fix" it.
 *
 * Missingness reads the catalog's own selector against the built config, not the raw sources —
 * Required and Optional are different failures. A missing Required key means the provider never
 * registered at all; a missing Optional token (ListenBrainz) means it registered and answers
 * everything except artist radio discovery, so lumping it in with "skipped" would be false.
 */
internal fun missingCredentials(keys: ApiKeyConfig, withheldIds: Set<String>): MissingCredentials {
    val catalogById = ProviderCatalog.entries.associateBy { it.id }
    val considered = KEY_SPECS
        .filterNot { it.catalogId in withheldIds }
        .map { spec -> spec.envVar to catalogById.getValue(spec.catalogId).keyRequirement }
    return MissingCredentials(
        required = considered.mapNotNull { (envVar, requirement) ->
            envVar.takeIf { requirement is KeyRequirement.Required && requirement.key(keys) == null }
        },
        optional = considered.mapNotNull { (envVar, requirement) ->
            envVar.takeIf { requirement is KeyRequirement.Optional && requirement.key(keys) == null }
        },
    )
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
