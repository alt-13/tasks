package org.tasks.backup

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.tasks.backup.ImportBackupViewModel.State
import tasks.kmp.generated.resources.Res
import tasks.kmp.generated.resources.cancel
import tasks.kmp.generated.resources.import_failed
import tasks.kmp.generated.resources.import_failed_message
import tasks.kmp.generated.resources.import_progress_read
import tasks.kmp.generated.resources.import_summary_message
import tasks.kmp.generated.resources.import_summary_title
import tasks.kmp.generated.resources.ok
import tasks.kmp.generated.resources.returning_user_import_backup
import tasks.kmp.generated.resources.task_count

/** Confirmation, progress and result of a backup import, like Android's `ImportTasksDialog`. */
@Composable
fun ImportBackupDialog(state: State, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    if (state == State.Idle) return
    val done = state !is State.Importing
    AlertDialog(
        onDismissRequest = { if (done) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = done, dismissOnClickOutside = false),
        title = {
            Text(
                stringResource(
                    when (state) {
                        is State.Done -> Res.string.import_summary_title
                        State.Failed -> Res.string.import_failed
                        else -> Res.string.returning_user_import_backup
                    }
                )
            )
        },
        text = {
            when (state) {
                is State.Importing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        if (state.taskCount == 0) {
                            stringResource(Res.string.returning_user_import_backup)
                        } else {
                            stringResource(Res.string.import_progress_read, state.taskCount)
                        }
                    )
                }
                is State.Done -> Text(
                    stringResource(
                        Res.string.import_summary_message,
                        state.file,
                        tasks(state.result.taskCount),
                        tasks(state.result.importCount),
                        tasks(state.result.skipCount),
                        tasks(0),
                    )
                )
                is State.Confirm -> Text(state.file)
                else -> Text(stringResource(Res.string.import_failed_message))
            }
        },
        confirmButton = {
            if (done) {
                TextButton(onClick = if (state is State.Confirm) onConfirm else onDismiss) {
                    Text(stringResource(Res.string.ok))
                }
            }
        },
        dismissButton = {
            if (state is State.Confirm) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}

@Composable
private fun tasks(count: Int) = pluralStringResource(Res.plurals.task_count, count, count)
