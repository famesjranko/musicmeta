package com.landofoz.musicmeta.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * When the enclosing `enrich()` deadline expires, installed by the engine inside its
 * `withTimeoutOrNull`. [BudgetedTransientRetry] reads it to refuse a sleep that would run past the
 * deadline, since a cancelled fan-out loses every provider's in-flight work.
 *
 * Absent when a consumer drives an [HttpClient] directly; the retry then falls back to a fixed
 * two-minute budget. Deliberately not public: a caller outside this module cannot usefully install
 * a deadline the engine will overwrite, and [withRetryBudgetForTest] covers the one case that needs
 * to.
 */
internal class EnrichDeadline(budgetMs: Long) : AbstractCoroutineContextElement(EnrichDeadline) {
    private val atNanos = System.nanoTime() + budgetMs * 1_000_000
    val remainingMs: Long get() = (atNanos - System.nanoTime()) / 1_000_000

    companion object Key : CoroutineContext.Key<EnrichDeadline>
}

/**
 * One attempt's outcome, as [BudgetedTransientRetry] needs to read it: the mapped [HttpResult] plus
 * the `Retry-After` a shed 5xx carried. [HttpResult.ServerError] has no field for that header and
 * gaining one would break every consumer that constructs or `copy()`s it.
 *
 * The *other* thing the ladder needs — whether a [HttpResult.NetworkError] came from the transport
 * (retryable) or from a 2xx body that would not parse (not retryable; a second identical request
 * reproduces it) — is deliberately absent. [BudgetedTransientRetry.execute] catches [IOException]
 * itself, so a transport failure is the exception an implementation lets fly and a parse failure is
 * a `NetworkError` it returns. A flag here would be a flag an implementation forgets, and forgetting
 * it silently disables retry for the failure mode that most needs it.
 *
 * Build one with [asAttempt]; there is no other constructor.
 */
class HttpAttempt<out T> internal constructor(
    val result: HttpResult<T>,
    internal val shedRetryAfterMs: Long?,
)

/**
 * Wraps a mapped result for [BudgetedTransientRetry.execute], reading [retryAfterHeader] — the raw
 * `Retry-After` value, in seconds — where it applies.
 *
 * It applies to a [HttpResult.ServerError] and to nothing else, so pass the header unconditionally
 * and let this decide: a 429 carries its own wait in [HttpResult.RateLimited.retryAfterMs], which
 * the ladder reads from the result itself, and no other result has a wait to honour. Omitting it for
 * a 5xx costs only the fall back to exponential backoff.
 */
fun <T> HttpResult<T>.asAttempt(retryAfterHeader: String? = null): HttpAttempt<T> = HttpAttempt(
    this,
    if (this is HttpResult.ServerError) retryAfterHeader?.toLongOrNull()?.times(1000) else null,
)

/**
 * The budgeted transient-retry ladder, shared by every [HttpClient] implementation.
 *
 * Retries a [HttpResult.RateLimited], a [HttpResult.ServerError] whose code is retryable (502, 503,
 * 504 — a closed list, since 500 carries no implication that a retry helps and 501 never will) and a
 * transport failure, up to [maxAttempts] attempts, honouring `Retry-After`. Load shedding therefore
 * survives whichever code the upstream sheds with, whether it sends one at all, and which method the
 * caller reached for. Everything else — a 4xx, a body that would not parse, a success — surfaces on
 * the first attempt.
 *
 * Every sleep is charged against the enclosing `enrich()` deadline, which only this module can see:
 * a wait that would not fit is refused and the result surfaces unretried, because running past the
 * deadline guarantees a timeout that loses every other provider's in-flight work. With no deadline
 * installed — a consumer driving an [HttpClient] directly — the budget is two minutes.
 *
 * ```kotlin
 * private val retry = BudgetedTransientRetry(attemptTimeoutMs = myConnectPlusReadTimeoutMs)
 *
 * override suspend fun fetchJsonResult(url: String, headers: Map<String, String>) = retry.execute {
 *     val response = myLibrary.get(url, headers)   // an IOException out of here IS a transport failure
 *     mapToHttpResult(response).asAttempt(response.header("Retry-After"))
 * }
 * ```
 *
 * @param attemptTimeoutMs what one attempt against a hanging upstream may spend — for most clients
 *   the connect timeout plus the read timeout. A transport failure is charged this on top of the
 *   wait, because the retry may spend it again before it can fail the same way, and a budget test
 *   that charges only the sleep gives a hang two attempts where there was room for one. A shed
 *   status fails in milliseconds and is charged nothing. Deriving it from a timeout that is disabled
 *   by default (OkHttp's `callTimeout`, which is 0 unless set) yields 0, and a 0 charge is that same
 *   defect with green tests.
 * @param maxAttempts total attempts, not retries: 1 disables the ladder.
 */
class BudgetedTransientRetry(
    private val attemptTimeoutMs: Int = DEFAULT_ATTEMPT_TIMEOUT_MS,
    private val maxAttempts: Int = 3,
) {

    /**
     * Runs [attempt] until it produces a result worth keeping, the attempts run out, or the budget
     * refuses the next wait.
     *
     * Let an [IOException] out of [attempt] to report a transport failure; return a
     * [HttpResult.NetworkError] to report a response that arrived and would not parse. The last
     * attempt's result is what surfaces, exhausted or refused.
     */
    suspend fun <T> execute(attempt: suspend () -> HttpAttempt<T>): HttpResult<T> {
        repeat(maxAttempts - 1) { priorWaits ->
            var transportFailure = false
            val attempted = try {
                attempt()
            } catch (e: IOException) {
                transportFailure = true
                HttpResult.NetworkError(e.message ?: "Network error", e).asAttempt()
            }
            val retryAfterMs = when (val result = attempted.result) {
                is HttpResult.RateLimited -> result.retryAfterMs
                is HttpResult.ServerError ->
                    if (result.statusCode in RETRYABLE) attempted.shedRetryAfterMs else return result
                is HttpResult.NetworkError ->
                    if (transportFailure) null else return result
                else -> return result
            }
            val nextAttemptMs = if (transportFailure) attemptTimeoutMs.toLong() else 0L
            delay(waitMs(retryAfterMs, priorWaits, nextAttemptMs) ?: return attempted.result)
        }
        return try {
            attempt().result
        } catch (e: IOException) {
            HttpResult.NetworkError(e.message ?: "Network error", e)
        }
    }

    /**
     * How long to wait before retrying, or `null` when that wait plus [nextAttemptMs] — what the
     * retry itself may spend before it can fail the same way — does not fit in the time left.
     * Running past the enclosing [EnrichDeadline] guarantees a timeout that loses every other
     * provider's in-flight work, so returning what we already have beats waiting for it.
     */
    private suspend fun waitMs(retryAfterMs: Long?, priorWaits: Int, nextAttemptMs: Long): Long? {
        val base = retryAfterMs ?: 2000L * (1L shl priorWaits)
        val budgetMs = currentCoroutineContext()[EnrichDeadline]?.remainingMs
            ?: (MAX_RETRY_AFTER_SEC * 1000)
        // Compared before jitter, so the ceiling is a hard figure rather than a coin flip near it.
        if (base + nextAttemptMs > budgetMs) return null
        return (base + (base * 0.25 * (Random.nextDouble() * 2 - 1)).toLong()).coerceAtLeast(1000L)
    }

    companion object {
        /** What one attempt may spend when the caller does not say: the default client's timeout. */
        const val DEFAULT_ATTEMPT_TIMEOUT_MS: Int = 10_000
    }
}

/**
 * Runs [block] as though an `enrich()` call with [budgetMs] left enclosed it, so a
 * [BudgetedTransientRetry] inside it refuses any wait that does not fit.
 *
 * **A test utility, and the only reason it is public.** The deadline itself is internal, so without
 * this an `HttpClient` living outside this module has no way to exercise the branch that refuses a
 * sleep — the branch a client with no retry at all also passes, which is how that test rots. Pair it
 * with the same fixture under a generous budget and assert the request counts differ.
 *
 * The budget is real elapsed time, not the virtual clock: `runTest` will not advance it.
 */
suspend fun <T> withRetryBudgetForTest(budgetMs: Long, block: suspend CoroutineScope.() -> T): T =
    withContext(EnrichDeadline(budgetMs)) { block() }

/** Standalone, with no [EnrichDeadline] installed, the ceiling on a wait and everything after it. */
private const val MAX_RETRY_AFTER_SEC = 120L

/**
 * The 5xx codes a fronting proxy emits for "the upstream did not answer, try again", which is how
 * MusicBrainz sheds load. A closed list, not a range: 500 is "something broke" with no implication
 * that a retry helps, 501 is "not implemented" and never will be, and a range test would absorb
 * every future 5xx alongside them.
 */
private val RETRYABLE = setOf(502, 503, 504)
