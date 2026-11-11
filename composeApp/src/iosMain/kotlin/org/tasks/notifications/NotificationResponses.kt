package org.tasks.notifications

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform
import org.tasks.TaskRequests
import org.tasks.ensureStarted
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationDefaultActionIdentifier
import platform.UserNotifications.UNUserNotificationCenter

private const val TAG = "NotificationResponses"

internal const val CATEGORY_ACTIONABLE = "org.tasks.reminder"
internal const val CATEGORY_SNOOZE_ONLY = "org.tasks.reminder.snooze"

internal suspend fun registerNotificationCategories() {
    val complete = UNNotificationAction.actionWithIdentifier(
        NotificationAction.COMPLETE.key,
        NotificationAction.label(NotificationAction.COMPLETE),
        0uL, // no options: runs in the background
    )
    // snoozing asks for a time in the app, like desktop and Android
    val snooze = UNNotificationAction.actionWithIdentifier(
        NotificationAction.SNOOZE.key,
        NotificationAction.label(NotificationAction.SNOOZE),
        UNNotificationActionOptionForeground,
    )
    UNUserNotificationCenter.currentNotificationCenter().setNotificationCategories(
        setOf(
            category(CATEGORY_ACTIONABLE, listOf(complete, snooze)),
            category(CATEGORY_SNOOZE_ONLY, listOf(snooze)),
        )
    )
}

private fun category(identifier: String, actions: List<UNNotificationAction>) =
    UNNotificationCategory.categoryWithIdentifier(
        identifier,
        actions,
        emptyList<String>(),
        0uL, // no options
    )

/** Called by the app's `UNUserNotificationCenterDelegate` when a reminder is tapped or acted on. */
fun onNotificationResponse(identifier: String, actionIdentifier: String, completion: () -> Unit) {
    val taskId = NotificationWindow.taskId(identifier)
    val action = when (actionIdentifier) {
        UNNotificationDefaultActionIdentifier -> NotificationAction.OPEN
        else -> NotificationAction.fromKey(actionIdentifier)
    }
    if (taskId == null || action == null) {
        Logger.d(tag = TAG) { "Ignoring $actionIdentifier on $identifier" }
        completion()
        return
    }
    ensureStarted()
    val koin = KoinPlatform.getKoin()
    koin.get<CoroutineScope>().launch {
        if (action == NotificationAction.OPEN) {
            koin.get<TaskRequests>().awaitOpenRequests()
        }
        koin.get<NotificationActionHandler>().handle(taskId, action)
        // the app may have been launched in the background, without the reconcile loop
        koin.get<IosNotifier>().reconcileNow()
        withContext(Dispatchers.Main) { completion() }
    }
}
