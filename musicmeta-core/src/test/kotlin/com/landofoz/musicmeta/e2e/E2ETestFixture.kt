package com.landofoz.musicmeta.e2e

import com.landofoz.musicmeta.http.DefaultHttpClient
import com.landofoz.musicmeta.http.RateLimiter
import com.landofoz.musicmeta.testutil.E2E_CREDENTIAL_SPECS
import com.landofoz.musicmeta.testutil.E2eCredentials

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
    val listenBrainzRateLimiter = RateLimiter(400) // measured 2026-07-27: 30 req/10s, with headroom
    val discogsRateLimiter = RateLimiter(1000) // measured 2026-07-28: x-discogs-ratelimit: 60 in live response headers
    val itunesRateLimiter = RateLimiter(3000)

    private val credentialNames = E2E_CREDENTIAL_SPECS.map { it.property }.toSet()
    private val credentials by lazy {
        E2eCredentials.resolve(E2eCredentials.checkoutRoot(), System::getProperty, System::getenv)
    }

    fun prop(name: String): String = if (name in credentialNames) {
        credentials[name].orEmpty()
    } else {
        System.getProperty(name, System.getenv(name.replace(".", "_").uppercase()).orEmpty())
    }
}
