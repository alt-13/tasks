package org.tasks.notifications

import co.touchlab.kermit.Logger
import com.todoroo.astrid.alarms.AlarmService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import org.tasks.data.dao.TaskDao
import org.tasks.data.entity.Notification
import org.tasks.data.entity.Task
import org.tasks.extensions.guarded
import org.tasks.extensions.notificationPermission
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

private const val TAG = "IosNotifier"

/**
 * Hands the next [NotificationWindow.SIZE] reminders to iOS, which posts them even when the app
 * isn't running. The window only moves forward when [run] reconciles it.
 */
internal class IosNotifier(
    private val alarmService: () -> AlarmService,
    private val taskDao: TaskDao,
    private val notificationsEnabled: suspend () -> Boolean,
) : Notifier {
    private val reconciles = Channel<Unit>(Channel.CONFLATED)

    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    suspend fun run() {
        for (unit in reconciles) {
            guarded(TAG, "Failed to schedule reminders", Unit) { reconcile() }
        }
    }

    private suspend fun reconcile() {
        val now = currentTimeMillis()
        val window = if (
            notificationsEnabled() &&
            notificationPermission() == NotificationPermission.GRANTED
        ) {
            NotificationWindow.window(alarmService().upcomingAlarms(now, NotificationWindow.SIZE), now)
        } else {
            emptyList()
        }
        val stale = NotificationWindow.stale(identifiers(pending = true), window)
        if (stale.isNotEmpty()) {
            center.removePendingNotificationRequestsWithIdentifiers(stale)
        }
        val tasks = taskDao.fetch(window.map { it.taskId }.distinct()).associateBy { it.id }
        // adding an identifier again replaces its request, so edited titles stay current
        window.forEach { entry -> tasks[entry.taskId]?.let { schedule(it, entry, now) } }
        Logger.d(tag = TAG) { "Scheduled ${window.size}, removed ${stale.size}" }
    }

    private fun schedule(task: Task, entry: Notification, now: Long) {
        val content = UNMutableNotificationContent().apply {
            setTitle(task.title?.takeIf { it.isNotBlank() } ?: "Tasks.org")
            task.notes?.takeIf { it.isNotBlank() }?.let { setBody(it) }
            setSound(UNNotificationSound.defaultSound)
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            ((entry.timestamp - now) / 1000.0).coerceAtLeast(1.0),
            false,
        )
        val request = UNNotificationRequest.requestWithIdentifier(
            NotificationWindow.identifier(entry),
            content,
            trigger,
        )
        center.addNotificationRequest(request) { error ->
            if (error != null) {
                Logger.w(tag = TAG) { "Failed to schedule $entry: $error" }
            }
        }
    }

    private suspend fun identifiers(pending: Boolean): List<String> =
        suspendCancellableCoroutine { continuation ->
            if (pending) {
                center.getPendingNotificationRequestsWithCompletionHandler { requests ->
                    continuation.resume(
                        requests.orEmpty().map { (it as UNNotificationRequest).identifier }
                    )
                }
            } else {
                center.getDeliveredNotificationsWithCompletionHandler { notifications ->
                    continuation.resume(
                        notifications.orEmpty().map { (it as UNNotification).request.identifier }
                    )
                }
            }
        }

    override suspend fun cancel(id: Long, reason: CancelReason) = cancel(listOf(id), reason)

    // takes down what is already on screen, the reconcile drops what is still pending
    override suspend fun cancel(ids: List<Long>, reason: CancelReason) {
        val tasks = ids.toSet()
        val shown = identifiers(pending = false).filter { NotificationWindow.taskId(it) in tasks }
        if (shown.isNotEmpty()) {
            center.removeDeliveredNotificationsWithIdentifiers(shown)
        }
        triggerNotifications()
    }

    override suspend fun cancelAll(reason: CancelReason) {
        center.removeAllDeliveredNotifications()
        triggerNotifications()
    }

    override fun triggerNotifications() {
        reconciles.trySend(Unit)
    }

    override suspend fun updateTimerNotification() {}
}
