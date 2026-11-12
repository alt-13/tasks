package org.tasks.jobs

import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tasks.analytics.Reporting
import org.tasks.data.dao.CaldavDao
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavAccount.Companion.TYPE_CALDAV
import org.tasks.data.entity.CaldavAccount.Companion.TYPE_TASKS
import org.tasks.data.entity.Task
import org.tasks.extensions.guarded
import org.tasks.service.TaskMigrator
import org.tasks.sync.SyncRunner
import org.tasks.sync.SyncSource
import org.tasks.time.DateTimeUtils2.currentTimeMillis
import org.tasks.time.printTimestamp
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.BackgroundTasks.BGTaskSchedulerErrorCodeUnavailable
import platform.BackgroundTasks.BGTaskSchedulerErrorDomain
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.dateWithTimeIntervalSince1970

private const val TAG = "IosBackgroundWork"

// a request from an earlier launch may still be pending
private const val UNKNOWN = -1L

/** Schedules the app's single `BGAppRefreshTaskRequest`; see [RefreshRequests]. */
class IosBackgroundWork(
    private val scope: CoroutineScope,
    private val runner: SyncRunner,
    private val caldavDao: CaldavDao,
    private val reporting: Reporting,
    private val taskMigrator: () -> TaskMigrator,
) : BackgroundWork {
    private val mutex = Mutex()
    private var requests = RefreshRequests()
    private var submitted: Long? = UNKNOWN

    // no calendar integration on iOS
    override fun updateCalendar(task: Task) {}

    // the blog feed checker is JVM-only and the shared app never schedules it
    override suspend fun scheduleBlogFeedCheck() {}

    override suspend fun sync(source: SyncSource) = runner.sync(source)

    override fun migrateLocalTasks(localAccount: CaldavAccount, tasksAccount: CaldavAccount) {
        scope.launch { taskMigrator().migrateLocalTasks(localAccount, tasksAccount) }
    }

    override suspend fun scheduleRefresh(timestamp: Long) = update { it.refresh(timestamp) }

    override fun updateBackgroundSync() {
        scope.launch {
            guarded(TAG, "Failed to update background sync", Unit, onFailure = { reporting.reportException(it) }) {
                val enabled = syncEnabled()
                update { it.backgroundSync(enabled, currentTimeMillis()) }
            }
        }
    }

    /** Called at the start of every background refresh, since iOS doesn't repeat them. */
    suspend fun onRefreshStarted() {
        val enabled = syncEnabled()
        val now = currentTimeMillis()
        update(consumed = true) { it.started(now).backgroundSync(enabled, now) }
    }

    // the account types the iOS SyncRunner syncs
    private suspend fun syncEnabled() = caldavDao.getAccounts(TYPE_CALDAV, TYPE_TASKS).isNotEmpty()

    private suspend fun update(
        consumed: Boolean = false,
        change: (RefreshRequests) -> RefreshRequests,
    ) = mutex.withLock {
        if (consumed) {
            submitted = null
        }
        requests = change(requests)
        val next = requests.next
        if (next == submitted) {
            return@withLock
        }
        val id = backgroundRefreshTaskId ?: return@withLock
        if (next == null) {
            Logger.d(tag = TAG) { "cancelling background refresh" }
            BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(id)
            submitted = null
        } else if (submit(id, next)) {
            submitted = next
        }
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun submit(id: String, at: Long): Boolean = memScoped {
        val request = BGAppRefreshTaskRequest(identifier = id)
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSince1970(at / 1000.0)
        val error = alloc<ObjCObjectVar<NSError?>>()
        if (BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error.ptr)) {
            Logger.d(tag = TAG) { "background refresh requested for ${printTimestamp(at)}" }
            return@memScoped true
        }
        val e = error.value
        val unavailable = e != null && e.domain == BGTaskSchedulerErrorDomain &&
            e.code == BGTaskSchedulerErrorCodeUnavailable
        if (unavailable) {
            // Background App Refresh turned off, Low Power Mode or the simulator
            Logger.i(tag = TAG) { "background refresh unavailable" }
        } else {
            reporting.reportException(IllegalStateException("Failed to request background refresh: $e"))
        }
        false
    }
}
