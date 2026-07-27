package com.landofoz.musicmeta

/**
 * Logging interface for the enrichment engine.
 * Defaults to no-op. Android consumers can bridge to Logcat,
 * JVM consumers to SLF4J, etc.
 */
interface EnrichmentLogger {
    fun debug(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable? = null)

    companion object {
        val NoOp: EnrichmentLogger = object : EnrichmentLogger {
            override fun debug(tag: String, message: String) {}
            override fun warn(tag: String, message: String, throwable: Throwable?) {}
        }
    }
}

/**
 * A logger is a side channel; its failure must not become the engine's.
 *
 * Wrapped once at [EnrichmentEngine.Builder.logger] rather than at each call site, so every
 * `logger.debug`/`logger.warn` in the engine — including the ones inside the cache, strategy and
 * provider guards' own `catch` blocks, and the ones on the happy path — inherits the guard.
 *
 * Deliberately no `ensureActive()`, unlike every other guard in the engine — and the reason is that
 * [EnrichmentLogger.debug] and [EnrichmentLogger.warn] are **not** `suspend`. Cancellation is
 * cooperative, so it cannot be delivered into a non-suspend call; a
 * [kotlinx.coroutines.CancellationException] surfacing here was therefore constructed by the
 * consumer's own logger, and swallowing it is correct. `ensureActive()` is not reachable from a
 * non-suspend override in any case. `catch (Exception)` matches the other guards — an `Error` still
 * escapes.
 */
internal fun EnrichmentLogger.guarded(): EnrichmentLogger = object : EnrichmentLogger {
    override fun debug(tag: String, message: String) {
        try {
            this@guarded.debug(tag, message)
        } catch (_: Exception) {
            // ignored: see KDoc
        }
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        try {
            this@guarded.warn(tag, message, throwable)
        } catch (_: Exception) {
            // ignored: see KDoc
        }
    }
}
