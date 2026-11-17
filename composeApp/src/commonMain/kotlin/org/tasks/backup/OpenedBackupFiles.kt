package org.tasks.backup

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Backup files other apps opened in Tasks, each as a [BackupFilePicker] that "picks" that file. */
class OpenedBackupFiles {
    // buffered: on a cold launch the file arrives before the app collects it
    private val files = Channel<BackupFilePicker>(Channel.UNLIMITED)
    val requests: Flow<BackupFilePicker> = files.receiveAsFlow()

    fun open(file: BackupFilePicker) {
        files.trySend(file)
    }
}
