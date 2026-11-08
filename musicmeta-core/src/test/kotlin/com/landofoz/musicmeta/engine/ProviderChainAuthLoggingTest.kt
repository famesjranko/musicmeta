package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ProviderChainAuthLoggingTest(private val collectAll: Boolean) {
    @Test
    fun `an auth refusal from any provider warns on every route`() = runTest {
        // Given - a custom provider refuses an album metadata request with an auth error.
        val type = EnrichmentType.ALBUM_METADATA
        val provider = FakeProvider("custom-catalog", capabilities = listOf(ProviderCapability(type, 100)))
        val cause = IllegalStateException("HTTP 401 credentials refused")
        provider.givenResult(type, EnrichmentResult.Error(type, provider.id, checkNotNull(cause.message), cause, ErrorKind.AUTH))
        val warnings = mutableListOf<Pair<String, Throwable?>>()
        val logger = object : EnrichmentLogger {
            override fun debug(tag: String, message: String) = Unit
            override fun warn(tag: String, message: String, throwable: Throwable?) {
                warnings.add(message to throwable)
            }
        }
        val chain = ProviderChain(type, listOf(provider), logger = logger)
        val request = EnrichmentRequest.forAlbum("Album", "Artist")

        // When - resolving through either the first-success walk or the collect-all walk.
        if (collectAll) chain.resolveAll(request) else chain.resolve(request)

        // Then - one warning names the runtime provider and route and retains the refusal and cause.
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().first.contains(provider.id))
        assertTrue(warnings.single().first.contains(type.name))
        assertTrue(warnings.single().first.contains(checkNotNull(cause.message)))
        assertEquals(cause, warnings.single().second)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "collectAll={0}")
        fun cases(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }
}
