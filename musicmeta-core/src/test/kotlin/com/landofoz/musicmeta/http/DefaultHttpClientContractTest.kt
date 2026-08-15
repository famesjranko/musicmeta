package com.landofoz.musicmeta.http

import com.landofoz.musicmeta.contract.HttpClientContract

/** [DefaultHttpClient] against the shared [HttpClientContract]; `subject()` is all this adds. */
class DefaultHttpClientContractTest : HttpClientContract() {

    override fun subject(): HttpClient = DefaultHttpClient(userAgent = "musicmeta-contract-test", maxRetries = 1)
}
