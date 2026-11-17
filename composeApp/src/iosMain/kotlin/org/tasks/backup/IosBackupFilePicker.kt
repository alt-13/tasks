package org.tasks.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.Source
import org.tasks.extensions.keyWindow
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.darwin.NSObject
import kotlin.coroutines.resume

class IosBackupFilePicker : BackupFilePicker {
    // UIDocumentPickerViewController holds its delegate weakly
    private var delegate: NSObject? = null

    override suspend fun <T> pick(read: suspend (name: String, openSource: () -> Source) -> T): T? {
        val url = withContext(Dispatchers.Main) { present() } ?: return null
        return readBackup(url, read)
    }

    private suspend fun present(): NSURL? = suspendCancellableCoroutine { continuation ->
        var presenter: UIViewController = keyWindow().rootViewController ?: run {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        while (true) {
            presenter = presenter.presentedViewController ?: break
        }
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeJSON), asCopy = false)
        val pickerDelegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                finish(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                finish(null)
            }

            private fun finish(url: NSURL?) {
                delegate = null
                if (continuation.isActive) continuation.resume(url)
            }
        }
        delegate = pickerDelegate
        picker.delegate = pickerDelegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }
}
