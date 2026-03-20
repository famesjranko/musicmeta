package com.cascade.enrichment

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
