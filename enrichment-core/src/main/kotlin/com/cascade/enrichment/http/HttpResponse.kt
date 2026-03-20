package com.cascade.enrichment.http

data class HttpResponse(
    val statusCode: Int,
    val body: String?,
    val headers: Map<String, String> = emptyMap(),
) {
    val isSuccessful: Boolean get() = statusCode in 200..299
    val isRedirect: Boolean get() = statusCode in 300..399
    val isRateLimited: Boolean get() = statusCode == 429
    val isServerError: Boolean get() = statusCode in 500..599
}
