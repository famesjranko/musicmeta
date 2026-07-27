package com.landofoz.musicmeta.http

import java.io.IOException

/**
 * Typed HTTP response that preserves status information for callers.
 * Unlike the nullable returns of [HttpClient.fetchJson], this captures
 * the specific failure mode so providers can react differently to
 * 404 (not found) vs 429 (rate limited) vs 500 (server error).
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

/** The body on success, `null` on every failure. For endpoints that send no credentials. */
internal fun <T> HttpResult<T>.bodyOrNull(): T? = (this as? HttpResult.Ok)?.body

/**
 * The body on success, `null` on any other failure — but a 401 or 403 throws [AuthException],
 * because a rejected key is a configuration error the consumer can act on, not empty data.
 *
 * Only for a call that actually sends credentials. On a public endpoint an upstream 403 is not the
 * consumer's key being wrong — there is no key — so it must stay a plain `null`.
 */
internal fun <T> HttpResult<T>.bodyOrThrowAuth(): T? = when (this) {
    is HttpResult.Ok -> body
    is HttpResult.ClientError ->
        if (statusCode == 401 || statusCode == 403) throw AuthException(statusCode) else null
    else -> null
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
 * `Error(ErrorKind.NETWORK)`, which the chain records as a breaker *failure*. Collapsed to `null` it
 * would be a `NotFound` — a breaker *success* — and the provider would look healthy while a rate
 * limit made it answer "no results" to everything.
 */
internal fun <T> HttpResult<T>.bodyOrThrowTransient(): T? = when (this) {
    is HttpResult.Ok -> body
    is HttpResult.ClientError -> null
    is HttpResult.RateLimited -> throw IOException("HTTP 429: rate limited")
    is HttpResult.ServerError -> throw IOException("HTTP $statusCode: server error")
    is HttpResult.NetworkError -> throw IOException(message, cause)
}
