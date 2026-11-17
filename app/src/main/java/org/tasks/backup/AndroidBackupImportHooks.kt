package org.tasks.backup

import android.content.Context
import com.todoroo.astrid.service.Upgrade_15_10
import com.todoroo.astrid.service.Upgrader
import com.todoroo.astrid.service.Upgrader.Companion.getAndroidColor
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tasks.R
import org.tasks.backup.BackupJsonReader.Companion.lenientJson
import org.tasks.data.getLegacyPictureUri
import org.tasks.jobs.BackgroundWork
import org.tasks.notifications.Notifier
import org.tasks.preferences.Preferences
import javax.inject.Inject

/** Imports all preferences into Android's own preferences, like before the importer moved to common code. */
class AndroidBackupImportHooks @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val preferences: Preferences,
    private val upgrade_15_10: Upgrade_15_10,
    private val notifier: Notifier,
    private val backgroundWork: BackgroundWork,
) : BackupImportHooks {
    override suspend fun importPreferences(name: String, json: String, version: Int) {
        val ignoreKeys = ignorePrefs.map { context.getString(it) }
        when (name) {
            "intPrefs" -> json.prefs<Int>(ignoreKeys).forEach { (k, v) -> preferences.setInt(k, v) }
            "longPrefs" -> json.prefs<Long>(ignoreKeys).forEach { (k, v) -> preferences.setLong(k, v) }
            "stringPrefs" -> json.prefs<String>(ignoreKeys).forEach { (k, v) -> preferences.setString(k, v) }
            "boolPrefs" -> json.prefs<Boolean>(ignoreKeys).forEach { (k, v) -> preferences.setBoolean(k, v) }
            "setPrefs" -> json.prefs<Set<String>>(ignoreKeys).forEach { (k, v) -> preferences.setStringSet(k, v) }
        }
    }

    private inline fun <reified T> String.prefs(ignoreKeys: List<String>): Map<String, T> =
        lenientJson.decodeFromString<Map<String, T>>(this).filterKeys { it !in ignoreKeys }

    override fun legacyPicture(picture: String?) = getLegacyPictureUri(picture)?.toString()

    override suspend fun afterImport(version: Int) {
        if (version < Upgrader.V8_2) {
            val themeIndex = preferences.getInt(R.string.p_theme_color, 7)
            preferences.setInt(R.string.p_theme_color, getAndroidColor(context, themeIndex))
        }
        if (version < Upgrade_15_10.VERSION) {
            upgrade_15_10.migrateRandomReminder()
        }
        if (version < Upgrader.V12_4) {
            // the old importer saved these tasks with TaskSaver, which schedules reminders and a refresh
            notifier.triggerNotifications()
            backgroundWork.scheduleRefresh()
        }
    }

    companion object {
        private val ignorePrefs = intArrayOf(
            R.string.p_current_version,
            R.string.p_backups_android_backup_last,
            R.string.p_device_install_version,
            R.string.p_last_backup,
            R.string.p_install_date,
        )
    }
}
