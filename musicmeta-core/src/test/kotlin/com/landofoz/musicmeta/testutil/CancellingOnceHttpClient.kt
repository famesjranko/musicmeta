package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject

/**
 * Cancels the caller and throws on its first `fetchJsonResult` call (either overload), then
 * delegates every later call normally — one call's cancellation for a retry-after-cancellation
 * fixture shared by every HTTP-backed provider test.
 */
class CancellingOnceHttpClient(private val delegate: HttpClient) : HttpClient by delegate {
    private var calls = 0

    override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> {
        cancelOnFirstCall()
        return delegate.fetchJsonResult(url)
    }

    override suspend fun fetchJsonResult(url: String, headers: Map<String, String>): HttpResult<JSONObject> {
        cancelOnFirstCall()
        return delegate.fetchJsonResult(url, headers)
    }

    private suspend fun cancelOnFirstCall() {
        calls++
        if (calls == 1) {
            currentCoroutineContext()[Job]?.cancel()
            throw CancellationException("simulated cancellation")
        }
    }
}
