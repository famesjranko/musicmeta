package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.http.DefaultHttpClient
import com.landofoz.musicmeta.http.RateLimiter

/**
 * Shared rate limiters for E2E tests. All test classes in the e2e package
 * use these so that cumulative API load across the full suite is properly
 * throttled, preventing flaky 429 failures.
 */
object E2ETestFixture {
    const val USER_AGENT = "MusicMetaTest/1.0 (https://github.com/famesjranko/musicmeta)"

    val httpClient = DefaultHttpClient(USER_AGENT)
    val mbRateLimiter = RateLimiter(1100) // MusicBrainz: max 1 req/sec
    val defaultRateLimiter = RateLimiter(100)
    val lrcLibRateLimiter = RateLimiter(200)
    val lastFmRateLimiter = RateLimiter(200)
    val itunesRateLimiter = RateLimiter(3000)

    fun prop(name: String): String = System.getProperty(
        name,
        System.getenv(name.replace(".", "_").uppercase()) ?: "",
    )
}
