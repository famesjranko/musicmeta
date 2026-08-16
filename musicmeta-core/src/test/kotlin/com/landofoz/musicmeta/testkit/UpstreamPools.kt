package com.landofoz.musicmeta.testkit

import com.landofoz.musicmeta.testutil.FakeHttpClient
import org.json.JSONObject

/**
 * Loads a scenario's canned upstream bodies onto a [FakeHttpClient]. The only fixture route.
 *
 * Reads `musicmeta-core/src/test/resources/pools/<scenario>/manifest.json` — a flat
 * `{ "<file>": "<urlContains>" }` map — and feeds each named file to
 * [FakeHttpClient.givenJsonResponse] under its mapped `urlContains`. A missing scenario or a missing
 * file throws with the full classpath path; there is no silent empty-string fallback, which is how a
 * harness passes by testing nothing.
 *
 * **Why a manifest, not the filename itself.** A `urlContains` substring is not always
 * filesystem-safe — iTunes' album search is distinguished from its artist-albums lookup only by
 * `search?media=music&entity=album` versus `lookup?id=` — and two calls to one provider can share a
 * substring that must stay distinct, as MusicBrainz's release-group lookups do (`inc=releases`
 * versus `inc=url-rels`). The manifest lets the file be named for what a reviewer wants to see in a
 * diff while the exact matching substring, however unfriendly, lives beside it.
 *
 * **Fragments may not overlap, and that is enforced rather than documented.** [FakeHttpClient]
 * resolves a URL against the first stub whose key it contains, in insertion order, and
 * `JSONObject.keys()` has no defined order — so two fragments where one contains the other would
 * pick a winner that varies between runs. [loadOnto] rejects that pair outright: an ambiguous
 * manifest is the author's to resolve, not the loader's to guess at.
 *
 * `shapes.md` § 3 owns what may go in a pool file; that constraint is legal, not stylistic.
 */
internal object UpstreamPools {

    fun load(scenario: String): FakeHttpClient = FakeHttpClient().also { loadOnto(it, scenario) }

    fun loadOnto(http: FakeHttpClient, scenario: String) {
        val manifest = JSONObject(resource(scenario, MANIFEST))
        // Sorted so the load order is the same on every run whatever org.json's hashing does;
        // the overlap check below is what makes that order irrelevant to the outcome.
        val entries = manifest.keys().asSequence().sorted().map { it to manifest.getString(it) }.toList()
        rejectOverlappingFragments(scenario, entries)
        entries.forEach { (file, urlContains) -> http.givenJsonResponse(urlContains, resource(scenario, file)) }
    }

    private fun rejectOverlappingFragments(scenario: String, entries: List<Pair<String, String>>) {
        for ((fileA, a) in entries) {
            for ((fileB, b) in entries) {
                val overlaps = fileA != fileB && (a.contains(b) || b.contains(a))
                check(!overlaps) {
                    "pool $scenario: fragments overlap, so which stub answers a matching URL depends " +
                        "on load order — \"$a\" ($fileA) and \"$b\" ($fileB). Make one of them more specific."
                }
            }
        }
    }

    private fun resource(scenario: String, file: String): String =
        checkNotNull(object {}.javaClass.getResourceAsStream("/pools/$scenario/$file")) {
            "missing pool resource: /pools/$scenario/$file"
        }.readBytes().decodeToString()

    private const val MANIFEST = "manifest.json"
}
