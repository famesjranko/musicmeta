package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.CacheOp
import com.landofoz.musicmeta.testutil.FakeEnrichmentCache
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `enrich()` must not propagate a failure from a consumer's [EnrichmentLogger] (#71). The logger is
 * called from inside the cache and strategy guards' own `catch` blocks and on the happy path, so
 * before the fix it could fail a call that the engine had already handled.
 */
class EnrichLoggerFailureTest {

    /** Throws from both methods, and counts the throws so a test cannot pass by never logging. */
    private class ThrowingLogger : EnrichmentLogger {
        var calls = 0
        override fun debug(tag: String, message: String) {
            calls++
            error("logger exploded on debug")
        }
        override fun warn(tag: String, message: String, throwable: Throwable?) {
            calls++
            error("logger exploded on warn")
        }
    }

    private val config = EnrichmentConfig(enableIdentityResolution = false)
    private val request = EnrichmentRequest.forAlbum("OK Computer", "Radiohead")
    private val artType = EnrichmentType.ALBUM_ART

    private fun provider() = FakeProvider(
        id = "p",
        capabilities = listOf(ProviderCapability(artType, 100)),
    ).also {
        it.givenResult(
            artType,
            EnrichmentResult.Success(artType, EnrichmentData.Artwork("https://x.com/art.jpg"), "p", 0.95f),
        )
    }

    /** Reports no identity, which is a happy-path `logger.debug` — no failure is being handled. */
    private fun identityProvider() = FakeProvider(id = "identity", isIdentityProvider = true)
        .also { it.givenIdentityResult(EnrichmentResult.NotFound(EnrichmentType.GENRE, "identity")) }

    private fun engine(
        logger: EnrichmentLogger,
        cache: FakeEnrichmentCache,
        cfg: EnrichmentConfig = config,
        extraProvider: FakeProvider? = null,
    ) = EnrichmentEngine.Builder()
        .addProvider(provider())
        .also { b -> extraProvider?.let { b.addProvider(it) } }
        .cache(cache)
        .config(cfg)
        .logger(logger)
        .build()

    @Test fun `a throwing logger does not fail a successful enrich`() = runTest {
        // Given -- a healthy engine whose consumer logger throws from every call. Identity
        // resolution is on so the happy-path logger.debug calls are reached: those report no
        // failure at all, so a throw there takes down an enrich() that had nothing wrong with it.
        val logger = ThrowingLogger()

        // When -- enriching
        val results = engine(
            logger,
            FakeEnrichmentCache(),
            cfg = EnrichmentConfig(),
            extraProvider = identityProvider(),
        ).enrich(request, setOf(artType))

        // Then -- the type resolves; the logging side effect is the only thing lost
        assertEquals("p", (results.raw[artType] as EnrichmentResult.Success).provider)
        assertTrue("the logger must actually have been reached", logger.calls > 0)
    }

    @Test fun `a throwing logger does not fail an enrich the cache guard is already degrading`() = runTest {
        // Given -- a cache whose get() throws, so CacheGuard reports the degradation through the
        // logger from inside its own catch. Nothing above that re-catches.
        val logger = ThrowingLogger()
        val cache = FakeEnrichmentCache().also { it.failing = setOf(CacheOp.GET) }

        // When -- enriching
        val results = engine(logger, cache).enrich(request, setOf(artType))

        // Then -- the cache failure still degrades to a miss and the provider answers
        assertEquals("p", (results.raw[artType] as EnrichmentResult.Success).provider)
        assertTrue("the guard's warn must actually have been reached", logger.calls > 0)
    }
}
