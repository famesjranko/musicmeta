package com.landofoz.musicmeta.engine

import com.landofoz.musicmeta.CanonicalStatus
import com.landofoz.musicmeta.ContentAttribution
import com.landofoz.musicmeta.ContentLicense
import com.landofoz.musicmeta.EnrichmentCache
import com.landofoz.musicmeta.EnrichmentConfig
import com.landofoz.musicmeta.EnrichmentData
import com.landofoz.musicmeta.EnrichmentLogger
import com.landofoz.musicmeta.EnrichmentRequest
import com.landofoz.musicmeta.EnrichmentResult
import com.landofoz.musicmeta.EnrichmentType
import com.landofoz.musicmeta.ErrorKind
import com.landofoz.musicmeta.LicenseRelation
import com.landofoz.musicmeta.LookupProvenance
import com.landofoz.musicmeta.cache.CacheMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Every read from and write to the [EnrichmentCache] a call makes, and the rules that decide which
 * of them happen: what a cache hit may answer, which key an answer is aliased under, what is safe
 * to persist under this call's canonical status, and what an expired entry may still stand in for.
 *
 * Held by [DefaultEnrichmentEngine], which owns the fan-out and the per-type finalization but
 * delegates the cache decisions here — the engine asks a provider chain a question, this answers
 * whether the question needed asking and whether the answer survives the call.
 */
internal class CachePersistence(
    private val cache: EnrichmentCache,
    private val config: EnrichmentConfig,
    private val logger: EnrichmentLogger,
) {

    /** [runProgressiveFanOut]'s cache-read pass: cache hits, negative-cache hits, and what remains uncached. */
    @Suppress("LoopWithTooManyJumpStatements")
    suspend fun readCacheLayer(
        request: EnrichmentRequest,
        types: Set<EnrichmentType>,
        forceRefresh: Boolean,
    ): CacheLayer {
        val results = mutableMapOf<EnrichmentType, EnrichmentResult>()
        val uncachedTypes = mutableSetOf<EnrichmentType>()
        val negativeCacheHits = mutableSetOf<EnrichmentType>()
        for (type in types) {
            val cached = if (forceRefresh) {
                null
            } else {
                guardedCacheRead(logger, "get") { cache.get(entityKeyFor(request, type), type) }
            }
            val safeCached = safeFreshCacheHit(cached?.result, type)
            // A cached Success answering nothing is a *miss*, not a NotFound. An empty entry written
            // by an older build would otherwise outlive this fix by the type's TTL — 90 days for
            // GENRE — re-demoted on every call and never refetched. Leaving the type uncached lets
            // the providers run and the write-back overwrite it, so the entry heals itself.
            // An entry whose genre tags never learned whether they were curated takes the same route
            // for the same reason: see hasUnknownGenreCuration.
            if (safeCached != null) {
                results[type] = withCacheProvenanceFallback(safeCached)
                continue
            }
            // A manual selection is a value the caller chose, not a freshness promise. A pinned
            // positive therefore remains readable after its TTL. A marker with no value stays a
            // miss so the first positive fill can establish the selected value.
            val pinned = !forceRefresh && pinState(entityKeyFor(request, type), type) == PinState.PINNED
            if (pinned) {
                val pinnedEntry = guardedCacheRead(logger, "getIncludingExpired") {
                    cache.getIncludingExpired(entityKeyFor(request, type), type)
                }
                val safePinned = pinnedEntry?.result?.let(::suppressUnsafeWikipedia)
                if (safePinned is EnrichmentResult.Success && safePinned.data.answers(type)) {
                    results[type] = withCacheProvenanceFallback(safePinned)
                    continue
                }
            }
            // A fresh negative entry answers "providers had nothing" without a re-ask; the read is
            // skipped on refresh and for selections, where a past absence must not block a first fill.
            val negative = if (forceRefresh || pinned) {
                null
            } else {
                guardedCacheRead(logger, "getNegative") { cache.getNegative(entityKeyFor(request, type), type) }
            }
            if (negative != null) {
                results[type] = negative.result
                negativeCacheHits.add(type)
            } else {
                uncachedTypes.add(type)
            }
        }
        return CacheLayer(results, uncachedTypes, negativeCacheHits)
    }

    private fun safeFreshCacheHit(
        result: EnrichmentResult.Success?,
        type: EnrichmentType,
    ): EnrichmentResult.Success? {
        val safe = result?.let(::suppressUnsafeWikipedia) as? EnrichmentResult.Success ?: return null
        return safe.takeIf { it.data.answers(type) && !it.data.hasUnknownGenreCuration(type) }
    }

    suspend fun writeBack(
        request: EnrichmentRequest,
        resolvedRequest: EnrichmentRequest,
        results: Map<EnrichmentType, EnrichmentResult>,
        context: WriteBackContext,
    ) {
        val resolvedMbid = context.identityResolution.identifiers.musicBrainzId
        val canonicalStatus = context.identityResolution.status
        for ((type, result) in results) {
            val aliasKey = aliasKeyFor(request, resolvedRequest, resolvedMbid, type)
            val identifierIncomplete = context.chainExecutions[type]?.identifierIncomplete == true
            val filterEmptied = type in context.filterEmptied
            val staleDerived = type in context.staleDerived
            val cacheable =
                isCacheableNegative(result, canonicalStatus, identifierIncomplete, filterEmptied, staleDerived)
            when {
                // A negative served from cache this call is not re-put: its short TTL is the entry's
                // freshness contract, and a cache hit must not extend it.
                cacheable && type !in context.negativeCacheHits ->
                    writeNegative(request, aliasKey, type, result as EnrichmentResult.NotFound, canonicalStatus)
                isCacheablePositive(result, canonicalStatus) ->
                    writePositive(request, aliasKey, type, result as EnrichmentResult.Success, canonicalStatus)
            }
        }
    }

    /**
     * The name-alias key when identity resolution added an MBID, so a future name-only lookup
     * finds MBID-resolved data — shared by both write branches below, so a negative write ends up
     * under exactly the same keys a Success would. Force refresh and [invalidateKeys] clear that
     * alias once identity resolution has recovered its canonical names. A request that named no
     * entity has no caller name to alias under, so it takes
     * MusicBrainz's canonical one — the same name a later name-only lookup would ask with.
     *
     * Never fires for a request carrying caller-supplied identifiers: a caller name is not an
     * equivalence proof for those identifiers. The only identifier-bearing alias is the canonical
     * name learned during actual identity resolution.
     */
    private fun aliasKeyFor(
        request: EnrichmentRequest,
        resolvedRequest: EnrichmentRequest,
        resolvedMbid: String?,
        type: EnrichmentType,
    ): String? = when {
        namesNoEntity(request) && !namesNoEntity(resolvedRequest) -> entityKeyForName(resolvedRequest, type)
        resolvedMbid != null && request.identifiers.musicBrainzId == null &&
            entityKeyFor(request, type) == entityKeyForName(request, type) -> entityKeyForName(request, type)
        else -> null
    }

    /**
     * Whether a result reached under this call's canonical status is safe to cache: [CanonicalStatus.RESOLVED]
     * or any `NOT_ATTEMPTED_*` reason — never [CanonicalStatus.AMBIGUOUS], [CanonicalStatus.UNRESOLVED],
     * [CanonicalStatus.FAILED], or [CanonicalStatus.RESOLVING], each of which means this call's fan-out
     * ran (or is still running) on an unconfirmed identity.
     */
    private fun CanonicalStatus.isCacheable(): Boolean = this !in UNCACHEABLE_STATUSES

    /**
     * Only a real fan-out "providers had nothing" qualifies for negative caching: never a chain
     * that skipped a provider for an identifier this call never had ([identifierIncomplete]), never
     * one [DefaultEnrichmentEngine.finalizeResult] produced by catalog-filtering a `Success` down
     * to nothing ([filterEmptied]) — that emptiness describes the local catalog, not an upstream
     * provider, whether the `Success` it emptied was this call's own live answer or a stale-cache
     * substitute — never one whose finalized value is itself a stale-cache substitute, or a
     * composite synthesized from one ([staleDerived]), since a stale substitute is a past call's
     * snapshot rather than this call's own answer, and never one reached under a canonical identity
     * that did not resolve. Decided from the call's own [canonicalStatus] and those three per-type
     * facts — a `NotFound` carries no per-result canonical fact of its own, so it is never consulted
     * here.
     */
    fun isCacheableNegative(
        result: EnrichmentResult,
        canonicalStatus: CanonicalStatus,
        identifierIncomplete: Boolean,
        filterEmptied: Boolean,
        staleDerived: Boolean,
    ): Boolean =
        result is EnrichmentResult.NotFound &&
            !identifierIncomplete &&
            !filterEmptied &&
            !staleDerived &&
            canonicalStatus.isCacheable()

    /**
     * A `Success` reached while canonical resolution was attempted and did not resolve
     * (`AMBIGUOUS`/`UNRESOLVED`/`FAILED`) is a fuzzy or ambiguous guess — caching it would serve it
     * as a cache hit for the type's whole TTL with no way to tell it apart from a confident one,
     * and a retry could never heal or re-offer the suggestions that produced it.
     */
    private fun isCacheablePositive(result: EnrichmentResult, canonicalStatus: CanonicalStatus): Boolean =
        result is EnrichmentResult.Success &&
            suppressUnsafeWikipedia(result) is EnrichmentResult.Success &&
            !result.isStale &&
            canonicalStatus.isCacheable()

    /**
     * Wikipedia prose without article credit is refetched, and
     * a Wikipedia file without file attribution is withheld. Other-provider artwork alternatives
     * remain usable; a safe alternative becomes the primary image when the old primary is unsafe.
     */
    internal fun suppressUnsafeWikipedia(
        result: EnrichmentResult.Success,
    ): EnrichmentResult = when (val data = result.data) {
        is EnrichmentData.Biography -> when {
            result.provider != WIKIPEDIA -> result
            data.attribution == null -> EnrichmentResult.NotFound(result.type, WIKIPEDIA)
            // Article credit establishes no rights for a separate thumbnail file.
            else -> result.copy(data = data.copy(thumbnailUrl = null))
        }
        is EnrichmentData.Artwork -> {
            val safeAlternatives = data.alternatives.orEmpty().filter {
                it.provider != WIKIPEDIA || it.attribution.isReusableWikipediaFile()
            }
            if (result.provider != WIKIPEDIA || data.attribution.isReusableWikipediaFile()) {
                result.copy(data = data.copy(alternatives = safeAlternatives.takeIf { it.isNotEmpty() }))
            } else {
                val replacement = safeAlternatives.firstOrNull()
                if (replacement == null) EnrichmentResult.NotFound(result.type, WIKIPEDIA) else result.copy(
                    provider = replacement.provider,
                    data = EnrichmentData.Artwork(
                        url = replacement.url,
                        thumbnailUrl = replacement.thumbnailUrl,
                        sizes = replacement.sizes,
                        alternatives = safeAlternatives.drop(1).takeIf { it.isNotEmpty() },
                        attribution = replacement.attribution,
                    ),
                )
            }
        }
        else -> result
    }

    /** A Wikipedia file is reusable only when its complete source facts establish one safe licence. */
    private fun ContentAttribution?.isReusableWikipediaFile(): Boolean {
        val attribution = this ?: return false
        if (!attribution.resourceId.startsWith("File:") || !attribution.sourceUrl.isSafeHttpsUrl()) return false
        if (attribution.nonFree != false || attribution.restrictions != emptyList<String>()) return false
        val licenses = attribution.licenses
        if (licenses.isEmpty() || attribution.licenseRelation == LicenseRelation.UNKNOWN) return false
        if (licenses.any { !it.isSupportedWikipediaLicense() }) return false
        val publicDomain = licenses.all { it.isPublicDomainLicense() }
        if (attribution.copyrighted != !publicDomain || attribution.attributionRequired != !publicDomain) return false
        if (
            !publicDomain &&
            attribution.attributionText.isNullOrBlank() && attribution.creator.isNullOrBlank()
        ) return false
        return attribution.usageTerms == null || licenses.any { it.matchesWikipediaUsageTerms(attribution.usageTerms) }
    }

    private fun String.isSafeHttpsUrl(): Boolean {
        if (any { it.isWhitespace() || Character.isISOControl(it) }) return false
        if (hasEncodedControl()) return false
        val uri = try {
            URI(this)
        } catch (_: Exception) {
            return false
        }
        return uri.scheme.equals("https", ignoreCase = true) && uri.host != null && uri.rawUserInfo == null
    }

    private fun String.hasEncodedControl(): Boolean {
        val decodedPercent = ENCODED_PERCENT.replace(this, "%")
        for (match in ENCODED_CONTROL.findAll(decodedPercent)) {
            val byte = match.value.substring(1, 3).toInt(16)
            if (byte <= 0x1F || byte == 0x7F || decodedC1ByteIsUnsafe(decodedPercent, match.range.first)) {
                return true
            }
        }
        return false
    }

    private fun decodedC1ByteIsUnsafe(value: String, percentIndex: Int): Boolean {
        val byte = value.substring(percentIndex + 1, percentIndex + 3).toInt(16)
        if (byte !in 0x80..0x9F) return false

        var start = percentIndex
        while (start >= 3 && value[start - 3] == '%' && value.substring(start - 2, start).all(::isHexDigit)) {
            start -= 3
        }
        var end = percentIndex + 3
        while (end + 2 < value.length && value[end] == '%' && value.substring(end + 1, end + 3).all(::isHexDigit)) {
            end += 3
        }
        val bytes = (start until end step 3).map {
            value.substring(it + 1, it + 3).toInt(16).toByte()
        }.toByteArray()
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
                .any(Char::isISOControl)
        } catch (_: CharacterCodingException) {
            true
        }
    }

    private fun isHexDigit(char: Char): Boolean = char in '0'..'9' || char.lowercaseChar() in 'a'..'f'

    private fun ContentLicense.isSupportedWikipediaLicense(): Boolean {
        val expectedUrl = when {
            identifier == "Public domain" -> return url == null
            identifier == "CC0" -> "https://creativecommons.org/publicdomain/zero/1.0"
            else -> {
                val match = Regex("CC (BY|BY-SA) (1.0|2.0|2.5|3.0|4.0)").matchEntire(identifier) ?: return false
                "https://creativecommons.org/licenses/${match.groupValues[1].lowercase()}/${match.groupValues[2]}"
            }
        }
        return url?.trimEnd('/') == expectedUrl
    }

    private fun ContentLicense.isPublicDomainLicense(): Boolean = identifier == "Public domain" || identifier == "CC0"

    private fun ContentLicense.matchesWikipediaUsageTerms(terms: String): Boolean {
        if (identifier == terms) return true
        val longName = when {
            identifier == "CC0" -> "Creative Commons CC0 1.0 Universal"
            identifier.startsWith("CC BY-SA ") ->
                "Creative Commons Attribution Share Alike ${identifier.substringAfterLast(' ')}"
            identifier.startsWith("CC BY ") -> "Creative Commons Attribution ${identifier.substringAfterLast(' ')}"
            else -> return false
        }
        fun normalized(value: String) = value.lowercase().replace(Regex("[-\\s]"), "")
        return normalized(terms) == normalized(longName)
    }

    private suspend fun writeNegative(
        request: EnrichmentRequest,
        aliasKey: String?,
        type: EnrichmentType,
        result: EnrichmentResult.NotFound,
        canonicalStatus: CanonicalStatus,
    ) {
        writeNegativeAt(entityKeyFor(request, type), type, result, canonicalStatus)
        if (aliasKey != null) {
            writeNegativeAt(aliasKey, type, result, canonicalStatus)
        }
    }

    private suspend fun writePositive(
        request: EnrichmentRequest,
        aliasKey: String?,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
        canonicalStatus: CanonicalStatus,
    ) {
        val ttl = config.ttlOverrides[type] ?: type.defaultTtlMs
        writePositiveAt(entityKeyFor(request, type), type, result, canonicalStatus, ttl)
        if (aliasKey != null) {
            writePositiveAt(aliasKey, type, result, canonicalStatus, ttl)
        }
    }

    private suspend fun writePositiveAt(
        key: String,
        type: EnrichmentType,
        result: EnrichmentResult.Success,
        canonicalStatus: CanonicalStatus,
        ttl: Long,
    ) {
        if (!mayWritePositive(key, type)) return
        guardedCacheWrite(logger, "put") { cache.put(key, type, result, canonicalStatus, ttl) }
    }

    private suspend fun writeNegativeAt(
        key: String,
        type: EnrichmentType,
        result: EnrichmentResult.NotFound,
        canonicalStatus: CanonicalStatus,
    ) {
        if (pinState(key, type) != PinState.UNPINNED) return
        guardedCacheWrite(logger, "putNegative") {
            cache.putNegative(key, type, result, canonicalStatus, config.negativeTtlMs)
        }
    }

    /**
     * A failed pin-state read is deliberately fail-closed for persistence. Cache reads may still
     * fall through to providers, but an unreadable selection must never become permission to
     * replace data a caller selected.
     */
    private suspend fun mayWritePositive(key: String, type: EnrichmentType): Boolean = when (pinState(key, type)) {
        PinState.UNPINNED -> true
        PinState.UNKNOWN -> false
        PinState.PINNED -> try {
            cache.getIncludingExpired(key, type) == null
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("EnrichmentCache", "Cache getIncludingExpired failed; skipping persistence: ${e.message}", e)
            false
        }
    }

    private suspend fun pinState(key: String, type: EnrichmentType): PinState = try {
        if (cache.isManuallySelected(key, type)) PinState.PINNED else PinState.UNPINNED
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        logger.warn("EnrichmentCache", "Cache isManuallySelected failed; skipping persistence: ${e.message}", e)
        PinState.UNKNOWN
    }

    private enum class PinState { PINNED, UNPINNED, UNKNOWN }

    suspend fun invalidateForRefresh(request: EnrichmentRequest, types: Set<EnrichmentType>) {
        for (type in types) {
            for (key in cacheKeysFor(request, type)) {
                guardedCacheWrite(logger, "invalidate") { cache.invalidate(key, type) }
            }
        }
    }

    /**
     * The canonical-name alias a forced call could not clear on the way in: the request named no
     * entity then, and the name the alias sits under is the one identity resolution has just
     * learned.
     */
    suspend fun invalidateResolvedNameAlias(resolvedRequest: EnrichmentRequest, types: Set<EnrichmentType>) {
        for (type in types) {
            guardedCacheWrite(logger, "invalidate") {
                cache.invalidate(entityKeyForName(resolvedRequest, type), type)
            }
        }
    }

    /**
     * Exact-bearing requests invalidate only their complete primary tuple. A caller-supplied name
     * is not an equivalence proof, so clearing its bare-name key could evict another entity's
     * answer. Canonical aliases are added by [invalidateKeys] only after identity resolution has
     * supplied the canonical names.
     */
    private fun cacheKeysFor(request: EnrichmentRequest, type: EnrichmentType): List<String> =
        listOf(entityKeyFor(request, type))

    /** Invalidates the primary tuple and a canonical-name alias only when resolution supplied it. */
    suspend fun invalidateKeys(
        request: EnrichmentRequest,
        named: EnrichmentRequest,
        type: EnrichmentType,
    ) {
        val keys = cacheKeysFor(request, type) +
            if (named !== request) listOf(entityKeyForName(named, type)) else emptyList()
        for (key in keys.distinct()) cache.invalidate(key, type)
    }

    /**
     * A cache hit reports [LookupProvenance.CACHE] instead of `null` when the [EnrichmentCache]
     * implementation that served it did not preserve the original live lookup's route — never
     * `null`, or a consumer reading absence as confident inherits the same hole [CanonicalStatus]
     * closed for canonical resolution. A preserving cache (both shipped implementations) replays
     * the original route verbatim and never reaches this branch; it exists for one that does not.
     */
    private fun withCacheProvenanceFallback(result: EnrichmentResult.Success): EnrichmentResult.Success =
        if (result.provenance == null) result.copy(provenance = LookupProvenance.CACHE) else result

    /**
     * [config.cacheMode]'s `STALE_IF_ERROR` clause for one type: a fresh `Error`/`RateLimited` is
     * replaced by an expired cache entry that still answers the type, marked [EnrichmentResult.Success.isStale].
     * Any other result — including a fresh `Success` or `NotFound` — passes through unchanged.
     *
     * [ErrorKind.ENGINE_CLOSED] is never substituted, even under `STALE_IF_ERROR`: it is the one
     * `Error` this engine stamps itself, after [DefaultEnrichmentEngine.close] — that method's own
     * KDoc promises every unsettled requested type becomes that Error, unconditionally, and a stale
     * cache hit silently standing in for it would break that promise for exactly the caller relying
     * on it to notice a shutdown. [ErrorKind.TIMEOUT], stamped the same way for a different reason,
     * keeps the normal substitution.
     */
    suspend fun applyStaleCacheToType(
        request: EnrichmentRequest,
        type: EnrichmentType,
        result: EnrichmentResult,
    ): EnrichmentResult {
        if (config.cacheMode != CacheMode.STALE_IF_ERROR) return result
        if (result !is EnrichmentResult.Error && result !is EnrichmentResult.RateLimited) return result
        if (result is EnrichmentResult.Error && result.errorKind == ErrorKind.ENGINE_CLOSED) return result
        val stale = guardedCacheRead(logger, "getIncludingExpired") {
            cache.getIncludingExpired(entityKeyFor(request, type), type)
        }
        // A stale entry that answers nothing is worse than the Error it would replace: the Error at
        // least tells the consumer to retry.
        val safeStale = stale?.result?.let(::suppressUnsafeWikipedia)
        return if (safeStale is EnrichmentResult.Success && safeStale.data.answers(type)) {
            withCacheProvenanceFallback(safeStale).copy(isStale = true)
        } else {
            result
        }
    }

    private companion object {
        private const val WIKIPEDIA = "wikipedia"
        val ENCODED_PERCENT = Regex("(?i)%25")
        val ENCODED_CONTROL = Regex(
            "(?i)%(?:0[0-9a-f]|1[0-9a-f]|7f|[89][0-9a-f]|" +
                "25(?:0[0-9a-f]|1[0-9a-f]|7f|[89][0-9a-f]))",
        )

        // RESOLVING never actually reaches isCacheable(): writeBack only runs with the real,
        // settled session.identityResolution. Listed anyway so a future caller of isCacheable()
        // against a live IdentityHolder.current can't accidentally treat an in-progress
        // resolution as safe to cache.
        private val UNCACHEABLE_STATUSES = setOf(
            CanonicalStatus.AMBIGUOUS, CanonicalStatus.UNRESOLVED, CanonicalStatus.FAILED,
            CanonicalStatus.RESOLVING,
        )
    }
}
