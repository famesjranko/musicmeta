package com.landofoz.musicmeta.provider.musicbrainz

import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.FakeHttpClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins `scripts/probes/mb-search-4xx-probe.sh`'s live finding (2026-08-12: every case, both the
 * quoted `buildQuery` shape and the unquoted fuzzy-`~` shape, sent to MusicBrainz answered 200,
 * never a 4xx) as a property of the query builders' escaping rather than a fact that decays with
 * the network: every Lucene special character is backslash-escaped before framing, the quoted
 * phrase cannot be broken out of, the unquoted fuzzy term stays a single Lucene token even when
 * hostile, and the whole expression is URL-encoded — so nothing a title/artist can carry reaches
 * MusicBrainz as unescaped Lucene syntax or an unterminated phrase, in either shape.
 * `.scratch/mb-4xx-as-empty-pool/spec.md` question 1, answered: outcome (a), provably unreachable.
 */
class MusicBrainzApiHostileQueryTest {

    private val httpClient = FakeHttpClient()
    private val api = MusicBrainzApi(httpClient, RateLimiter(0))

    @Test
    fun `an over-long title still lands inside one quoted, escaped phrase`() = runTest {
        // Given - a title far past anything a real release title could be
        val overLong = "a".repeat(3000)
        httpClient.givenJsonResponse("release?query", EMPTY_RELEASES)

        // When - searching with it
        api.searchReleases(overLong, "Radiohead")

        // Then - the whole title sits inside one quoted phrase; nothing truncates or splits it
        val url = httpClient.requestedUrls.single()
        assertTrue(url.contains("release%3A%22" + "a".repeat(3000) + "%22"))
    }

    @Test
    fun `a title of only Lucene metacharacters is fully backslash-escaped`() = runTest {
        // Given - a title with no alphanumeric content, only characters escapeLucene targets
        httpClient.givenJsonResponse("release?query", EMPTY_RELEASES)

        // When - searching with it
        api.searchReleases("""+-&|!(){}[]^"~*?:\/""", "Radiohead")

        // Then - every special character is backslash-escaped ahead of URL-encoding, so none of
        // them reaches MusicBrainz as bare Lucene syntax
        val url = httpClient.requestedUrls.single()
        val decodedQuery = java.net.URLDecoder.decode(url.substringAfter("query=").substringBefore("&fmt"), "UTF-8")
        assertTrue(
            "expected an escaped metachar title in $decodedQuery",
            decodedQuery.contains("""release:"\+\-\&\|\!\(\)\{\}\[\]\^\"\~\*\?\:\\\/""""),
        )
    }

    @Test
    fun `a title that is blank after trim still forms a well-formed empty phrase`() = runTest {
        // Given - a title that is only whitespace
        httpClient.givenJsonResponse("release?query", EMPTY_RELEASES)

        // When - searching with it
        api.searchReleases("   ", "Radiohead")

        // Then - buildQuery does not special-case blank input; it still emits a closed, quoted
        // (if whitespace-only) phrase rather than an unterminated or malformed one
        val url = httpClient.requestedUrls.single()
        assertTrue(url.contains("release%3A%22" + "+".repeat(3) + "%22"))
    }

    @Test
    fun `an unpaired quote in the title cannot break out of the Lucene phrase`() = runTest {
        // Given - a title carrying a raw double-quote
        httpClient.givenJsonResponse("release?query", EMPTY_RELEASES)

        // When - searching with it
        api.searchReleases("""Abbey "Road""", "The Beatles")

        // Then - escapeLucene escapes the embedded quote too, so the outer phrase MusicBrainz
        // parses is still exactly one field:"..." term, never two, and never unterminated
        val url = httpClient.requestedUrls.single()
        val decodedQuery = java.net.URLDecoder.decode(url.substringAfter("query=").substringBefore("&fmt"), "UTF-8")
        assertTrue(
            "expected the embedded quote escaped in $decodedQuery",
            decodedQuery.contains("""release:"Abbey \"Road""""),
        )
        assertFalse(
            "the phrase must not close early on the embedded quote",
            decodedQuery.contains("""release:"Abbey """"),
        )
    }

    @Test
    fun `control characters reach the encoded URL as bytes, never as unescaped Lucene syntax`() = runTest {
        // Given - a title carrying a NUL and a bare newline, neither of which is Lucene-special so
        // escapeLucene passes them through — the class the ticket's four inputs missed
        httpClient.givenJsonResponse("release?query", EMPTY_RELEASES)

        // When - searching with it
        api.searchReleases("OK\u0000Computer\nSide2", "Radiohead")

        // Then - URLEncoder turns both into percent-encoded bytes; none of Lucene's operator
        // characters (AND/OR/quote/colon) appear unescaped outside the field:"..." shape
        val url = httpClient.requestedUrls.single()
        assertTrue(url.contains("%00"))
        assertTrue(url.contains("%0A") || url.contains("%0a"))
        assertTrue(url.startsWith("https://musicbrainz.org/ws/2/release?query=release%3A%22"))
    }

    @Test
    fun `an over-long title still lands inside one unquoted fuzzy term`() = runTest {
        // Given - a title far past anything a real release title could be
        val overLong = "a".repeat(3000)
        httpClient.givenJsonResponse("release?query", EMPTY_RELEASES)

        // When - fuzzy-searching with it
        api.searchReleasesFuzzy(overLong, "Radiohead")

        // Then - the whole title sits ahead of the trailing fuzzy operator; nothing truncates or
        // splits it, and the term stays unquoted the way searchReleasesFuzzy always builds it
        val url = httpClient.requestedUrls.single()
        assertTrue(url.contains("release%3A" + "a".repeat(3000) + "%7E"))
    }

    @Test
    fun `a fuzzy title of only Lucene metacharacters is fully backslash-escaped`() = runTest {
        // Given - a title with no alphanumeric content, only characters escapeLucene targets
        httpClient.givenJsonResponse("release?query", EMPTY_RELEASES)

        // When - fuzzy-searching with it
        api.searchReleasesFuzzy("""+-&|!(){}[]^"~*?:\/""", "Radiohead")

        // Then - every special character is backslash-escaped ahead of URL-encoding and ahead of
        // the trailing fuzzy `~`, so none of them reaches MusicBrainz as bare Lucene syntax
        val url = httpClient.requestedUrls.single()
        val decodedQuery = java.net.URLDecoder.decode(url.substringAfter("query=").substringBefore("&fmt"), "UTF-8")
        assertTrue(
            "expected an escaped metachar title in $decodedQuery",
            decodedQuery.contains("""release:\+\-\&\|\!\(\)\{\}\[\]\^\"\~\*\?\:\\\/~"""),
        )
    }

    @Test
    fun `a fuzzy title that is blank after trim still forms a well-formed fuzzy term`() = runTest {
        // Given - a title that is only whitespace
        httpClient.givenJsonResponse("release?query", EMPTY_RELEASES)

        // When - fuzzy-searching with it
        api.searchReleasesFuzzy("   ", "Radiohead")

        // Then - the fuzzy builder does not special-case blank input either; the fuzzy operator
        // still lands directly after the (blank) escaped title, not on a missing/malformed term
        val url = httpClient.requestedUrls.single()
        assertTrue(url.contains("release%3A" + "+".repeat(3) + "%7E"))
    }

    @Test
    fun `an unpaired quote in a fuzzy title cannot break the term in two`() = runTest {
        // Given - a title carrying a raw double-quote
        httpClient.givenJsonResponse("release?query", EMPTY_RELEASES)

        // When - fuzzy-searching with it
        api.searchReleasesFuzzy("""Abbey "Road""", "The Beatles")

        // Then - escapeLucene escapes the embedded quote here too, so the unquoted term MusicBrainz
        // parses stays one Lucene token ending in `~`, never two terms and never unterminated
        val url = httpClient.requestedUrls.single()
        val decodedQuery = java.net.URLDecoder.decode(url.substringAfter("query=").substringBefore("&fmt"), "UTF-8")
        assertTrue(
            "expected the embedded quote escaped in $decodedQuery",
            decodedQuery.contains("""release:Abbey \"Road~"""),
        )
    }

    @Test
    fun `control characters reach the fuzzy shape's encoded URL as bytes, never as unescaped Lucene syntax`() = runTest {
        // Given - a title carrying a NUL and a bare newline, neither of which is Lucene-special so
        // escapeLucene passes them through — the class the ticket's four inputs missed
        httpClient.givenJsonResponse("release?query", EMPTY_RELEASES)

        // When - fuzzy-searching with it
        api.searchReleasesFuzzy("OK\u0000Computer\nSide2", "Radiohead")

        // Then - URLEncoder turns both into percent-encoded bytes; the term stays a single
        // unquoted, fuzzy-suffixed token
        val url = httpClient.requestedUrls.single()
        assertTrue(url.contains("%00"))
        assertTrue(url.contains("%0A") || url.contains("%0a"))
        assertTrue(url.startsWith("https://musicbrainz.org/ws/2/release?query=release%3A"))
        assertTrue(url.contains("%7E+AND+artistname%3ARadiohead%7E"))
    }

    private companion object {
        const val EMPTY_RELEASES = """{"releases": []}"""
    }
}
