package org.tasks.extensions

import org.tasks.notifications.NotificationPermission

// Android and desktop don't report a denied permission in settings
actual suspend fun notificationPermission(): NotificationPermission = NotificationPermission.GRANTED
