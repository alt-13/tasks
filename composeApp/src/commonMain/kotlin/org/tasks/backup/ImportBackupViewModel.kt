package org.tasks.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportBackupViewModel(
    private val picker: BackupFilePicker,
    private val importer: BackupImporter,
) : ViewModel() {
    sealed interface State {
        data object Idle : State
        data class Importing(val taskCount: Int) : State
        data class Done(val file: String, val result: BackupImporter.ImportResult) : State
        data object Failed : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    private var job: Job? = null

    fun import() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.value = try {
                picker.pick { file, openSource ->
                    _state.value = State.Importing(0)
                    // once started, finish even if the screen goes away, like Android
                    withContext(NonCancellable) {
                        State.Done(file, importer.importTasks(openSource) { _state.value = State.Importing(it) })
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

    fun dismiss() {
        _state.value = State.Idle
    }

    companion object {
        private const val TAG = "ImportBackupViewModel"
    }
}
