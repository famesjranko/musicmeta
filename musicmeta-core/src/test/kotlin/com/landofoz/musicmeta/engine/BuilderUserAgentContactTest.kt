package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentEngine
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ProviderCapability
import com.landofoz.musicmeta.testutil.FakeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MusicBrainz and Wikimedia require contact information in the User-Agent. The builder's
 * `contact()` is the short way to supply it, and a build that supplies neither it nor a
 * User-Agent of its own says so once.
 */
class BuilderUserAgentContactTest {

    /** Records warnings so a test can assert on the one the builder emits. */
    private class RecordingLogger : EnrichmentLogger {
        val warnings = mutableListOf<String>()
        override fun debug(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) {
            warnings.add(message)
        }
    }

    private fun provider(id: String) = FakeProvider(
        id = id,
        capabilities = listOf(ProviderCapability(EnrichmentType.GENRE, 100)),
    )

    private fun configOf(engine: EnrichmentEngine) = (engine as DefaultEnrichmentEngine).config

    @Test fun `contact info composes the policy-suggested User-Agent`() {
        // Given - a builder given contact information and no User-Agent of its own
        val builder = EnrichmentEngine.Builder().contact("https://example.com/musicmeta")

        // When - building the engine
        val engine = builder.build()

        // Then - the contact sits in the form both policies ask for
        assertEquals(
            "MusicEnrichmentEngine/1.0 ( https://example.com/musicmeta )",
            configOf(engine).userAgent,
        )
    }

    @Test fun `a custom User-Agent is used verbatim and contact is ignored`() {
        // Given - a builder given both a full custom User-Agent and contact information
        val builder = EnrichmentEngine.Builder()
            .config(EnrichmentConfig(userAgent = "MyApp/2.0 (me@example.com)"))
            .contact("https://example.com/ignored")

        // When - building the engine
        val engine = builder.build()

        // Then - the caller's string survives untouched
        assertEquals("MyApp/2.0 (me@example.com)", configOf(engine).userAgent)
    }

    @Test fun `the contactless default warns once, naming the fix`() {
        // Given - a builder with MusicBrainz registered and no contact information
        val logger = RecordingLogger()
        val builder = EnrichmentEngine.Builder()
            .addProvider(provider("musicbrainz"))
            .logger(logger)

        // When - building the engine
        builder.build()

        // Then - exactly one warning names the unmet policy and the builder field that fixes it
        assertEquals(1, logger.warnings.size)
        val warning = logger.warnings.single()
        assertTrue(warning, "musicbrainz" in warning)
        assertTrue(warning, "contact information" in warning)
        assertTrue(warning, "EnrichmentEngine.Builder.contact()" in warning)
    }

    @Test fun `contact information silences the warning`() {
        // Given - the same provider set, with contact information supplied
        val logger = RecordingLogger()
        val builder = EnrichmentEngine.Builder()
            .addProvider(provider("musicbrainz"))
            .addProvider(provider("wikipedia"))
            .contact("me@example.com")
            .logger(logger)

        // When - building the engine
        builder.build()

        // Then - nothing is warned about
        assertEquals(emptyList<String>(), logger.warnings)
    }

    @Test fun `a provider set with no affected provider does not warn`() {
        // Given - the contactless default, but no MusicBrainz or Wikimedia provider registered
        val logger = RecordingLogger()
        val builder = EnrichmentEngine.Builder()
            .addProvider(provider("deezer"))
            .logger(logger)

        // When - building the engine
        builder.build()

        // Then - no policy demands contact information, so nothing is warned about
        assertEquals(emptyList<String>(), logger.warnings)
    }
}
