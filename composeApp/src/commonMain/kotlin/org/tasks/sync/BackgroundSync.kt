package org.tasks.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [sync] for an OS-granted background slot, then [afterSync], even if [sync] failed.
 * [completion] is called exactly once, after the work has stopped: true if [sync] finished, false
 * if it failed or the returned job was cancelled. Once the job is cancelled, [afterSync] no longer
 * starts; once started, it runs to the end, so it is never left half done.
 */
fun CoroutineScope.launchBackgroundSync(
    onError: (Throwable) -> Unit,
    completion: (Boolean) -> Unit,
    afterSync: suspend () -> Unit = {},
    sync: suspend () -> Unit,
): Job {
    var success = false
    return launch {
        try {
            sync()
            success = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            try {
                afterSync()
            } catch (e: Exception) {
                onError(e)
            }
        }
    }.apply { invokeOnCompletion { completion(success) } }
}
