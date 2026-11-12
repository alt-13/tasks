package org.tasks.backup

import com.todoroo.astrid.service.Upgrade_15_13
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.tasks.DatabaseTest
import org.tasks.caldav.FileStorage
import org.tasks.caldav.VtodoCache
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavAccount.Companion.TYPE_CALDAV
import org.tasks.data.entity.CaldavAccount.Companion.TYPE_GOOGLE_TASKS
import org.tasks.data.entity.CaldavAccount.Companion.TYPE_MICROSOFT
import org.tasks.data.entity.CaldavAccount.Companion.TYPE_TASKS
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.TagData
import org.tasks.data.entity.Task

class BackupImporterTest : DatabaseTest() {
    @get:Rule val folder = TemporaryFolder()

    private val taskDao = db.taskDao()
    private val caldavDao = db.caldavDao()
    private val tagDataDao = db.tagDataDao()
    private val vtodoCache by lazy { VtodoCache(caldavDao, FileStorage(folder.root.path)) }
    private val importer by lazy {
        BackupImporter(
            tagDataDao = tagDataDao,
            userActivityDao = db.userActivityDao(),
            taskDao = taskDao,
            dirtyDao = db.dirtyDao(),
            locationDao = db.locationDao(),
            refreshBroadcaster = mock(),
            alarmDao = db.alarmDao(),
            tagDao = db.tagDao(),
            filterDao = db.filterDao(),
            taskAttachmentDao = db.taskAttachmentDao(),
            caldavDao = caldavDao,
            appPreferences = mock { onBlocking { isDefaultDueTimeEnabled() } doReturn true },
            taskListMetadataDao = db.taskListMetadataDao(),
            vtodoCache = vtodoCache,
            analytics = mock(),
        )
    }

    private suspend fun import(json: String) = importer.importTasks({ Buffer().writeUtf8(json) })

    /** A backup as the exporter writes it, holding [tasks] in [calendar] of [account]. */
    private fun backup(
        account: CaldavAccount? = null,
        calendar: CaldavCalendar? = null,
        tasks: List<TaskBackup> = emptyList(),
        tags: List<TagData> = emptyList(),
        version: Int = CURRENT,
    ) = """{"version":$version,"timestamp":0,"data":{"tasks":${Json.encodeToString(tasks)},""" +
        """"tags":${Json.encodeToString(tags)},"caldavAccounts":${Json.encodeToString(listOfNotNull(account))},""" +
        """"caldavCalendars":${Json.encodeToString(listOfNotNull(calendar))},"intPrefs":{}}}"""

    private fun account(
        uuid: String,
        accountType: Int = TYPE_TASKS,
        url: String? = "https://caldav.tasks.org/calendars/user1/",
        username: String? = "user@test.com",
    ) = CaldavAccount(uuid = uuid, accountType = accountType, url = url, username = username)

    private fun calendar(account: String, uuid: String, url: String? = "https://caldav.tasks.org/calendars/user1/tasks/") =
        CaldavCalendar(account = account, uuid = uuid, url = url, name = "Tasks")

    private fun task(title: String, calendar: String, remoteId: String, obj: String = "$remoteId.ics") = TaskBackup(
        task = Task(title = title, remoteId = "uuid-$title"),
        caldavTasks = listOf(CaldavTask(calendar = calendar, remoteId = remoteId, obj = obj)),
    )

    private suspend fun setup(account: CaldavAccount, calendar: CaldavCalendar? = null, vararg tasks: TaskBackup) {
        caldavDao.insert(account)
        calendar?.let { caldavDao.insert(it) }
        tasks.forEach {
            val task = it.task.copy()
            taskDao.createNew(task)
            it.caldavTasks!!.forEach { caldavTask -> caldavDao.insert(caldavTask.copy(task = task.id)) }
        }
    }

    private fun assertResult(taskCount: Int, importCount: Int, skipCount: Int, result: BackupImporter.ImportResult) {
        assertEquals(listOf(taskCount, importCount, skipCount), listOf(result.taskCount, result.importCount, result.skipCount))
    }

    @Test
    fun backupAndRestore() = runBlocking {
        val result = import(backup(account("acc-1"), calendar("acc-1", "cal-1"), listOf(task("test task", "cal-1", "remote-1"))))

        assertResult(1, 1, 0, result)
        assertEquals(listOf("test task"), taskDao.getAll().map { it.title })
        assertEquals(1, caldavDao.getAccounts().size)
        assertEquals(1, caldavDao.getCalendars().size)
    }

    @Test
    fun skipDuplicatesByUuid() = runBlocking {
        taskDao.createNew(Task(title = "test task", remoteId = "uuid-1"))

        val result = import(backup(tasks = listOf(TaskBackup(Task(title = "test task", remoteId = "uuid-1")))))

        assertResult(1, 0, 1, result)
        assertEquals(1, taskDao.getAll().size)
    }

    @Test
    fun deduplicateByCaldavUrl() = runBlocking {
        setup(account("acc-B"), calendar("acc-B", "cal-B"), task("test task", "cal-B", "remote-1"))

        val result = import(backup(account("acc-A"), calendar("acc-A", "cal-A"), listOf(task("test task", "cal-A", "remote-1"))))

        assertResult(1, 0, 1, result)
        assertEquals(1, taskDao.getAll().size)
        assertEquals(1, caldavDao.getAccounts().size)
        assertEquals(1, caldavDao.getCalendars().size)
    }

    @Test
    fun deduplicateCaldavAccount() = runBlocking {
        val url = "https://nextcloud.example.com/remote.php/dav/calendars/user/"
        setup(account("acc-B", TYPE_CALDAV, url, "user"), calendar("acc-B", "cal-B", "${url}tasks/"), task("nc", "cal-B", "nc-1"))

        val result = import(
            backup(account("acc-A", TYPE_CALDAV, url, "user"), calendar("acc-A", "cal-A", "${url}tasks/"), listOf(task("nc", "cal-A", "nc-1")))
        )

        assertResult(1, 0, 1, result)
        assertEquals(1, taskDao.getAll().size)
        assertEquals(1, caldavDao.getAccounts().size)
        assertEquals(1, caldavDao.getCalendars().size)
    }

    @Test
    fun deduplicateCaldavUrlsStoredBeforeCanonicalization() = runBlocking {
        val url = "https://nextcloud.example.com/remote.php/dav/calendars/foo%40example.com/"
        val canonicalUrl = "https://nextcloud.example.com/remote.php/dav/calendars/foo@example.com/"
        setup(account("acc-B", TYPE_CALDAV, canonicalUrl, "user"), calendar("acc-B", "cal-B", "${canonicalUrl}tasks/"), task("nc", "cal-B", "nc-1"))

        val result = import(
            backup(
                account("acc-A", TYPE_CALDAV, url, "user"),
                calendar("acc-A", "cal-A", "${url}tasks/"),
                listOf(task("nc", "cal-A", "nc-1")),
                version = Upgrade_15_13.VERSION - 1,
            )
        )

        assertResult(1, 0, 1, result)
        assertEquals(1, taskDao.getAll().size)
        assertEquals(listOf(canonicalUrl), caldavDao.getAccounts().map { it.url })
        assertEquals(listOf("${canonicalUrl}tasks/"), caldavDao.getCalendars().map { it.url })
    }

    @Test
    fun deduplicateMicrosoftByUsername() = runBlocking {
        setup(account("acc-B", TYPE_MICROSOFT, null, "user@outlook.com"), calendar("acc-B", "cal-B", "list-1"), task("ms", "cal-B", "ms-1", "ms-1"))

        val result = import(
            backup(account("acc-A", TYPE_MICROSOFT, null, "user@outlook.com"), calendar("acc-A", "cal-A", "list-1"), listOf(task("ms", "cal-A", "ms-1", "ms-1")))
        )

        assertResult(1, 0, 1, result)
        assertEquals(1, taskDao.getAll().size)
        assertEquals(1, caldavDao.getAccounts().size)
    }

    @Test
    fun importNewTaskToRemappedCalendar() = runBlocking {
        // Device B only has task 1 synced
        setup(account("acc-B"), calendar("acc-B", "cal-B"), task("task 1", "cal-B", "remote-1"))

        val result = import(
            backup(account("acc-A"), calendar("acc-A", "cal-A"), listOf(task("task 1", "cal-A", "remote-1"), task("task 2", "cal-A", "remote-2")))
        )

        assertResult(2, 1, 1, result)
        assertEquals(2, taskDao.getAll().size)
        val task2 = taskDao.getAll().first { it.title == "task 2" }
        assertEquals("cal-B", caldavDao.getTasks(task2.id).single().calendar)
    }

    @Test
    fun doNotDeduplicateDifferentUsersOnSameServer() = runBlocking {
        setup(account("acc-B", TYPE_CALDAV, "https://server.example.com/dav/user2/", "user2"))

        import(backup(account("acc-A", TYPE_CALDAV, "https://server.example.com/dav/user1/", "user1")))

        assertEquals(2, caldavDao.getAccounts().size)
    }

    @Test
    fun backupAndRestorePreservesTags() = runBlocking {
        import(backup(tags = listOf(TagData(name = "important", remoteId = "tag-1"))))

        assertEquals(listOf("important"), tagDataDao.getAll().map { it.name })
    }

    @Test
    fun importsCurrentFormat() = runBlocking {
        val result = import(BACKUP_V151306)

        assertResult(2, 2, 0, result)
        val cafe = taskDao.getAll().single { it.title == "Café ✓ 🙂" }
        val caldavTask = caldavDao.getTasks(cafe.id).single()
        assertEquals(3L to 2L, db.dirtyDao().getDirtyState(caldavTask.id)!!.let { it.dirtyVersion to it.syncedVersion })
        assertEquals(listOf(-900000L), db.alarmDao().getAlarms(cafe.id).map { it.time })
        assertEquals(true, vtodoCache.getVtodo(caldavTask)?.contains("SUMMARY:Café"))
        val receipts = taskDao.getAll().single { it.title == "Receipts" }
        assertEquals(listOf("work"), db.tagDao().getTagsForTask(receipts.id).map { it.name })
        assertEquals(listOf("Scanned"), db.userActivityDao().getComments(receipts.id).map { it.message })
        assertEquals(listOf("att-1"), db.taskAttachmentDao().getAttachmentsForTask(receipts.id).map { it.attachmentUid })
    }

    @Test
    fun importsGoogleTasksPlacesAndLastSync() = runBlocking {
        val result = import(BACKUP_V111201)

        assertResult(2, 2, 0, result)
        assertEquals(TYPE_GOOGLE_TASKS, caldavDao.getAccountByUuid("alice@gmail.com")!!.accountType)
        assertEquals("alice@gmail.com", caldavDao.getCalendarByUuid("MTE2MjM0NTY3")!!.account)
        val passport = taskDao.getAll().single { it.title == "Renew passport" }
        assertEquals("MTE2MjM0NTY3", caldavDao.getTasks(passport.id).single().calendar)
        val plants = taskDao.getAll().single { it.title == "Water plants" }
        val geofence = db.locationDao().getGeofencesForTask(plants.id).single()
        assertEquals("Garden centre", db.locationDao().getByUid(geofence.place!!)!!.name)
        // no dirty versions in this backup: modified (1637150000000) before lastSync, so in sync
        val caldavTask = caldavDao.getTasks(plants.id).single()
        assertEquals(1L to 1L, db.dirtyDao().getDirtyState(caldavTask.id)!!.let { it.dirtyVersion to it.syncedVersion })
        assertEquals(listOf("Overdue"), db.filterDao().getFilters().map { it.title })
    }

    @Test
    fun rebuildsFiltersFromBefore13_2() = runBlocking {
        import(BACKUP_V491)

        val filter = db.filterDao().getFilters().single()
        assertEquals(true, filter.sql!!.contains("tasks.dueDate<='EOD()'"))
    }

    companion object {
        private const val CURRENT = 160000
    }
}
