package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.cache.CacheMode
import com.landofoz.musicmeta.cache.InMemoryEnrichmentCache
import java.io.File
import java.util.concurrent.atomic.AtomicReference

private const val CONTACT = "https://github.com/famesjranko/musicmeta"

fun main() {
    val port = env("PORT")?.toIntOrNull() ?: 8099
    val secrets = loadSecrets()
    val keys = ApiKeyConfig(
        lastFmKey = secrets["lastfm.apikey"] ?: env("LASTFM_API_KEY"),
        fanartTvProjectKey = secrets["fanarttv.apikey"] ?: env("FANARTTV_API_KEY"),
        discogsPersonalToken = secrets["discogs.token"] ?: env("DISCOGS_TOKEN"),
        listenBrainzToken = secrets["listenbrainz.token"] ?: env("LISTENBRAINZ_TOKEN"),
    )
    val cache = InMemoryEnrichmentCache()

    fun buildEngine(cacheMode: CacheMode): EnrichmentEngine {
        // The library's 30s default suits a consumer that would rather answer fast and partially —
        // a mobile app with a screen to fill. This demo wants the opposite: it exists to show what
        // every provider returns, so a type dropped to meet a deadline is the failure, not the slow
        // answer.
        //
        // 30s cannot hold that here, and the reason is structural rather than a matter of tuning.
        // MusicBrainz allows one request a second and the library holds one limiter per host, so
        // concurrent cold enrichments do not run side by side — they interleave into one queue. Wall
        // clock therefore scales with the number of people clicking at once while the deadline does
        // not, and the types that lose are whichever were still queued: a browser open in two tabs
        // is enough.
        //
        // 120s is headroom for that, not a measured figure — nothing here derives it, and a busy
        // enough demo would exhaust it too.
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
    // has entries a NETWORK_FIRST run already warmed to serve as fallbacks.
    val engineRef = AtomicReference(buildEngine(CacheMode.NETWORK_FIRST))
    val cacheModeRef = AtomicReference(CacheMode.NETWORK_FIRST)

    val missingKeys = listOfNotNull(
        "LASTFM_API_KEY".takeIf { keys.lastFmKey == null },
        "FANARTTV_API_KEY".takeIf { keys.fanartTvProjectKey == null },
        "DISCOGS_TOKEN".takeIf { keys.discogsPersonalToken == null },
        "LISTENBRAINZ_TOKEN".takeIf { keys.listenBrainzToken == null },
    )
    if (missingKeys.isNotEmpty()) {
        println(
            "No key set for: ${missingKeys.joinToString(", ")} — those providers are skipped. " +
                "See secrets.properties / README.",
        )
    }

    startServer(engineRef, cacheModeRef, ::buildEngine, port)
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
