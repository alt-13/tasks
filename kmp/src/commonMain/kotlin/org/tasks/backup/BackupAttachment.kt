package org.tasks.backup

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import org.tasks.data.UUIDHelper
import org.tasks.data.entity.Task
import org.tasks.data.entity.TaskAttachment

/**
 * A task's attachment in a backup. Since Aug 2022 (database 86) only [attachmentUid] is set and the
 * file is in `taskAttachments`. Older backups store the whole `task_attachments` row instead.
 * Every field is optional so either shape decodes.
 */
@Serializable
class BackupAttachment @OptIn(ExperimentalSerializationApi::class) constructor(
    val attachmentUid: String? = null,
    val remoteId: String? = null,
    val name: String? = null,
    @JsonNames("path") // before 6.4
    val uri: String? = null,
) {
    /** The file of a pre-2022 attachment, mapped like `MIGRATION_85_86`. Null for current backups. */
    fun legacyFile(): TaskAttachment? {
        if (attachmentUid != null || uri.isNullOrBlank()) return null
        return TaskAttachment(
            // old rows inserted without createNew() could lack a uuid
            remoteId = remoteId.takeUnless { Task.isUuidEmpty(it) } ?: UUIDHelper.newUUID(),
            name = name.orEmpty(),
            uri = uri,
        )
    }
}
