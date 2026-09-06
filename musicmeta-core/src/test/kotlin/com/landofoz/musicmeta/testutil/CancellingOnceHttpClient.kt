package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.http.HttpClient
import com.landofoz.musicmeta.http.HttpResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cancels the caller and throws on its first `fetchJsonResult` call (either overload), then
 * delegates every later call normally — one call's cancellation for a retry-after-cancellation
 * fixture shared by every HTTP-backed provider test.
 */
class CancellingOnceHttpClient(private val delegate: HttpClient) : HttpClient by delegate {
    private val calls = AtomicInteger()

    override suspend fun fetchJsonResult(url: String): HttpResult<JSONObject> {
        cancelOnFirstCall()
        return delegate.fetchJsonResult(url)
    }

    override suspend fun fetchJsonResult(url: String, headers: Map<String, String>): HttpResult<JSONObject> {
        cancelOnFirstCall()
        return delegate.fetchJsonResult(url, headers)
    }

    // The counter is atomic because "first" must name one call: two callers racing a `var` can both
    // read 1 and both cancel, which is a second cancellation the fixture never promised.
    private suspend fun cancelOnFirstCall() {
        if (calls.incrementAndGet() == 1) {
            currentCoroutineContext()[Job]?.cancel()
            throw CancellationException("simulated cancellation")
        }
    }
}
