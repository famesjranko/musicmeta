package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** The canonical title and artist of the entity an identity provider resolved. */
internal data class EntityNames(val title: String?, val artist: String?)

/**
 * Per-[DefaultEnrichmentEngine.enrich] channel carrying the names the identity provider read off
 * the entity it resolved, installed once per call alongside [ProviderCallScope].
 *
 * A request built by [EnrichmentRequest.Companion.forTrackByMbid] and its siblings names an
 * identifier and nothing else, and every provider but MusicBrainz searches by name — so the fan-out
 * needs a name that only the identity payload holds. `EnrichmentResult` cannot carry it: it is
 * published, and its `Success` is a `data class` a consumer may already construct.
 *
 * [offer] is first-write-wins because identity resolution runs before the fan-out, so the first
 * entity named this call is the one the request was resolved to; the later types' entities are
 * byproducts of it.
 */
internal class ResolvedEntityNames : AbstractCoroutineContextElement(Key) {

    private val names = AtomicReference<EntityNames?>(null)

    /** Records [title] and [artist] as this call's resolved entity, unless one is already recorded. */
    fun offer(title: String?, artist: String?) {
        names.compareAndSet(null, EntityNames(title, artist))
    }

    /** What identity resolution named, or null when it named nothing this call. */
    fun resolved(): EntityNames? = names.get()

    /**
     * Records how this call's alias pool can be obtained, unless a source is already recorded —
     * first-write-wins for the same reason [offer] is.
     *
     * A source rather than a list because obtaining the pool is free on some resolution paths and
     * costs a lookup on others, and only a matcher that has already failed on the requested name
     * can say whether it is worth paying for. [aliases] is what decides that, once per call.
     */
    fun offerAliases(source: suspend () -> List<AlternativeName>) {
        aliasSource.compareAndSet(null, source)
    }

    /**
     * The alternative names identity resolution holds for this call's entity, resolving the source
     * on first read and reusing that answer afterwards — however many providers and candidates ask.
     *
     * Empty when nothing offered a source, and empty when the source failed: an alias pool is
     * corroboration, so a provider that cannot get one falls back to matching on the requested name
     * alone rather than failing the type. **A failed source is held as that empty pool for the rest
     * of the call**, so a failing upstream costs one lookup and not one per reader; the price is
     * that a source recovering mid-call is invisible to the later readers. Cancellation of this
     * call's own job propagates instead, and is not held.
     */
    // SwallowedException: the degrade above is the contract; the exception has no second reader.
    @Suppress("SwallowedException")
    suspend fun aliases(): List<AlternativeName> {
        resolvedAliases.get()?.let { return it }
        val source = aliasSource.get() ?: return emptyList()
        return aliasLock.withLock {
            resolvedAliases.get() ?: run {
                val pool = try {
                    source()
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    emptyList()
                }
                resolvedAliases.set(pool)
                pool
            }
        }
    }

    private val aliasSource = AtomicReference<(suspend () -> List<AlternativeName>)?>(null)
    private val resolvedAliases = AtomicReference<List<AlternativeName>?>(null)
    private val aliasLock = Mutex()

    internal companion object Key : CoroutineContext.Key<ResolvedEntityNames>
}

/**
 * The alias pool this call's identity resolution holds, or empty outside an engine call and for a
 * request nothing offered a pool for.
 */
internal suspend fun resolvedAliasPool(): List<AlternativeName> =
    currentCoroutineContext()[ResolvedEntityNames]?.aliases().orEmpty()

/**
 * [request] with each blank name field filled from [names].
 *
 * Blank only, never an override. A caller asking for "Comfortably Numb (Live at Earls Court)"
 * resolves to a recording MusicBrainz titles "Comfortably Numb" — it keeps the variant in the
 * disambiguation — so overwriting would send Deezer and LRCLIB after the studio take. An
 * artist-credit of "Metallica feat. X" is the same hazard on the artist field.
 */
internal fun EnrichmentRequest.withBackfilledNames(names: EntityNames?): EnrichmentRequest {
    if (names == null) return this
    return when (this) {
        is EnrichmentRequest.ForAlbum -> copy(
            title = title.filledFrom(names.title),
            artist = artist.filledFrom(names.artist),
        )
        is EnrichmentRequest.ForArtist -> copy(name = name.filledFrom(names.title))
        is EnrichmentRequest.ForTrack -> copy(
            title = title.filledFrom(names.title),
            artist = artist.filledFrom(names.artist),
        )
    }
}

private fun String.filledFrom(canonical: String?): String =
    if (isBlank() && !canonical.isNullOrBlank()) canonical else this
