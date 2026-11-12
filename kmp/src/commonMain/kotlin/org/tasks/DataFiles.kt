package org.tasks

import okio.FileSystem
import okio.Path
import okio.SYSTEM
import org.tasks.billing.PurchaseEntitlement
import org.tasks.data.db.Database
import org.tasks.kmp.dataStoreFileName

private val dataFiles = listOf(
    Database.NAME,
    "${Database.NAME}-wal",
    "${Database.NAME}-shm",
    "${Database.NAME}-journal",
    dataStoreFileName,
    PurchaseEntitlement.FILE_NAME,
    "vtodo", // FileStorage.root
)

/**
 * Moves the database, preferences and files from [from] to [to], all or nothing.
 *
 * Does nothing if [to] already holds any of them, so data there is never overwritten or mixed
 * with data from [from]. If a move fails, moves back what was already moved and rethrows.
 */
fun moveDataFiles(from: Path, to: Path, fileSystem: FileSystem = FileSystem.SYSTEM) {
    if (dataFiles.any { fileSystem.exists(to / it) }) return
    val moved = mutableListOf<String>()
    try {
        dataFiles.filter { fileSystem.exists(from / it) }.forEach {
            fileSystem.atomicMove(from / it, to / it)
            moved += it
        }
    } catch (e: Exception) {
        moved.asReversed().forEach {
            try {
                fileSystem.atomicMove(to / it, from / it)
            } catch (rollback: Exception) {
                e.addSuppressed(rollback)
            }
        }
        throw e
    }
}
