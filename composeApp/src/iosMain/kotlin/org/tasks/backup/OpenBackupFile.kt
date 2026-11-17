package org.tasks.backup

import co.touchlab.kermit.Logger
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.Source
import org.koin.mp.KoinPlatform
import org.tasks.ensureStarted
import platform.Foundation.NSURL

private const val TAG = "OpenBackupFile"

/** Called by the app when another app opens a file in Tasks (Files, share sheet, AirDrop). */
fun openBackupFile(url: NSURL) {
    if (!url.fileURL) {
        Logger.d(tag = TAG) { "Ignoring $url" }
        return
    }
    ensureStarted()
    KoinPlatform.getKoin().get<OpenedBackupFiles>().open(OpenedBackupFile(url))
}

private class OpenedBackupFile(private val url: NSURL) : BackupFilePicker {
    override suspend fun <T> pick(read: suspend (name: String, openSource: () -> Source) -> T): T? =
        try {
            readBackup(url, read)
        } finally {
            // copies iOS made for us (share sheet, AirDrop) would pile up otherwise
            url.path?.takeIf { it.contains("/Documents/Inbox/") }?.let {
                runCatching { FileSystem.SYSTEM.delete(it.toPath()) }
                    .onFailure { e -> Logger.e(e, tag = TAG) { "Failed to delete $it" } }
            }
        }
}

internal suspend fun <T> readBackup(url: NSURL, read: suspend (name: String, openSource: () -> Source) -> T): T? {
    val path = url.path ?: return null
    val name = url.lastPathComponent ?: path
    // a file outside the sandbox (picked, or opened in place) can only be read between start and stop
    val accessing = url.startAccessingSecurityScopedResource()
    try {
        return read(name) { FileSystem.SYSTEM.source(path.toPath()) }
    } finally {
        if (accessing) {
            url.stopAccessingSecurityScopedResource()
        }
    }
}
