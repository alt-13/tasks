package org.tasks.backup

import kotlinx.serialization.json.Json
import org.tasks.data.entity.TaskAttachment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class BackupAttachmentTest {
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private fun decode(attachments: String) =
        json.decodeFromString<List<BackupAttachment>>(attachments)

    @Test
    fun convertsPre2022Attachment() {
        // a task_attachments row as written by backups before Aug 2022
        val attachment = decode(
            """[{"remoteId":"abc","taskId":"task-uuid","name":"photo.jpg","uri":"content://org.tasks/attachments/photo.jpg","contentType":"image/jpeg"}]"""
        ).single()

        assertEquals(
            TaskAttachment(remoteId = "abc", name = "photo.jpg", uri = "content://org.tasks/attachments/photo.jpg"),
            attachment.legacyFile(),
        )
    }

    @Test
    fun readsPathFromPre64Attachment() {
        val attachment = decode(
            """[{"remoteId":"abc","name":"a.jpg","path":"/sdcard/attachments/a.jpg","contentType":"image/"}]"""
        ).single()

        assertEquals("/sdcard/attachments/a.jpg", attachment.legacyFile()?.uri)
    }

    @Test
    fun currentAttachmentHasNoLegacyFile() {
        val attachment = decode("""[{"attachmentUid":"abc"}]""").single()

        assertEquals("abc", attachment.attachmentUid)
        assertNull(attachment.legacyFile())
    }

    @Test
    fun generatesUuidForLegacyAttachmentWithoutOne() {
        val (noUuid, nullUuid) = decode(
            """[{"remoteId":"0","name":"a","uri":"file:///a"},{"remoteId":null,"uri":"file:///b"}]"""
        ).map { it.legacyFile()!! }

        assertNotEquals("0", noUuid.remoteId)
        assertNotEquals(noUuid.remoteId, nullUuid.remoteId)
        assertEquals("", nullUuid.name)
    }

    @Test
    fun malformedAttachmentDecodesWithoutFile() {
        val attachment = decode("""[{"remoteId":"abc","name":"a"},{}]""")

        assertEquals(listOf(null, null), attachment.map { it.legacyFile() })
    }
}
