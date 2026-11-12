package org.tasks.backup

import co.touchlab.kermit.Logger
import com.todoroo.astrid.service.Upgrade_15_13
import com.todoroo.astrid.service.Upgrade_15_13.Companion.canonicalized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
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
import org.tasks.data.entity.TaskAttachment
import org.tasks.data.entity.TaskListMetadata
import org.tasks.filters.LegacyFilterCriteria
import org.tasks.preferences.AppPreferences

/** Common port of Android's `TasksJsonImporter`. */
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
) {
    /** [openSource] is called twice: the backup is read once for metadata and once for tasks. */
    suspend fun importTasks(
        openSource: () -> Source,
        onProgress: (suspend (taskCount: Int) -> Unit)? = null,
    ): ImportResult = withContext(Dispatchers.IO) {
        val result = ImportResult()
        val accountUuidMap = mutableMapOf<String, String>()
        val calendarUuidMap = mutableMapOf<String, String>()
        try {
            val version = BackupJsonReader(openSource().buffer()).use {
                importMetadata(it, accountUuidMap, calendarUuidMap)
            }
            BackupJsonReader(openSource().buffer()).use {
                importTasks(it, onProgress, version, result, calendarUuidMap)
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
    ): Int {
        val importedAccounts = mutableMapOf<String, CaldavAccount>()
        var version = 0
        reader.readData { element ->
            when (element) {
                "version" -> version = reader.nextInt().also { Logger.d(tag = TAG) { "Backup version: $it" } }
                "timestamp" -> reader.nextLong().let { Logger.d(tag = TAG) { "Backup timestamp: $it" } }
                "places" -> reader.forEach<Place> { place ->
                    if (locationDao.getByUid(place.uid!!) == null) {
                        locationDao.insert(place)
                    }
                }
                "tags" -> reader.forEach<TagData> { tagData ->
                    if (findTagData(tagData.remoteId!!, tagData.name!!) == null) {
                        tagDataDao.insert(tagData)
                    }
                }
                "filters" -> reader.forEach<Filter> {
                    val filter = if (version < LEGACY_FILTER_VERSION) LegacyFilterCriteria.rebuildFilter(it) else it
                    if (filterDao.getByName(filter.title!!) == null) {
                        filterDao.insert(filter)
                    }
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
                        caldavDao.insert(calendar.copy(account = remappedAccount))
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
                                color = googleTaskList.color ?: 0,
                                icon = googleTaskList.icon?.toString(),
                            )
                        )
                    }
                }
                else -> skip(reader, element)
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
                        importTask(backup, version, rawJson, result, calendarUuidMap)
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
        task.suppressRefresh()
        task.suppressSync()
        taskDao.createNew(task)
        val taskId = task.id
        val skipAllDayAlarms = version < ALL_DAY_ALARMS_VERSION
                && task.hasDueDate()
                && !task.hasDueTime()
                && !appPreferences.isDefaultDueTimeEnabled()
        backup.alarms
            ?.takeUnless { skipAllDayAlarms }
            ?.map { it.copy(task = taskId) }
            ?.let { alarmDao.insert(it) }
        backup.comments?.forEach { comment ->
            comment.targetId = task.uuid
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

        /** Keep in sync with `CommonMigrations.MIGRATION_92_93` */
        private fun reconstructDirtyVersion(lastSync: Long?, modified: Long): Pair<Long, Long> = when {
            lastSync == null || lastSync == 0L -> 1L to 0L
            modified > lastSync -> 2L to 1L
            else -> 1L to 1L
        }
    }
}
