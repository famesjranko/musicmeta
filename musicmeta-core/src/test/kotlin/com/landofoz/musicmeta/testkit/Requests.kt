package com.landofoz.musicmeta.testkit

import com.landofoz.musicmeta.testutil.FakeHttpClient
import org.junit.Assert.assertTrue

/**
 * No upstream URL is requested twice. Per-URL, never a total: a total call budget breaks on every
 * legitimate provider addition and gets bumped rather than read (`docs/pitfalls.md:423-427`).
 *
 * **Scope is the fake, not one `enrich()`.** [FakeHttpClient.requestedUrls] accumulates for the life
 * of the client, so this covers every request that fake has ever seen. Use one fake per `enrich()` —
 * which is what [UpstreamPools.load] hands you — or a second call legitimately repeating a URL reads
 * here as a duplicate.
 */
internal fun FakeHttpClient.assertNoUrlRequestedTwice() {
    val duplicates = requestedUrls.groupingBy { it }.eachCount().filterValues { it > 1 }
    assertTrue(
        "URL(s) requested more than once by this client: $duplicates",
        duplicates.isEmpty(),
    )
}

/** How many requests hit URLs containing [fragment] — for a per-kind memo assertion. */
internal fun FakeHttpClient.countMatching(fragment: String): Int =
    requestedUrls.count { it.contains(fragment) }
