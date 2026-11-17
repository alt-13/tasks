package org.tasks.backup

import co.touchlab.kermit.Logger
import com.todoroo.astrid.service.Upgrade_15_13
import com.todoroo.astrid.service.Upgrade_15_13.Companion.canonicalized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okio.IOException
import okio.Source
import okio.buffer
import org.tasks.analytics.Analytics
import org.tasks.analytics.AnalyticsEvents
import org.tasks.backup.BackupJsonReader.Companion.lenientJson
import org.tasks.broadcast.RefreshBroadcaster
import org.tasks.caldav.VtodoCache
import org.tasks.data.GoogleTaskAccount
import org.tasks.data.GoogleTaskList
import org.tasks.data.TaskMover
import org.tasks.data.dao.AlarmDao
import org.tasks.data.dao.CaldavDao
import org.tasks.data.dao.DirtyDao
import org.tasks.data.dao.FilterDao
import org.tasks.data.dao.LocationDao
import org.tasks.data.dao.TagDao
import org.tasks.data.dao.TagDataDao
import org.tasks.data.dao.TaskAttachmentDao
import org.tasks.data.dao.TaskDao
import org.tasks.data.dao.TaskListMetadataDao
import org.tasks.data.dao.UserActivityDao
import org.tasks.data.entity.Alarm
import org.tasks.data.entity.Alarm.Companion.TYPE_RANDOM
import org.tasks.data.entity.Alarm.Companion.TYPE_SNOOZE
import org.tasks.data.entity.Attachment
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavAccount.Companion.TYPE_GOOGLE_TASKS
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Filter
import org.tasks.data.entity.Geofence
import org.tasks.data.entity.Place
import org.tasks.data.entity.Tag
import org.tasks.data.entity.TagData
import org.tasks.data.entity.Task
import org.tasks.data.entity.TaskAttachment
import org.tasks.data.entity.TaskListMetadata
import org.tasks.filters.LegacyFilterCriteria
import org.tasks.kmp.org.tasks.themes.ColorProvider
import org.tasks.preferences.AppPreferences
import org.tasks.repeats.Recur
import org.tasks.repeats.Until
import org.tasks.themes.migrateLegacyIcon
import org.tasks.time.DateTime

/** Imports a Tasks.org JSON backup. Platform differences are in [BackupImportHooks]. */
class BackupImporter(
    private val tagDataDao: TagDataDao,
    private val userActivityDao: UserActivityDao,
    private val taskDao: TaskDao,
    private val dirtyDao: DirtyDao,
    private val locationDao: LocationDao,
    private val refreshBroadcaster: RefreshBroadcaster,
    private val alarmDao: AlarmDao,
    private val tagDao: TagDao,
    private val filterDao: FilterDao,
    private val taskAttachmentDao: TaskAttachmentDao,
    private val caldavDao: CaldavDao,
    private val appPreferences: AppPreferences,
    private val taskListMetadataDao: TaskListMetadataDao,
    private val vtodoCache: VtodoCache,
    private val analytics: Analytics,
    private val hooks: BackupImportHooks,
    private val taskMover: TaskMover,
) {
    /** [openSource] is called twice: the backup is read once for metadata and once for tasks. */
    suspend fun importTasks(
        openSource: () -> Source,
        onProgress: (suspend (taskCount: Int) -> Unit)? = null,
    ): ImportResult = withContext(Dispatchers.IO) {
        val result = ImportResult()
        val accountUuidMap = mutableMapOf<String, String>()
        val calendarUuidMap = mutableMapOf<String, String>()
        val newPlaces = mutableSetOf<String>()
        val placeRadius = mutableMapOf<String, Int>()
        try {
            val version = BackupJsonReader(openSource().buffer()).use {
                importMetadata(it, accountUuidMap, calendarUuidMap, newPlaces)
            }
            BackupJsonReader(openSource().buffer()).use {
                importTasks(it, onProgress, version, result, calendarUuidMap) { place, radius ->
                    // like the old min(geofences.radius), and only for places this import added
                    if (place in newPlaces) placeRadius[place] = minOf(radius, placeRadius[place] ?: radius)
                }
            }
            placeRadius.forEach { (uid, radius) ->
                locationDao.getByUid(uid)?.let { locationDao.update(it.copy(radius = radius)) }
            }
            hooks.afterImport(version)
            if (version < LOCAL_TASKS_VERSION) {
                taskMover.migrateLocalTasks()
            }
            Logger.d(tag = TAG) { "Updating parents" }
            caldavDao.updateParents(force = true)
            analytics.logEvent(
                AnalyticsEvents.SETTINGS_CLICK,
                AnalyticsEvents.PARAM_TYPE to "import_backup_success",
                AnalyticsEvents.PARAM_VALUE to version.toString(),
                AnalyticsEvents.PARAM_TASK_COUNT to result.taskCount,
                AnalyticsEvents.PARAM_IMPORT_COUNT to result.importCount,
                AnalyticsEvents.PARAM_SKIP_COUNT to result.skipCount,
            )
        } catch (e: Exception) {
            Logger.e(e, tag = TAG) { "Import failed" }
            analytics.logEvent(
                AnalyticsEvents.SETTINGS_CLICK,
                AnalyticsEvents.PARAM_TYPE to "import_backup_failed",
                AnalyticsEvents.PARAM_ERROR to (e::class.simpleName ?: "unknown"),
            )
            if (e !is IOException) {
                throw e
            }
        }
        refreshBroadcaster.broadcastRefresh()
        result
    }

    private suspend fun importMetadata(
        reader: BackupJsonReader,
        accountUuidMap: MutableMap<String, String>,
        calendarUuidMap: MutableMap<String, String>,
        newPlaces: MutableSet<String>,
    ): Int {
        val importedAccounts = mutableMapOf<String, CaldavAccount>()
        val filters = mutableListOf<Filter>()
        var version = 0
        reader.readData { element ->
            when (element) {
                "version" -> version = reader.nextInt().also { Logger.d(tag = TAG) { "Backup version: $it" } }
                "timestamp" -> reader.nextLong().let { Logger.d(tag = TAG) { "Backup timestamp: $it" } }
                "places" -> reader.forEach<Place> { place ->
                    if (locationDao.getByUid(place.uid!!) == null) {
                        locationDao.insert(place.copy(icon = place.icon.migrateLegacyIcon()))
                        newPlaces += place.uid!!
                    }
                }
                "tags" -> reader.forEach<TagData> { tagData ->
                    if (findTagData(tagData.remoteId!!, tagData.name!!) == null) {
                        tagDataDao.insert(
                            tagData.copy(
                                color = themeToColor(version, tagData.color ?: 0),
                                icon = tagData.icon.migrateLegacyIcon(),
                            )
                        )
                    }
                }
                // inserted after the pass, when calendarUuidMap is complete
                "filters" -> reader.forEach<Filter> {
                    filters += if (version < LEGACY_FILTER_VERSION) LegacyFilterCriteria.rebuildFilter(it) else it
                }
                "caldavAccounts" -> reader.forEach<CaldavAccount> { imported ->
                    val account = if (version < Upgrade_15_13.VERSION) imported.canonicalized() else imported
                    importedAccounts[account.uuid!!] = account
                    if (caldavDao.getAccountByUuid(account.uuid!!) != null) {
                        return@forEach
                    }
                    val existing = findExistingAccount(account)
                    if (existing != null) {
                        accountUuidMap[account.uuid!!] = existing.uuid!!
                    } else {
                        caldavDao.insert(account)
                    }
                }
                "caldavCalendars" -> reader.forEach<CaldavCalendar> { imported ->
                    if (caldavDao.getCalendarByUuid(imported.uuid!!) != null) {
                        return@forEach
                    }
                    val calendar = if (version < Upgrade_15_13.VERSION) {
                        imported.canonicalized(importedAccounts[imported.account])
                    } else {
                        imported
                    }
                    val remappedAccount = accountUuidMap[calendar.account] ?: calendar.account
                    val existingByUrl = calendar.url
                        ?.takeIf { it.isNotBlank() }
                        ?.let { url -> caldavDao.getCalendarByUrl(remappedAccount!!, url) }
                    if (existingByUrl != null) {
                        calendarUuidMap[calendar.uuid!!] = existingByUrl.uuid!!
                    } else {
                        caldavDao.insert(
                            calendar.copy(
                                account = remappedAccount,
                                color = themeToColor(version, calendar.color),
                                icon = calendar.icon.migrateLegacyIcon(),
                            )
                        )
                    }
                }
                "taskListMetadata" -> reader.forEach<TaskListMetadata> { tlm ->
                    val id = tlm.filter.takeIf { it?.isNotBlank() == true } ?: tlm.tagUuid!!
                    if (taskListMetadataDao.fetchByTagOrFilter(id) == null) {
                        taskListMetadataDao.insert(tlm)
                    }
                }
                "taskAttachments" -> reader.forEach<TaskAttachment> { attachment ->
                    if (taskAttachmentDao.getAttachment(attachment.remoteId) == null) {
                        taskAttachmentDao.insert(attachment)
                    }
                }
                "googleTaskAccounts" -> reader.forEach<GoogleTaskAccount> { googleTaskAccount ->
                    if (caldavDao.getAccount(TYPE_GOOGLE_TASKS, googleTaskAccount.account!!) == null) {
                        caldavDao.insert(
                            CaldavAccount(
                                accountType = TYPE_GOOGLE_TASKS,
                                uuid = googleTaskAccount.account,
                                name = googleTaskAccount.account,
                                username = googleTaskAccount.account,
                            )
                        )
                    }
                }
                "googleTaskLists" -> reader.forEach<GoogleTaskList> { googleTaskList ->
                    if (caldavDao.getCalendar(googleTaskList.remoteId!!) == null) {
                        caldavDao.insert(
                            CaldavCalendar(
                                account = googleTaskList.account,
                                uuid = googleTaskList.remoteId,
                                color = themeToColor(version, googleTaskList.color ?: 0),
                                icon = googleTaskList.icon?.toString().migrateLegacyIcon(),
                            )
                        )
                    }
                }
                "intPrefs", "longPrefs", "stringPrefs", "boolPrefs", "setPrefs" ->
                    hooks.importPreferences(element, reader.jsonString(), version)
                else -> skip(reader, element)
            }
        }
        filters.forEach { filter ->
            if (filterDao.getByName(filter.title!!) == null) {
                filterDao.insert(
                    calendarUuidMap.entries.fold(filter) { f, (old, new) ->
                        f.copy(sql = f.sql?.replace(old, new), values = f.values?.replace(old, new))
                    }.copy(
                        color = themeToColor(version, filter.color ?: 0),
                        icon = filter.icon.migrateLegacyIcon(),
                    )
                )
            }
        }
        return version
    }

    private suspend fun importTasks(
        reader: BackupJsonReader,
        onProgress: (suspend (Int) -> Unit)?,
        version: Int,
        result: ImportResult,
        calendarUuidMap: Map<String, String>,
        onGeofenceRadius: (place: String, radius: Int) -> Unit,
    ) {
        reader.readData { element ->
            when (element) {
                "tasks" -> {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val rawJson = reader.jsonString()
                        val backup = lenientJson.decodeFromString<TaskBackup>(rawJson)
                        result.taskCount++
                        onProgress?.invoke(result.taskCount)
                        importTask(backup, version, rawJson, result, calendarUuidMap, onGeofenceRadius)
                    }
                    reader.endArray()
                }
                else -> skip(reader, element)
            }
        }
    }

    /** Calls [block] for each top-level name except `data`, and for each name inside `data`. */
    private inline fun BackupJsonReader.readData(block: (String) -> Unit) {
        beginObject()
        while (hasNext()) {
            when (val name = nextName()) {
                "data" -> {
                    beginObject()
                    while (hasNext()) block(nextName())
                    endObject()
                }
                else -> block(name)
            }
        }
        endObject()
    }

    private fun skip(reader: BackupJsonReader, name: String) {
        Logger.w(tag = TAG) { "Skipping $name" }
        reader.skipValue()
    }

    private suspend fun importTask(
        backup: TaskBackup,
        version: Int,
        rawJson: String,
        result: ImportResult,
        calendarUuidMap: Map<String, String>,
        onGeofenceRadius: (place: String, radius: Int) -> Unit,
    ) {
        val task = backup.task
        val perTaskDirty: Pair<Long, Long>? = when {
            backup.dirtyVersion != null && backup.syncedVersion != null ->
                backup.dirtyVersion to backup.syncedVersion
            version >= DIRTY_FLAG_VERSION -> {
                val synced = backup.syncedVersion ?: 0L
                val dirty = backup.dirtyVersion ?: (synced + 1)
                dirty to synced
            }
            else -> null
        }
        val googleLastSync = if (perTaskDirty == null) lastSyncValues(rawJson, "google") else emptyList()
        val caldavLastSync = if (perTaskDirty == null) lastSyncValues(rawJson, "caldavTasks") else emptyList()
        if (taskDao.fetch(task.uuid) != null) {
            result.skipCount++
            return
        }
        if (
            backup.caldavTasks
                ?.filter { it.deleted == 0L }
                ?.any {
                    val calendar = calendarUuidMap[it.calendar] ?: it.calendar
                    val existing = if (
                        it.obj.isNullOrBlank() ||
                        it.obj == "null.ics" // caused by an old bug
                    ) {
                        it.remoteId?.let { remoteId -> caldavDao.getTaskByRemoteId(calendar!!, remoteId) }
                    } else {
                        caldavDao.getTask(calendar!!, it.obj!!)
                    }
                    existing != null
                } == true
        ) {
            result.skipCount++
            return
        }
        val skipAllDayAlarms = version < ALL_DAY_ALARMS_VERSION
                && task.hasDueDate()
                && !task.hasDueTime()
                && !appPreferences.isDefaultDueTimeEnabled()
        // Old backups hold fields the entities no longer have. Unlike Android, which upgrades the task
        // after inserting it and saves only the ring flags, the task is upgraded before it's inserted.
        val legacy = if (version < REPEAT_FROM_VERSION) lenientJson.parseToJsonElement(rawJson).jsonObject else null
        val legacyTask = legacy?.get("task")?.jsonObject
        val legacyAlarms = if (version < LEGACY_ALARMS_VERSION) {
            legacyAlarms(task, legacyTask, skipAllDayAlarms).also {
                // bit tests like the database migration; isNotifyModeFive is false while reminder bits are set
                task.ringFlags = when {
                    task.ringFlags and Task.NOTIFY_MODE_FIVE != 0 -> Task.NOTIFY_MODE_FIVE
                    task.ringFlags and Task.NOTIFY_MODE_NONSTOP != 0 -> Task.NOTIFY_MODE_NONSTOP
                    else -> 0
                }
            }
        } else {
            emptyList()
        }
        if (legacy != null) {
            val recurrence = task.recurrence
            task.repeatFrom = if (recurrence?.contains("FROM=COMPLETION") == true) {
                Task.RepeatFrom.COMPLETION_DATE
            } else {
                Task.RepeatFrom.DUE_DATE
            }
            task.recurrence = recurrence
                ?.replace(";?FROM=[^;]*".toRegex(), "")
                ?.withUntil(legacyTask.long("repeatUntil"))
        }
        task.suppressRefresh()
        task.suppressSync()
        taskDao.createNew(task)
        val taskId = task.id
        (backup.alarms.orEmpty().takeUnless { skipAllDayAlarms }.orEmpty() + legacyAlarms)
            .map { it.copy(task = taskId) }
            .takeIf { it.isNotEmpty() }
            ?.let { alarmDao.insert(it) }
        backup.comments?.forEach { comment ->
            comment.targetId = task.uuid
            if (version < LEGACY_PICTURE_VERSION) {
                comment.picture = hooks.legacyPicture(comment.picture)
            }
            userActivityDao.createNew(comment)
        }
        backup.google?.forEachIndexed { index, googleTask ->
            val id = caldavDao.insert(
                CaldavTask(
                    task = taskId,
                    calendar = googleTask.listId,
                    remoteId = googleTask.remoteId,
                    remoteOrder = googleTask.remoteOrder,
                    remoteParent = googleTask.remoteParent,
                    deleted = googleTask.deleted,
                )
            )
            if (googleTask.deleted == 0L) {
                val (dirty, synced) = perTaskDirty
                    ?: reconstructDirtyVersion(googleLastSync.getOrNull(index), task.modificationDate)
                dirtyDao.setDirtyState(id, dirty, synced)
            }
        }
        backup.locations?.forEach { location ->
            val place = Place(
                longitude = location.longitude,
                latitude = location.latitude,
                name = location.name,
                address = location.address,
                url = location.url,
                phone = location.phone,
                radius = location.radius.takeIf { it > 0 } ?: Place().radius,
            )
            locationDao.insert(place)
            locationDao.insert(
                Geofence(
                    task = taskId,
                    place = place.uid,
                    isArrival = location.arrival,
                    isDeparture = location.departure,
                )
            )
        }
        backup.tags?.forEach { tag ->
            val tagData = findTagData(tag.tagUid!!, tag.name!!) ?: return@forEach
            tagDao.insert(tag.copy(task = taskId, taskUid = task.remoteId, tagUid = tagData.remoteId))
        }
        backup.geofences?.forEach { geofence ->
            locationDao.insert(geofence.copy(task = taskId))
        }
        if (version < PLACE_RADIUS_VERSION) {
            legacy?.get("geofences")?.jsonArray?.forEach { geofence ->
                val place = geofence.jsonObject["place"]?.jsonPrimitive?.content ?: return@forEach
                geofence.jsonObject.long("radius")?.let { onGeofenceRadius(place, it.toInt()) }
            }
        }
        backup.attachments
            ?.mapNotNull { taskAttachmentDao.getAttachment(it.attachmentUid) }
            ?.map { Attachment(task = taskId, fileId = it.id!!, attachmentUid = it.remoteId) }
            ?.let { taskAttachmentDao.insert(it) }
        backup.caldavTasks?.forEachIndexed { index, caldavTask ->
            val remappedCalendar = calendarUuidMap[caldavTask.calendar] ?: caldavTask.calendar
            val id = caldavDao.insert(caldavTask.copy(task = taskId, calendar = remappedCalendar))
            if (!caldavTask.isDeleted()) {
                val (dirty, synced) = perTaskDirty
                    ?: reconstructDirtyVersion(caldavLastSync.getOrNull(index), task.modificationDate)
                dirtyDao.setDirtyState(id, dirty, synced)
            }
        }
        backup.vtodo?.let {
            val caldavTask = backup.caldavTasks?.firstOrNull { t -> !t.isDeleted() } ?: return@let
            val remappedCalendar = calendarUuidMap[caldavTask.calendar] ?: caldavTask.calendar
            val caldavCalendar = caldavDao.getCalendar(remappedCalendar!!) ?: return@let
            vtodoCache.putVtodo(caldavCalendar, caldavTask.copy(calendar = remappedCalendar), it)
        }
        result.importCount++
    }

    private suspend fun findExistingAccount(account: CaldavAccount): CaldavAccount? =
        caldavDao.getAccounts().firstOrNull { existing ->
            existing.accountType == account.accountType &&
                    when {
                        account.url?.isNotBlank() == true ->
                            existing.url == account.url && existing.username == account.username
                        account.username?.isNotBlank() == true ->
                            existing.username == account.username
                        else -> false
                    }
        }

    /** Alarms that backups before 12.4 stored as ring flags and task columns. */
    private fun legacyAlarms(task: Task, json: JsonObject?, skipAllDayAlarms: Boolean): List<Alarm> = buildList {
        if (!skipAllDayAlarms) {
            // Upgrade_14_11.fromLegacyFlags, without alarms for dates the task doesn't have
            val flags = task.ringFlags
            if (flags and Task.NOTIFY_AT_START != 0 && task.hasStartDate()) add(Alarm.whenStarted(0))
            if (flags and Task.NOTIFY_AT_DEADLINE != 0 && task.hasDueDate()) add(Alarm.whenDue(0))
            if (flags and Task.NOTIFY_AFTER_DEADLINE != 0 && task.hasDueDate()) add(Alarm.whenOverdue(0))
        }
        // Gson wrote the Java field names before May 2020 and the Kotlin names after
        json.long("reminderPeriod", "notifications")?.let { add(Alarm(time = it, type = TYPE_RANDOM)) }
        json.long("reminderSnooze", "snoozeTime")?.let { add(Alarm(time = it, type = TYPE_SNOOZE)) }
    }

    private suspend fun findTagData(uid: String, name: String): TagData? =
        tagDataDao.getByUuid(uid) ?: tagDataDao.getTagByName(name)

    class ImportResult {
        var taskCount = 0
        var importCount = 0
        var skipCount = 0
    }

    companion object {
        private const val TAG = "BackupImporter"
        const val DIRTY_FLAG_VERSION = 150706
        private const val LEGACY_FILTER_VERSION = 130200 // Upgrade_13_2.VERSION
        private const val ALL_DAY_ALARMS_VERSION = 141300 // Upgrade_14_13.VERSION
        private const val LEGACY_COLOR_VERSION = 675 // Upgrader.V8_2
        private const val LEGACY_PICTURE_VERSION = 546 // Upgrader.V6_4
        private const val LOCAL_TASKS_VERSION = 90600 // Upgrader.V9_6
        private const val LEGACY_ALARMS_VERSION = 120400 // Upgrader.V12_4
        private const val PLACE_RADIUS_VERSION = 120601 // Upgrader.V12_6
        private const val REPEAT_FROM_VERSION = 120800 // Upgrader.V12_8
        internal fun themeToColor(version: Int, color: Int) =
            if (version < LEGACY_COLOR_VERSION) ColorProvider.legacyColor(color) else color

        private fun lastSyncValues(rawJson: String, key: String): List<Long?> = try {
            lenientJson.parseToJsonElement(rawJson).jsonObject[key]?.jsonArray
                ?.map { entry ->
                    entry.jsonObject["cd_last_sync"]?.jsonPrimitive?.longOrNull
                        ?: entry.jsonObject["lastSync"]?.jsonPrimitive?.longOrNull
                }
                .orEmpty()
        } catch (e: Exception) {
            Logger.w(e, tag = TAG) { "Failed to parse lastSync values from backup" }
            emptyList()
        }

        private fun JsonObject?.long(vararg names: String): Long? =
            names.firstNotNullOfOrNull { this?.get(it)?.jsonPrimitive?.longOrNull }?.takeIf { it > 0 }

        /** Folds a pre-12.8 `repeatUntil` into the RRULE, like `MIGRATION_84_85`. */
        private fun String.withUntil(until: Long?): String {
            if (until == null) return this
            val date = DateTime(until)
            return try {
                Recur.parse(this).copy(until = Until.Date(date.year, date.monthOfYear, date.dayOfMonth)).toString()
            } catch (e: Exception) {
                Logger.w(e, tag = TAG) { "Failed to add repeatUntil to $this" }
                this
            }
        }

        /** Keep in sync with `CommonMigrations.MIGRATION_92_93` */
        private fun reconstructDirtyVersion(lastSync: Long?, modified: Long): Pair<Long, Long> = when {
            lastSync == null || lastSync == 0L -> 1L to 0L
            modified > lastSync -> 2L to 1L
            else -> 1L to 1L
        }
    }
}
