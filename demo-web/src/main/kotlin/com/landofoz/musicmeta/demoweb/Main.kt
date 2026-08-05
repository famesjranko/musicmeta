package com.landofoz.musicmeta.demoweb

import com.landofoz.musicmeta.ApiKeyConfig
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentEngine
import java.io.File

fun main() {
    val port = env("PORT")?.toIntOrNull() ?: 8099
    val secrets = loadSecrets()
    val keys = ApiKeyConfig(
        lastFmKey = secrets["lastfm.apikey"] ?: env("LASTFM_API_KEY"),
        fanartTvProjectKey = secrets["fanarttv.apikey"] ?: env("FANARTTV_API_KEY"),
        discogsPersonalToken = secrets["discogs.token"] ?: env("DISCOGS_TOKEN"),
        listenBrainzToken = secrets["listenbrainz.token"] ?: env("LISTENBRAINZ_TOKEN"),
    )
    val config = EnrichmentConfig(userAgent = "musicmeta-web-demo/1.0 (+https://github.com/famesjranko/musicmeta)")
    val engine = EnrichmentEngine.Builder()
        .apiKeys(keys)
        .config(config)
        .withDefaultProviders()
        .build()

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

    startServer(engine, port)
    println("musicmeta web demo running at http://localhost:$port")
}

private fun env(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }

private fun loadSecrets(): Map<String, String> {
    val paths = listOf(File("secrets.properties"), File("../secrets.properties"))
    val file = paths.firstOrNull { it.exists() } ?: return emptyMap()
    return file.readLines()
        .filter { it.contains('=') && !it.trimStart().startsWith('#') }
        .associate { line -> val (k, v) = line.split('=', limit = 2); k.trim() to v.trim() }
}
