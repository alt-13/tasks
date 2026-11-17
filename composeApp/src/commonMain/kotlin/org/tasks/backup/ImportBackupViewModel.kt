package org.tasks.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportBackupViewModel(
    private val picker: BackupFilePicker,
    private val importer: BackupImporter,
    openedFiles: OpenedBackupFiles,
) : ViewModel() {
    sealed interface State {
        data object Idle : State
        data class Confirm(val file: String) : State
        data class Importing(val taskCount: Int) : State
        data class Done(val file: String, val result: BackupImporter.ImportResult) : State
        data object Failed : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null
    private var confirmation: CompletableDeferred<Boolean>? = null

    init {
        viewModelScope.launch {
            openedFiles.requests.collect { file ->
                job?.join()
                // a file another app sent us is only imported once the user agrees
                import(file, confirm = true)
                job?.join()
            }
        }
    }

    fun import() = import(picker, confirm = false)

    private fun import(source: BackupFilePicker, confirm: Boolean) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = try {
                source.pick { file, openSource ->
                    if (confirm && !confirmed(file)) {
                        State.Idle
                    } else {
                        _state.value = State.Importing(0)
                        // once started, finish even if the screen goes away, like Android
                        withContext(NonCancellable) {
                            State.Done(file, importer.importTasks(openSource) { _state.value = State.Importing(it) })
                        }
                    }
                } ?: State.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, tag = TAG) { "Import failed" }
                State.Failed
            }
        }
    }

    private suspend fun confirmed(file: String): Boolean {
        val answer = CompletableDeferred<Boolean>()
        confirmation = answer
        _state.value = State.Confirm(file)
        return answer.await()
    }

    fun confirm() {
        confirmation?.complete(true)
    }

    fun dismiss() {
        confirmation?.complete(false)
        _state.value = State.Idle
    }

    companion object {
        private const val TAG = "ImportBackupViewModel"
    }
}
