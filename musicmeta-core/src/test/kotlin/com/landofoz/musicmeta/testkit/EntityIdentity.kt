package com.landofoz.musicmeta.testkit

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentResults
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.IdentityResolution
import com.landofoz.musicmeta.engine.ArtistMatcher
import com.landofoz.musicmeta.engine.TitleMatcher
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Either the resolution names the entity that was requested, or it declines. No third outcome, and
 * no expected-value table — a provider may rank differently tomorrow and may find nothing, but never
 * answer about something else.
 *
 * **There are two entry points and a caller must choose by name**, because the strong rule is only
 * available for some requests. [assertAnswersTheRequest] is the full rule and refuses to run where it
 * would be vacuous. [assertAnswersTheRequestWithoutCanonicalIdentity] is the weaker one, for a
 * request that structurally cannot carry canonical names; each call to it is a recorded gap that
 * child `12` enumerates by grepping this method's name.
 *
 * **Why the split — the second narrowing of this rule, and what killed each.**
 *
 * The first design had one rule inspecting every provider's own `Success` payload for the entity it
 * matched. Killed by the gate prototype: `EnrichmentData` was read variant by variant and **none of
 * them names an entity** — `Lyrics` carries `syncedLyrics`/`plainLyrics`/`isInstrumental`, `Metadata`
 * carries genres and label and dates, `Artwork` carries a URL and dimensions. For the LRCLIB
 * `Alabama Song` defect the returned payload does not name the track at all, so there was nothing to
 * compare.
 *
 * The replacement asserted the canonical [EnrichmentResults.identity] against the request. Killed by
 * child `00`, 2026-08-16, because [IdentityResolution.title] and [IdentityResolution.artist] are
 * populated **only from an identifier lookup, never from a name search** — stated at
 * `IdentityResolution.kt:20-32`, at `IdentityHelper.kt:14-20`, and at `MusicBrainzEnricher.kt:637`
 * ("Offered from the three lookup paths and never from a search"), and measured: a
 * `forTrack("Under Pressure", "Queen")` against a real `MusicBrainzProvider` returns
 * `status=RESOLVED matchScore=100 title=null artist=null`, whether the hit it took was the right
 * artist or the wrong one. Almost every scenario in this programme is a name-search request, so the
 * rule would have run, compared nothing, and gone green — a test that cannot fail, which is what
 * child `01` deleted 1471 lines of.
 *
 * A rule narrowed twice with no record reads to the next person as a rule that was always this weak.
 * It was not; this is what is left after two things were proved impossible.
 *
 * **What catches a name-search provider's own wrong selection, since this cannot:** the scenario's
 * own stated verdict — `shapes.md` § 4's "must produce" column, which reads as an outcome
 * ("`NotFound`, not the wrong track") rather than a value, precisely so it survives a legitimate
 * re-ranking.
 */
internal object EntityIdentity {

    /**
     * The full rule, for a request that carries an identifier and therefore resolves to canonical
     * names. Asserts everything [assertAnswersTheRequestWithoutCanonicalIdentity] does, and then the
     * thing that actually matters: the canonical artist and title match what was asked for, by the
     * production matchers.
     *
     * **Fails if the canonical names are absent on a `RESOLVED` resolution**, rather than passing
     * quietly. That combination means the request resolved by name search, where this rule has
     * nothing to compare — the caller wants the other method, and wants to have decided that.
     */
    fun assertAnswersTheRequest(request: EnrichmentRequest, results: EnrichmentResults) {
        assertAnswersTheRequestWithoutCanonicalIdentity(request, results)
        val identity = results.identity
        if (identity.status != CanonicalStatus.RESOLVED) return
        if (identity.title == null && identity.artist == null) {
            fail(
                "identity resolved but named nothing, so this rule would assert nothing: " +
                    "request=$request. A request carrying no identifier resolves by name search, " +
                    "which never sets canonical names — use " +
                    "assertAnswersTheRequestWithoutCanonicalIdentity and assert the scenario's own " +
                    "verdict instead.",
            )
        }
        when (request) {
            is EnrichmentRequest.ForArtist -> checkArtist(request.name, identity.title)
            is EnrichmentRequest.ForAlbum -> {
                checkArtist(request.artist, identity.artist)
                checkTitle(request.title, identity.title, "album")
            }
            is EnrichmentRequest.ForTrack -> {
                checkArtist(request.artist, identity.artist)
                checkTitle(request.title, identity.title, "track")
            }
        }
    }

    /**
     * The weaker rule, for a request that names its entity and carries no identifier — where the
     * engine resolves by name search and canonical names are never set, so there is no name to
     * compare. **Every call to this is a recorded gap**; `12` enumerates them by this method's name
     * and reports what fraction of composed scenarios run without a canonical-identity assertion.
     *
     * State plainly what it does assert, so a reader knows its strength rather than assuming it:
     *
     * - **every result is about the type it is filed under.** A result map keyed by one type and
     *   carrying another is a wiring fault no other rule looks for.
     * - **all three non-`Success` variants pass.** Each is a decline, and a decline is never an
     *   answer about the wrong entity (`spec.md` constraint 6). `RateLimited` in particular must
     *   pass, or every scenario that trips a limiter fails the identity rule for a reason that has
     *   nothing to do with identity.
     * - **the resolution is internally consistent**, per the two invariants
     *   [IdentityResolution] declares: `matchScore` is set only when the status is
     *   [CanonicalStatus.RESOLVED] (`IdentityResolution.kt:15-16`) and is a 0–100 score, and
     *   `suggestions` are near-misses carried only by [CanonicalStatus.AMBIGUOUS]
     *   (`IdentityResolution.kt:17-18`). Neither was asserted anywhere before this kit.
     *
     * **What it deliberately does not assert, and why**, correcting a suggestion made while this was
     * designed: a `Success` alongside an `UNRESOLVED` or `AMBIGUOUS` status is *not* a contradiction.
     * A provider whose capability declares `IdentifierRequirement.NONE` — LRCLIB, Deezer, iTunes and
     * Last.fm among them — answers from its own name search without any MusicBrainz identity at all,
     * so that pairing is the normal case for most of the stack rather than a fault.
     */
    fun assertAnswersTheRequestWithoutCanonicalIdentity(
        request: EnrichmentRequest,
        results: EnrichmentResults,
    ) {
        for ((type, result) in results.raw) {
            assertResultIsAboutItsType(request, type, result)
        }
        assertResolutionIsSelfConsistent(results.identity)
    }

    /**
     * Every [EnrichmentResult] variant carries the [EnrichmentType] it answers about, and it must be
     * the one it is filed under. The only per-result claim available without a name to compare.
     */
    fun assertResultIsAboutItsType(
        request: EnrichmentRequest,
        type: EnrichmentType,
        result: EnrichmentResult,
    ) {
        val resultType = when (result) {
            is EnrichmentResult.Success -> result.type
            is EnrichmentResult.NotFound -> result.type
            is EnrichmentResult.RateLimited -> result.type
            is EnrichmentResult.Error -> result.type
        }
        assertTrue(
            failureMessage(type, "type=$type request=$request", "type=$resultType", "type"),
            resultType == type,
        )
    }

    private fun assertResolutionIsSelfConsistent(identity: IdentityResolution) {
        val score = identity.matchScore
        if (identity.status != CanonicalStatus.RESOLVED) {
            assertTrue(
                "matchScore is documented as set only on a RESOLVED status, but status=" +
                    "${identity.status} carries matchScore=$score",
                score == null,
            )
        }
        if (score != null) {
            assertTrue("matchScore is a 0-100 score, but was $score", score in 0..100)
        }
        if (identity.suggestions.isNotEmpty()) {
            assertTrue(
                "suggestions are documented as AMBIGUOUS near-misses, but status=${identity.status} " +
                    "carries ${identity.suggestions.size}",
                identity.status == CanonicalStatus.AMBIGUOUS,
            )
        }
    }

    private fun checkArtist(requested: String, returned: String?) {
        if (returned == null) return
        assertTrue(
            failureMessage(null, "artist=$requested", "artist=$returned", "artist"),
            ArtistMatcher.isMatch(requested, returned),
        )
    }

    private fun checkTitle(requested: String, returned: String?, kind: String) {
        if (returned == null) return
        assertTrue(
            failureMessage(null, "$kind=$requested", "$kind=$returned", "title"),
            TitleMatcher.equivalent(requested, returned),
        )
    }

    /**
     * One failure-message builder for every identity failure across every child, so a failure is
     * actionable without a debugger: which type, what was requested, what came back, and which half
     * — artist or title — rejected it.
     */
    internal fun failureMessage(
        type: EnrichmentType?,
        requested: String,
        returned: String,
        rejectedHalf: String,
    ): String = "identity: type=${type?.name ?: "-"} requested=$requested returned=$returned " +
        "rejected=$rejectedHalf"
}
