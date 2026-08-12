package com.landofoz.musicmeta.http

import java.io.IOException

/**
 * Typed HTTP response that preserves status information for callers.
 * Every [HttpClient] method returns one, so providers can react differently to
 * 404 (not found) vs 429 (rate limited) vs 500 (server error) instead of seeing
 * one indistinguishable failure.
 */
sealed class HttpResult<out T> {
    data class Ok<T>(val body: T, val statusCode: Int = 200) : HttpResult<T>()
    data class ClientError(val statusCode: Int, val body: String? = null) : HttpResult<Nothing>()
    data class ServerError(val statusCode: Int, val body: String? = null) : HttpResult<Nothing>()
    data class RateLimited(val retryAfterMs: Long? = null) : HttpResult<Nothing>()
    data class NetworkError(val message: String, val cause: Throwable? = null) : HttpResult<Nothing>()
}

/**
 * A provider's credentials were rejected (HTTP 401/403).
 *
 * Thrown rather than returned so it travels the path a provider already has: the provider's
 * `catch (e: Exception) { mapError(type, e) }` turns it into an
 * `Error(errorKind = ErrorKind.AUTH)`, which `ProviderChain` records as a breaker *failure*.
 * A bad key collapsed to `null` here would become `NotFound` — a breaker *success* — and the
 * provider would look healthy while returning nothing forever (`docs/pitfalls.md` §4).
 */
internal class AuthException(statusCode: Int) : Exception("HTTP $statusCode: credentials rejected")

/**
 * A 429 that outlived the retry ladder. An [IOException] subtype so every existing
 * `catch (e: IOException)` and the `is IOException -> ErrorKind.NETWORK` fallback still see it;
 * [com.landofoz.musicmeta.EnrichmentProvider.mapError] narrows it to
 * [com.landofoz.musicmeta.ErrorKind.RATE_LIMIT] ahead of that branch, which is what lets the engine
 * hand a consumer an [com.landofoz.musicmeta.EnrichmentResult.RateLimited] carrying [retryAfterMs].
 */
internal class RateLimitException(val retryAfterMs: Long?) : IOException("HTTP 429: rate limited")

/**
 * [bodyOrThrowTransient], plus: a 401 or 403 throws [AuthException], because a rejected key is a
 * configuration error the consumer can act on, not empty data.
 *
 * Only for a call that actually sends credentials. On a public endpoint an upstream 403 is not the
 * consumer's key being wrong — there is no key — so those callers use [bodyOrThrowTransient],
 * which leaves every `ClientError` a plain `null`.
 */
internal fun <T> HttpResult<T>.bodyOrThrowAuthOrTransient(): T? = when {
    this is HttpResult.ClientError && (statusCode == 401 || statusCode == 403) ->
        throw AuthException(statusCode)
    else -> bodyOrThrowTransient()
}

/**
 * The body on success, `null` on a [HttpResult.ClientError] — a genuine "no such thing" — and a
 * thrown [IOException] on a transient transport failure (429, 5xx, network drop).
 *
 * For a search endpoint, where `null` becomes an empty result list. An empty list is
 * indistinguishable from a rate-limit or a dropped connection collapsed to `null`, and at identity
 * resolution that difference decides the whole run: a `NotFound` carrying `suggestions`
 * short-circuits the entire provider fan-out, so a 429 answered this way refuses a query the
 * upstream would have matched perfectly.
 *
 * Throwing is the same trick [AuthException] uses, for the same reason (`docs/pitfalls.md` §4): it
 * travels the provider's existing `catch (e: Exception) { mapError(type, e) }` into an
 * `Error(ErrorKind.NETWORK)` — or `ErrorKind.RATE_LIMIT` for the 429, which the chain widens back to
 * `EnrichmentResult.RateLimited` — which the chain records as a breaker *failure*. Collapsed to `null` it
 * would be a `NotFound` — a breaker *success* — and the provider would look healthy while a rate
 * limit made it answer "no results" to everything.
 */
internal fun <T> HttpResult<T>.bodyOrThrowTransient(): T? = when (this) {
    is HttpResult.Ok -> body
    is HttpResult.ClientError -> null
    is HttpResult.RateLimited -> throw RateLimitException(retryAfterMs)
    is HttpResult.ServerError -> throw IOException("HTTP $statusCode: server error")
    is HttpResult.NetworkError -> throw IOException(message, cause)
}
