package com.landofoz.musicmeta.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Enforces a minimum interval between requests.
 * One instance per host: rate limits are per-host, and the mutex is held across the request
 * itself, so providers sharing an instance make unrelated hosts' round-trips sequential.
 * Providers on the same host share one instance.
 *
 * @param intervalMs Minimum milliseconds between requests
 * @param clock Time source (injectable for testing)
 */
public class RateLimiter(
    private val intervalMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    @Volatile
    private var lastRequestTime = 0L

    /** Execute a block, waiting if necessary to respect the rate limit. */
    public suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock {
        val elapsed = clock() - lastRequestTime
        if (elapsed < intervalMs) {
            delay(intervalMs - elapsed)
        }
        try {
            block()
        } finally {
            lastRequestTime = clock()
        }
    }
}
