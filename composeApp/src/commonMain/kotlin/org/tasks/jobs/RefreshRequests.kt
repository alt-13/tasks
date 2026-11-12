package org.tasks.jobs

import org.tasks.time.ONE_HOUR

/**
 * iOS keeps one background refresh request per app. [next] is its earliest start, or null for no
 * request: there is one only while background sync is on, and a requested refresh can move it
 * earlier.
 */
data class RefreshRequests(
    val syncAt: Long? = null,
    val refreshAt: Long? = null,
) {
    val next: Long?
        get() = syncAt?.let { minOf(it, refreshAt ?: it) }

    // keeps a pending sync time, like Android's periodic sync when it's updated
    fun backgroundSync(enabled: Boolean, now: Long) =
        copy(syncAt = if (enabled) syncAt ?: (now + BACKGROUND_SYNC_INTERVAL) else null)

    fun refresh(at: Long) = copy(refreshAt = minOf(refreshAt ?: at, at))

    // iOS doesn't repeat refresh tasks: the request that started this run is gone
    fun started(now: Long) = RefreshRequests(refreshAt = refreshAt?.takeIf { it > now })

    companion object {
        // Android's periodic background sync interval
        const val BACKGROUND_SYNC_INTERVAL = ONE_HOUR
    }
}
