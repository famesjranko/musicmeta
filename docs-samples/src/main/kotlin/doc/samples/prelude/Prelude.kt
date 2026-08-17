// The context every guide's narrative may assume without teaching it again, injected by
// check_doc_samples.py ahead of every guide's narrative(), uniformly. A guide that teaches an
// alternative — quick-start.md's own engine-setup fence, a different `results` — rebinds the name in
// its own isolated `run { }`; it does not edit this file.
package doc.samples.prelude

import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentIdentifiers
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.SearchCandidate
import com.landofoz.musicmeta.TopTrack
import okhttp3.OkHttpClient

/** The default engine every guide's narrative may assume, built the way quick-start.md's own is. */
suspend fun preludeEngine(): EnrichmentEngine = EnrichmentEngine.Builder().withDefaultProviders().build()

/** A default `enrich()` result — only its type is load-bearing for a doc sample, never its content. */
suspend fun preludeResults(engine: EnrichmentEngine): EnrichmentResults =
    engine.enrich(EnrichmentRequest.forArtist("Radiohead"), setOf(EnrichmentType.GENRE))

/** Stand-in for "some cache key you already have" — cache-management.md never names a real one. */
val preludeOpaqueEntityKey: String = "opaque-entity-key"

/** Stand-in for "your existing `OkHttpClient`" — quick-start.md and extension-points.md's own instance. */
val preludeOkHttpClient: OkHttpClient = OkHttpClient()

/** Stand-in for a `SearchCandidate` a caller picked from `profile.suggestions`/`engine.search()`. */
val preludeSearchCandidate: SearchCandidate =
    SearchCandidate(
        title = "Bush",
        artist = "Bush",
        year = null,
        country = null,
        releaseType = null,
        score = 90,
        thumbnailUrl = null,
        identifiers = EnrichmentIdentifiers(),
        provider = "musicbrainz",
    )

/** Stand-in for one of `profile.topTracks?.tracks` — carries its own identifiers, per quick-start.md. */
val preludeTopTrack: TopTrack = TopTrack(title = "Idioteque", artist = "Radiohead", rank = 1)

val preludeTopTracks: List<TopTrack> = listOf(preludeTopTrack)

/** Stand-in for "your own local catalog" — configuration.md's `CatalogProvider` example. */
object preludeMyLibrary {
    fun contains(title: String, artist: String): Boolean = false

    fun getUri(title: String, artist: String): String? = null
}

/** Stand-in for "your app's UI layer" — quick-start.md's `enrichBatch`/`Flow.collect` example. */
fun preludeUpdateUI(title: String, artwork: EnrichmentData.Artwork?, genres: List<String>) = Unit

/** Stand-in for "your app's UI layer" — cache-management.md's `isStale` example. */
fun preludeShowBanner(message: String) = Unit
