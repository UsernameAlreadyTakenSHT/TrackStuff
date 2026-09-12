package com.example.trackstuff.data.remote

import java.util.concurrent.ConcurrentHashMap

/**
 * Explicit "refresh" actions bypass the HTTP cache, so they are allowed at most once per [INTERVAL_MS]
 * per key (a Discover source, a title). Automatic loads are never limited: they always go through the cache.
 * The timestamps are persisted through [persist] so that restarting the app does not reset the limit.
 */
object RefreshLimiter {
    const val INTERVAL_MS = 60 * 60 * 1000L
    private val lastAt = ConcurrentHashMap<String, Long>()

    /** Called with the full map after each acquisition; the app wires it to the settings store. */
    @Volatile var persist: ((Map<String, Long>) -> Unit)? = null

    /** Restores previously persisted timestamps (expired entries are dropped). */
    fun load(saved: Map<String, Long>) {
        val now = System.currentTimeMillis()
        saved.forEach { (k, t) -> if (now - t < INTERVAL_MS) lastAt[k] = t }
    }

    /** Minutes to wait before [key] may be force-refreshed again, or null when allowed now. */
    fun waitMinutes(key: String): Long? {
        val elapsed = System.currentTimeMillis() - (lastAt[key] ?: return null)
        if (elapsed >= INTERVAL_MS) return null
        return ((INTERVAL_MS - elapsed) / 60_000L) + 1
    }

    /** Records that [key] was force-refreshed now. Returns false (and records nothing) if it is too soon. */
    fun tryAcquire(key: String): Boolean {
        if (waitMinutes(key) != null) return false
        val now = System.currentTimeMillis()
        lastAt[key] = now
        lastAt.entries.removeIf { now - it.value >= INTERVAL_MS }
        persist?.invoke(lastAt.toMap())
        return true
    }

    /** Serialization used by the settings store: `key=millis` pairs separated by `;`. */
    fun encode(map: Map<String, Long>): String = map.entries.joinToString(";") { "${it.key}=${it.value}" }
    fun decode(s: String): Map<String, Long> =
        s.split(';').mapNotNull { e -> e.substringBefore('=', "").takeIf { it.isNotEmpty() }?.let { k -> e.substringAfter('=').toLongOrNull()?.let { k to it } } }.toMap()
}
