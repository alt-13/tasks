package org.tasks.jobs

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import org.koin.mp.KoinPlatform
import org.tasks.analytics.Reporting
import org.tasks.ensureStarted
import org.tasks.sync.SyncRunner
import org.tasks.sync.SyncSource
import org.tasks.sync.launchBackgroundSync
import platform.Foundation.NSBundle

private const val TAG = "BackgroundRefresh"

/** The BGAppRefreshTask identifier, `$(PRODUCT_BUNDLE_IDENTIFIER).refresh` from Info.plist. */
val backgroundRefreshTaskId: String?
    get() = (NSBundle.mainBundle.objectForInfoDictionaryKey("BGTaskSchedulerPermittedIdentifiers") as? List<*>)
        ?.firstOrNull() as? String

/**
 * Called from the BGAppRefreshTask launch handler. Returns a function for the task's expiration
 * handler that cancels the sync; [completion] is then called with false.
 */
fun onBackgroundRefresh(completion: (Boolean) -> Unit): () -> Unit {
    ensureStarted()
    Logger.i(TAG) { "background refresh started" }
    val koin = KoinPlatform.getKoin()
    val job = koin.get<CoroutineScope>().launchBackgroundSync(
        onError = { koin.get<Reporting>().reportException(it) },
        completion = { finished ->
            Logger.d(TAG) { "background refresh ${if (finished) "finished" else "failed or expired"}" }
            completion(finished)
        },
    ) {
        koin.get<SyncRunner>().syncNow(SyncSource.BACKGROUND)
    }
    return { job.cancel() }
}
