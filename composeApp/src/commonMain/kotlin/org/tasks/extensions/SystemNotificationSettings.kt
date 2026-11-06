package org.tasks.extensions

import org.tasks.notifications.NotificationPermission

expect fun openSystemNotificationSettings()

expect suspend fun notificationPermission(): NotificationPermission
