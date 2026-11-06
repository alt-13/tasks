package org.tasks.extensions

import co.touchlab.kermit.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import org.tasks.data.dao.AlarmDao
import org.tasks.notifications.NotificationPermission
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

private const val TAG = "NotificationPermission"

actual suspend fun notificationPermission(): NotificationPermission =
    suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter()
            .getNotificationSettingsWithCompletionHandler { settings ->
                continuation.resume(
                    when (settings?.authorizationStatus) {
                        null, UNAuthorizationStatusNotDetermined -> NotificationPermission.NOT_DETERMINED
                        UNAuthorizationStatusDenied -> NotificationPermission.DENIED
                        // authorized, provisional and ephemeral can all deliver
                        else -> NotificationPermission.GRANTED
                    }
                )
            }
    }

// Like the desktop notifier: ask once a reminder exists, not on first launch.
internal suspend fun requestNotificationPermissionIfNeeded(alarmDao: AlarmDao) =
    guarded(TAG, "Failed to resolve notification permission", Unit) {
        if (notificationPermission() == NotificationPermission.NOT_DETERMINED &&
            alarmDao.hasActiveAlarms()
        ) {
            requestPermission()
        }
    }

private suspend fun requestPermission() {
    suspendCancellableCoroutine<Unit> { continuation ->
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
        ) { granted, error ->
            when {
                error != null -> Logger.w(tag = TAG) { "Could not ask for permission ($error)" }
                !granted -> Logger.i(tag = TAG) { "Notification permission refused" }
            }
            continuation.resume(Unit)
        }
    }
}
