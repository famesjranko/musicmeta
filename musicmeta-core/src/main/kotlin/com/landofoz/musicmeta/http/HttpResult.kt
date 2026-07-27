package com.landofoz.musicmeta.http

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
