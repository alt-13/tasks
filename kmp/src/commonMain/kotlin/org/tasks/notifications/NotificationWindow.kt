package org.tasks.notifications

import org.tasks.data.entity.Notification

/**
 * The reminders to keep scheduled with a platform that caps pending notifications
 * (iOS allows 64), as a window over the next ones that rolls forward on every reconcile.
 */
object NotificationWindow {
    const val SIZE = 60

    private const val PREFIX = "task."

    fun identifier(entry: Notification): String = "$PREFIX${entry.taskId}.${entry.timestamp}"

    fun taskId(identifier: String): Long? =
        identifier
            .takeIf { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)
            ?.substringBefore('.')
            ?.toLongOrNull()

    fun window(upcoming: List<Notification>, now: Long): List<Notification> =
        upcoming
            .filter { it.timestamp > now }
            .sortedWith(compareBy({ it.timestamp }, { it.taskId }))
            .distinctBy { identifier(it) }
            .take(SIZE)

    // only our own requests, anything else pending belongs to someone else
    fun stale(pending: Collection<String>, window: List<Notification>): List<String> {
        val keep = window.map { identifier(it) }.toSet()
        return pending.filter { taskId(it) != null && it !in keep }
    }
}
