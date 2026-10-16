package com.landofoz.musicmeta.demoweb

/**
 * How many upstream-bearing requests one client may make in a burst.
 *
 * A visitor working the page hard — a search, a lookup, and a preview button per track — is well
 * inside this; a script is not. Sized for the honest case because the cost of being wrong differs:
 * too small turns away a real visitor, too large only slows an abuser down rather than stopping
 * them, and the admission gate is still there behind it.
 */
internal const val CLIENT_BUDGET_BURST = 20

/**
 * How long one spent request takes to come back, so a client settles at 30 lookups a minute.
 *
 * Above what a person produces and far below what a loop does, which is the only distinction this
 * is asked to make.
 */
internal const val CLIENT_BUDGET_REFILL_MS = 2_000L

/**
 * How many clients are tracked at once. A bucket carries no information once it is full, so the
 * cost of forgetting an idle client is nothing; the eviction is least-recently-*seen*, which is
 * never the client worth remembering.
 */
private const val MAX_TRACKED_CLIENTS = 1_024

/**
 * A token bucket per client, in front of the endpoints that can reach a provider.
 *
 * What it protects is not this process — the admission gate bounds concurrency already — but the
 * upstreams behind it and the egress they cost. One client that never stops asking spends a shared
 * quota that every other visitor and every other consumer of those APIs draws on, and concurrency
 * bounds do not touch that: five at a time, forever, is still forever.
 *
 * One instance per server rather than one per process. The gate is process-wide because it bounds
 * a process-wide cost; this bounds a conversation with a client, and a second server in the same
 * JVM is a second conversation.
 */
internal class ClientBudget(
    private val burst: Int = CLIENT_BUDGET_BURST,
    refillMs: Long = CLIENT_BUDGET_REFILL_MS,
    private val nanoTime: () -> Long = System::nanoTime,
) {

    private val refillNanos = refillMs * 1_000_000

    private class Bucket(var tokens: Int, var refilledAt: Long)

    private val buckets = object : LinkedHashMap<String, Bucket>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>) = size > MAX_TRACKED_CLIENTS
    }

    /** Spends one of [client]'s tokens, or reports that there was none to spend. */
    @Synchronized
    fun admit(client: String): Boolean {
        val now = nanoTime()
        val bucket = buckets.getOrPut(client) { Bucket(burst, now) }
        val earned = ((now - bucket.refilledAt) / refillNanos).toInt()
        if (earned > 0) {
            bucket.tokens = minOf(burst, bucket.tokens + earned)
            // Advancing by whole tokens rather than to `now` keeps the fraction already waited for,
            // so a client polling faster than the refill still earns tokens at the refill's rate.
            bucket.refilledAt += earned * refillNanos
        }
        if (bucket.tokens == 0) return false
        bucket.tokens -= 1
        return true
    }
}

/**
 * Which client a request is charged to.
 *
 * Cloud Run terminates TLS ahead of the container and *appends* the address it saw to whatever
 * `X-Forwarded-For` arrived, so the last entry is the only one in that header a stranger could not
 * write. Everything before it is the caller's own claim: trusting the first entry would let one
 * client mint an unlimited supply of budgets by varying a header.
 *
 * [peer] answers when there is no header at all, which is the local case — nothing is in front of
 * the process, so the socket peer is the client.
 *
 * The parse is specific to one hop. Put a load balancer in front and it appends its own address
 * after the platform's, at which point the last entry is the balancer and every visitor shares one
 * bucket: that deployment needs this function changed, not the numbers above tuned.
 */
internal fun clientKeyFrom(forwardedFor: String?, peer: String): String =
    forwardedFor?.split(',')?.map { it.trim() }?.lastOrNull { it.isNotEmpty() } ?: peer
