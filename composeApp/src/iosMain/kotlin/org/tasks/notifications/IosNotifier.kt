package org.tasks.notifications

import co.touchlab.kermit.Logger
import com.todoroo.astrid.alarms.AlarmService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tasks.data.dao.TaskDao
import org.tasks.data.entity.Notification
import org.tasks.data.entity.Task
import org.tasks.extensions.guarded
import org.tasks.extensions.notificationPermission
import org.tasks.preferences.TasksPreferences
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import org.tasks.time.endOfMinute
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNNotificationTrigger
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
    private val preferences: TasksPreferences,
) : Notifier {
    private val reconciles = Channel<Unit>(Channel.CONFLATED)

    private val center get() = UNUserNotificationCenter.currentNotificationCenter()

    private val reconciling = Mutex()

    suspend fun run() {
        for (unit in reconciles) {
            reconcileNow()
        }
    }

    // also called on its own when iOS runs a notification action with the app in the background
    suspend fun reconcileNow() {
        guarded(TAG, "Failed to schedule reminders", Unit) { reconciling.withLock { reconcile() } }
    }

    private suspend fun reconcile() {
        val pending = identifiers(pending = true)
        val fired = NotificationWindow.fired(
            scheduled = preferences.get(TasksPreferences.scheduledNotifications, emptySet()),
            pending = pending,
        )
        alarmService().recordFired(fired)
        val now = currentTimeMillis()
        val allowed = notificationsEnabled() &&
                notificationPermission() == NotificationPermission.GRANTED
        // due but never handed to iOS: created overdue, or due beyond the last window
        val posted = mutableListOf<Long>()
        if (allowed) {
            alarmService().triggerAlarms { overdue -> post(overdue).also { posted.addAll(it) } }
        }
        val window = if (allowed) {
            NotificationWindow.window(alarmService().upcomingAlarms(now, NotificationWindow.SIZE), now)
        } else {
            emptyList()
        }
        val stale = NotificationWindow.stale(pending, window)
        if (stale.isNotEmpty()) {
            center.removePendingNotificationRequestsWithIdentifiers(stale)
        }
        val tasks = taskDao.fetch(window.map { it.taskId }.distinct()).associateBy { it.id }
        // adding an identifier again replaces its request, so edited titles stay current
        val scheduled = window.filter { it.taskId in tasks }
        scheduled.forEach { schedule(tasks.getValue(it.taskId), it, timeInterval(it, now)) }
        preferences.set(
            TasksPreferences.scheduledNotifications,
            scheduled.map { NotificationWindow.identifier(it) }.toSet(),
        )
        Logger.d(tag = TAG) {
            "Recorded ${fired.size}, posted $posted, scheduled ${scheduled.size}, removed ${stale.size}"
        }
    }

    private suspend fun post(overdue: List<Notification>): List<Long> {
        val tasks = taskDao.fetch(overdue.map { it.taskId }).associateBy { it.id }
        return overdue.mapNotNull { entry ->
            val task = tasks[entry.taskId] ?: return@mapNotNull null
            schedule(task, entry, trigger = null)
            taskDao.setLastNotified(entry.taskId, entry.timestamp.endOfMinute())
            entry.taskId
        }
    }

    private fun timeInterval(entry: Notification, now: Long) =
        UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            ((entry.timestamp - now) / 1000.0).coerceAtLeast(1.0),
            false,
        )

    // a null trigger shows the notification right away
    private fun schedule(task: Task, entry: Notification, trigger: UNNotificationTrigger?) {
        val content = UNMutableNotificationContent().apply {
            setTitle(task.title?.takeIf { it.isNotBlank() } ?: "Tasks.org")
            task.notes?.takeIf { it.isNotBlank() }?.let { setBody(it) }
            setSound(UNNotificationSound.defaultSound)
            setCategoryIdentifier(if (task.readOnly) CATEGORY_SNOOZE_ONLY else CATEGORY_ACTIONABLE)
        }
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
