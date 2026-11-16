package org.tasks.backup

import okio.Source

interface BackupFilePicker {
    /**
     * Lets the user pick a backup file and runs [read] while the file can be read, or returns null
     * if the user cancelled. [read] gets the file name and opens the file as often as it needs.
     */
    suspend fun <T> pick(read: suspend (name: String, openSource: () -> Source) -> T): T?
}
