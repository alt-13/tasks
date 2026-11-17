package org.tasks.backup

import androidx.datastore.preferences.core.Preferences
import org.tasks.backup.BackupJsonReader.Companion.lenientJson
import org.tasks.preferences.TasksPreferences

/** The parts of [BackupImporter] that differ between Android and the shared app. */
interface BackupImportHooks {
    /** Imports the backup's [name] object (`intPrefs`, `longPrefs`, `stringPrefs`, `boolPrefs` or `setPrefs`). */
    suspend fun importPreferences(name: String, json: String, version: Int)

    /** Converts a comment picture from a backup made before 6.4. */
    fun legacyPicture(picture: String?): String? = picture

    /** Runs after the tasks are imported. */
    suspend fun afterImport(version: Int) {}
}

/** Imports the Android preferences that the shared app stores under the same name into [TasksPreferences]. */
class SharedBackupImportHooks(
    private val tasksPreferences: TasksPreferences,
) : BackupImportHooks {
    override suspend fun importPreferences(name: String, json: String, version: Int) {
        when (name) {
            "intPrefs" -> json.prefs<Int>().importPref(TasksPreferences.themeColor) {
                BackupImporter.themeToColor(version, it)
            }
            "stringPrefs" -> json.prefs<String>().let { prefs ->
                STRING_PREFS.forEach { key -> prefs.importPref(key) { it } }
                // a list preference on Android, so stored as a string
                prefs.importPref(TasksPreferences.defaultRecurrenceFrom) { it.toIntOrNull() }
                // Android's default_list is the list shown at start. Its default_remote_list is the
                // list for new tasks, stored as a filter ("4:<uuid>"); the shared app stores the uuid.
                prefs.importPref(TasksPreferences.defaultOpenFilter, "default_list") { it }
                prefs.importPref(TasksPreferences.defaultList, "default_remote_list") { value ->
                    value.split(":").takeIf { it.size == 2 && it[0] in LIST_FILTER_TYPES }?.get(1)
                }
            }
            "boolPrefs" -> json.prefs<Boolean>().let { prefs ->
                BOOLEAN_PREFS.forEach { key -> prefs.importPref(key) { it } }
            }
        }
    }

    private inline fun <reified T> String.prefs(): Map<String, T> = lenientJson.decodeFromString(this)

    private suspend fun <T, V : Any> Map<String, T>.importPref(
        key: Preferences.Key<V>,
        name: String = key.name,
        convert: (T) -> V?,
    ) {
        this[name]?.let(convert)?.let { tasksPreferences.set(key, it) }
    }

    companion object {
        private val LIST_FILTER_TYPES = listOf("3", "4") // FilterPreferenceCodec.TYPE_GOOGLE_TASKS, TYPE_CALDAV

        // Android preferences that the shared app stores under the same name and type.
        // Skipped: install details and debug_pro. default_list is mapped by meaning in stringPrefs.
        private val STRING_PREFS = listOf(
            TasksPreferences.defaultTags,
            TasksPreferences.defaultRecurrence,
            TasksPreferences.defaultLocation,
            TasksPreferences.lastViewedList,
        )
        private val BOOLEAN_PREFS = listOf(
            TasksPreferences.markdown,
            TasksPreferences.filtersEnabled,
            TasksPreferences.showTodayFilter,
            TasksPreferences.showRecentlyModifiedFilter,
            TasksPreferences.tagsEnabled,
            TasksPreferences.tagsHideUnused,
            TasksPreferences.placesEnabled,
            TasksPreferences.placesHideUnused,
            TasksPreferences.openLastViewedList,
            TasksPreferences.dynamicColor,
            TasksPreferences.bundleNotifications,
            TasksPreferences.wearableNotifications,
            TasksPreferences.collectStatistics,
            TasksPreferences.localListBannerDismissed,
        )
    }
}
