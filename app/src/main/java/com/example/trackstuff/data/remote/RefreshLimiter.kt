package com.example.trackstuff.data.remote

import java.util.concurrent.ConcurrentHashMap

/**
 * Explicit "refresh" actions bypass the HTTP cache, so they are allowed at most once per [INTERVAL_MS]
 * per key (a Discover source, a title). Automatic loads are never limited: they always go through the cache.
 */
object RefreshLimiter {
    const val INTERVAL_MS = 60 * 60 * 1000L
    private val lastAt = ConcurrentHashMap<String, Long>()

    /** Minutes to wait before [key] may be force-refreshed again, or null when allowed now. */
    fun waitMinutes(key: String): Long? {
        val elapsed = System.currentTimeMillis() - (lastAt[key] ?: return null)
        if (elapsed >= INTERVAL_MS) return null
        return ((INTERVAL_MS - elapsed) / 60_000L) + 1
    }

    /** Records that [key] was force-refreshed now. Returns false (and records nothing) if it is too soon. */
    fun tryAcquire(key: String): Boolean {
        if (waitMinutes(key) != null) return false
        lastAt[key] = System.currentTimeMillis()
        return true
    }
}
