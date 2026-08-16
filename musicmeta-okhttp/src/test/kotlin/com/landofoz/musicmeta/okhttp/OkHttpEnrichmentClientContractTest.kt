package com.landofoz.musicmeta.okhttp

import com.landofoz.musicmeta.contract.HttpClientContract
import com.landofoz.musicmeta.http.HttpClient
import okhttp3.OkHttpClient

/** [OkHttpEnrichmentClient] against the shared [HttpClientContract]; `subject()` is all this adds. */
class OkHttpEnrichmentClientContractTest : HttpClientContract() {

    override fun subject(): HttpClient =
        OkHttpEnrichmentClient(OkHttpClient(), userAgent = "musicmeta-contract-test", maxAttempts = 1)
}
