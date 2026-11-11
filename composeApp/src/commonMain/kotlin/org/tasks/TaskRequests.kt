package org.tasks

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withTimeoutOrNull

class TaskRequests {
    class OpenRequest internal constructor(
        val destination: TaskEditDestination,
        private val opened: CompletableDeferred<Boolean>,
    ) {
        fun complete(accepted: Boolean) {
            opened.complete(accepted)
        }
    }

    private val _openRequests = MutableSharedFlow<OpenRequest>(extraBufferCapacity = 1)
    val openRequests: SharedFlow<OpenRequest> = _openRequests

    private val accepting = MutableStateFlow(true)

    fun acceptOpenRequests(enabled: Boolean) {
        accepting.value = enabled
    }

    private val _snoozeRequests = Channel<Long>(Channel.BUFFERED)
    val snoozeRequests: Flow<Long> = _snoozeRequests.receiveAsFlow()

    suspend fun open(destination: TaskEditDestination): Boolean {
        if (!accepting.value) {
            return false
        }

        if (_openRequests.subscriptionCount.value == 0) {
            return false
        }
        val opened = CompletableDeferred<Boolean>()
        if (!_openRequests.tryEmit(OpenRequest(destination, opened))) {
            return false
        }
        return withTimeoutOrNull(OPEN_TIMEOUT_MS) { opened.await() } ?: false
    }

    // a notification tap can launch the app before App starts collecting open requests
    suspend fun awaitOpenRequests(timeoutMs: Long = AWAIT_TIMEOUT_MS): Boolean =
        withTimeoutOrNull(timeoutMs) { _openRequests.subscriptionCount.first { it > 0 } } != null

    fun snooze(taskId: Long) {
        _snoozeRequests.trySend(taskId)
    }

    companion object {
        private const val OPEN_TIMEOUT_MS = 5_000L
        private const val AWAIT_TIMEOUT_MS = 30_000L
    }
}
