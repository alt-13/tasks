package org.tasks.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs [sync] for an OS-granted background slot. [completion] is called exactly once, after the
 * work has stopped: true if [sync] finished, false if it failed or the returned job was cancelled.
 */
fun CoroutineScope.launchBackgroundSync(
    onError: (Throwable) -> Unit,
    completion: (Boolean) -> Unit,
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
    }.apply { invokeOnCompletion { completion(success) } }
}
