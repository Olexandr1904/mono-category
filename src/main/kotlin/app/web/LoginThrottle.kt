package app.web

import java.time.Instant

/**
 * Sliding-window rate limiter for the login form.
 *
 * Two buckets, deliberately shaped differently:
 *
 * - **Per client.** A hard lockout after [maxPerClient] failures. This is the one that
 *   actually stops a dictionary run from a single source.
 * - **Global.** A short, shallow window that survives IP rotation. It is a *rate limiter,
 *   not a lockout*: capped at [globalWindowSeconds], so an attacker cycling through
 *   addresses is held to roughly [maxGlobal] guesses per minute, while the owner is at
 *   worst delayed by a minute. A long global lockout would hand any passer-by a way to
 *   lock the owner out of their own budget indefinitely, which trades one denial of
 *   service for another.
 *
 * The map is bounded ([maxTrackedClients]) because the key is client-supplied in the
 * general case, and an unbounded map keyed by attacker input is its own denial of service.
 */
class LoginThrottle(
    private val maxPerClient: Int = 10,
    private val clientWindowSeconds: Long = 900,
    private val maxGlobal: Int = 30,
    private val globalWindowSeconds: Long = 60,
    private val maxTrackedClients: Int = 1024,
    private val now: () -> Long = { Instant.now().epochSecond },
) {
    private val failuresByClient = LinkedHashMap<String, ArrayDeque<Long>>()
    private val globalFailures = ArrayDeque<Long>()
    private val lock = Any()

    /** Zero when a login may be attempted; otherwise how many seconds the caller must wait. */
    fun retryAfter(client: String): Long = synchronized(lock) {
        val current = now()
        val perClient = failuresByClient[client]
            ?.also { it.prune(current, clientWindowSeconds) }
            ?.waitFor(current, maxPerClient, clientWindowSeconds)
            ?: 0L
        globalFailures.prune(current, globalWindowSeconds)
        val global = globalFailures.waitFor(current, maxGlobal, globalWindowSeconds)
        maxOf(perClient, global)
    }

    fun recordFailure(client: String) = synchronized(lock) {
        val current = now()
        // Insertion-ordered map used as an LRU: re-inserting moves the key to the end, so
        // the eviction below always drops the least recently active client.
        val bucket = failuresByClient.remove(client) ?: ArrayDeque()
        bucket.prune(current, clientWindowSeconds)
        bucket.addLast(current)
        failuresByClient[client] = bucket
        while (failuresByClient.size > maxTrackedClients) {
            failuresByClient.remove(failuresByClient.keys.first())
        }
        globalFailures.prune(current, globalWindowSeconds)
        globalFailures.addLast(current)
    }

    /** A correct password clears that client's history; the global window still drains on its own. */
    fun recordSuccess(client: String) = synchronized(lock) {
        failuresByClient.remove(client)
        Unit
    }

    private fun ArrayDeque<Long>.prune(current: Long, windowSeconds: Long) {
        while (isNotEmpty() && current - first() >= windowSeconds) removeFirst()
    }

    private fun ArrayDeque<Long>.waitFor(current: Long, limit: Int, windowSeconds: Long): Long {
        if (size < limit) return 0L
        // Blocked until the oldest failure in the window ages out, at which point there is
        // room for exactly one more attempt.
        return (first() + windowSeconds - current).coerceAtLeast(1L)
    }
}
