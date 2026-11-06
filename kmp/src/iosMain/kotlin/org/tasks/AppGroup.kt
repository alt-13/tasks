package org.tasks

import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

/** `APP_GROUP_ID` from `Config.xcconfig`, exposed through `Info.plist`. */
val appGroupId: String?
    get() = (NSBundle.mainBundle.objectForInfoDictionaryKey("AppGroupIdentifier") as? String)
        ?.takeIf { it.isNotBlank() }

/**
 * Directory shared with app extensions. Falls back to the app's Documents directory when the
 * group container is unavailable, e.g. in unsigned simulator builds without the entitlement.
 */
fun appGroupPath(groupId: String? = appGroupId): String =
    groupId
        ?.let { NSFileManager.defaultManager.containerURLForSecurityApplicationGroupIdentifier(it)?.path }
        ?: documentsPath()

internal fun documentsPath(): String {
    val url = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask).first() as NSURL
    return url.path ?: error("no Documents directory")
}
