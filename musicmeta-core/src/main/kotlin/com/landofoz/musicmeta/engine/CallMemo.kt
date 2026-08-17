package com.landofoz.musicmeta.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One upstream answer per key for as long as this instance lives. A provider gets one lifetime
 * shorter than the provider itself by slotting an instance into [ProviderCallScope] via
 * [ProviderCallScope.slot], which makes that lifetime one `enrich()` call — nothing held here
 * outlives it.
 *
 * The mutex is held across [fetch], not merely around the map: the engine resolves a request's
 * types as sibling `async` children, so two types asking the same question concurrently make one
 * call between them rather than one each.
 *
 * A thrown transient is never held — the write is on the success path — so the next type that asks
 * retries it. An *absence* is held like any other answer: [get] takes whatever [fetch] returns,
 * `null` and empty included, as the answer for [key] and repeats it rather than re-fetching, which
 * is what stops a request naming nothing paying the same "no" once per type.
 *
 * A `null` (or other empty value) that means "could not determine" rather than "definitively
 * absent" must not be memoized bare through this class — a transient collapsed to that value would
 * then be held and repeated for the rest of the call as if it were a confirmed miss. Model that
 * distinction in `V` instead, as
 * [com.landofoz.musicmeta.provider.musicbrainz.MusicBrainzLookup]'s `Found`/`Absent`/`Unreadable`
 * does for MusicBrainz's own lookups.
 */
internal class CallMemo<K : Any, V> {

    private val entries = mutableMapOf<K, V>()
    private val mutex = Mutex()

    /** [fetch]'s answer for [key], held for the call whatever it is — including a negative one. */
    suspend fun get(key: K, fetch: suspend () -> V): V = mutex.withLock {
        if (entries.containsKey(key)) entries.getValue(key) else fetch().also { entries[key] = it }
    }
}
