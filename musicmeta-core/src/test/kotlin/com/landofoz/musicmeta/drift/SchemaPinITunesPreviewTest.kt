package com.landofoz.musicmeta.drift

import com.landofoz.musicmeta.provider.itunes.ITunesApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SchemaPinITunesPreviewTest {
    @Test
    fun `preview routes detect a renamed preview URL field`() {
        // Given - a captured search response has lost the field every preview depends on.
        val body = JSONObject(checkNotNull(javaClass.getResource("/fixtures/itunes-preview/girls.json")).readText())
        val rows = body.getJSONArray("results")
        repeat(rows.length()) { rows.getJSONObject(it).remove("previewUrl") }
        val targets = ITunesApi.SCHEMA_PIN_TARGETS.filter { it.route == "previewTracks" }

        // When - the search and lookup pins inspect the changed response.
        val verdicts = targets.map { classifyBody(body, it.requiredPaths) }

        // Then - both routes name the missing preview field as drift.
        assertEquals(2, targets.size)
        assertEquals(List(2) { PinVerdict.Drift(listOf("results[*].previewUrl")) }, verdicts)
    }
}
