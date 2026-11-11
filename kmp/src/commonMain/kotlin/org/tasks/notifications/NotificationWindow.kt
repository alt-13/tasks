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

    // requests from the last reconcile that are no longer pending: the platform has shown them
    fun fired(scheduled: Collection<String>, pending: Collection<String>): List<Notification> {
        val waiting = pending.toSet()
        return scheduled
            .filter { it !in waiting }
            .mapNotNull { identifier ->
                val taskId = taskId(identifier) ?: return@mapNotNull null
                val timestamp = identifier.substringAfterLast('.').toLongOrNull() ?: return@mapNotNull null
                Notification(taskId = taskId, timestamp = timestamp)
            }
    }
}
