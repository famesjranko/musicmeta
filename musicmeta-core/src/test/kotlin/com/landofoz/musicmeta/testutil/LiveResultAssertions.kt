package com.landofoz.musicmeta.testutil

import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.ErrorKind

/**
 * Asserts that a result from a live upstream is not drift, and returns the
 * [EnrichmentResult.Success] whose content the caller should go on to assert — or `null` when the
 * request was shed before the upstream answered, so there is no content and nothing to conclude.
 *
 * Accepted: `Success`; [EnrichmentResult.RateLimited]; and an [EnrichmentResult.Error] of kind
 * [ErrorKind.NETWORK], [ErrorKind.RATE_LIMIT] or [ErrorKind.TIMEOUT]. A request the upstream never
 * answered says nothing about the upstream's schema, and a suite of a few hundred live requests
 * meets several of them every run.
 *
 * Rejected: [EnrichmentResult.NotFound], because the upstream did answer and said the entity does
 * not exist; every other [ErrorKind], because it answered in a way the mapper could not read; and
 * `null`, because a requested type must settle to something.
 *
 * Call it as `val x = assertNotDrift("…", result) ?: return@runBlocking`, so the content assertions
 * below run on the answers and are skipped on the sheds.
 */
fun assertNotDrift(message: String, result: EnrichmentResult?): EnrichmentResult.Success? =
    when (result) {
        is EnrichmentResult.Success -> result

        is EnrichmentResult.RateLimited -> {
            println("  shed ($message): throttled, content not checked — $result")
            null
        }

        is EnrichmentResult.Error -> when (result.errorKind) {
            ErrorKind.NETWORK, ErrorKind.RATE_LIMIT, ErrorKind.TIMEOUT -> {
                println("  shed ($message): never reached the upstream, content not checked — $result")
                null
            }

            else -> throw AssertionError(
                "$message: the upstream answered and the result did not survive the mapper — $result",
            )
        }

        is EnrichmentResult.NotFound ->
            throw AssertionError("$message: the upstream answered that this does not exist — $result")

        null -> throw AssertionError("$message: the requested type never settled")
    }
