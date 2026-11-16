package org.tasks.sync.microsoft

import kotlinx.datetime.TimeZone
import org.tasks.data.createDueDate
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Task
import org.tasks.sync.microsoft.MicrosoftConverter.applyRemote
import org.tasks.sync.microsoft.MicrosoftConverter.applySubtask
import org.tasks.sync.microsoft.MicrosoftConverter.toChecklistItem
import org.tasks.sync.microsoft.MicrosoftConverter.toRemote
import org.tasks.time.DateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class MicrosoftConverterTest {

    private fun remote(
        completed: Tasks.Task.DateTime? = null,
        due: Tasks.Task.DateTime? = null,
        created: String? = "2020-07-24T23:03:09.1234567Z",
        modified: String? = "2021-03-21T15:41:39.4206797Z",
    ) = Tasks.Task(
        title = "title",
        completedDateTime = completed,
        dueDateTime = due,
        createdDateTime = created,
        lastModifiedDateTime = modified,
    )

    private fun applied(remote: Tasks.Task) = Task().apply { applyRemote(remote, Task.Priority.NONE) }

    @Test
    fun parsesUtcTimestampsTruncatedToMillis() {
        val task = applied(remote())
        assertEquals(1595631789123, task.creationDate)
        assertEquals(1616341299420, task.modificationDate)
    }

    @Test
    fun parsesTimestampWithOffset() {
        val task = applied(remote(created = "2020-07-24T23:03:09+02:00"))
        assertEquals(1595624589000, task.creationDate)
    }

    @Test
    fun parsesCompletedDateTimeInUtc() {
        val task = applied(remote(completed = Tasks.Task.DateTime("2020-08-26T00:00:00.0000000", "UTC")))
        assertEquals(1598400000000, task.completionDate)
    }

    @Test
    fun parsesCompletedDateTimeInOlsonZone() {
        val task = applied(remote(completed = Tasks.Task.DateTime("2020-08-26T00:00:00.0000000", "Europe/Berlin")))
        assertEquals(1598392800000, task.completionDate)
    }

    @Test
    fun unknownZoneNameIsUtc() {
        val task = applied(remote(completed = Tasks.Task.DateTime("2020-08-26T00:00:00.0000000", "Pacific Standard Time")))
        assertEquals(1598400000000, task.completionDate)
    }

    @Test
    fun digitsAfterTheDotReplaceTheSeconds() {
        val task = applied(remote(completed = Tasks.Task.DateTime("2020-08-26T10:20:30.0000005", "UTC")))
        assertEquals(1598437205000, task.completionDate)
    }

    @Test
    fun notCompletedWithoutCompletedDateTime() {
        assertEquals(0L, applied(remote()).completionDate)
    }

    @Test
    fun parsesDueDateWithoutTime() {
        val task = applied(remote(due = Tasks.Task.DateTime("2020-08-25T00:00:00.0000000", "UTC")))
        assertEquals(1598313600000, task.dueDate)
    }

    @Test
    fun keepsLocalDueTime() {
        val dueTime = DateTime(2020, 8, 20, 14, 30, 1).millis
        val task = Task(dueDate = dueTime).apply {
            applyRemote(remote(due = Tasks.Task.DateTime("2020-08-25T00:00:00.0000000", "UTC")), Task.Priority.NONE)
        }
        val expected = DateTime(1598313600000)
            .withHourOfDay(14)
            .withMinuteOfHour(30)
            .withSecondOfMinute(1)
        assertEquals(createDueDate(Task.URGENCY_SPECIFIC_DAY_TIME, expected.millis), task.dueDate)
    }

    @Test
    fun parsesChecklistItem() {
        val task = Task().apply {
            applySubtask(
                parent = 1,
                parentCompletionDate = 0,
                checklistItem = Tasks.Task.ChecklistItem(
                    displayName = "item",
                    createdDateTime = "2020-07-24T23:03:09.1234567Z",
                    isChecked = true,
                    checkedDateTime = "2021-03-21T15:41:39.42Z",
                ),
            )
        }
        assertEquals(1595631789123, task.creationDate)
        assertEquals(1616341299420, task.completionDate)
    }

    @Test
    fun writesDatesInLocalAndUtcFormats() {
        val task = Task(
            dueDate = DateTime(2020, 8, 25, 12).millis,
            completionDate = DateTime(2020, 8, 26, 10, 20, 30, 456).millis,
            creationDate = 1595631789123,
            modificationDate = 1616341299420,
        )
        val remote = task.toRemote(CaldavTask(calendar = "list"), emptyList())
        val zone = TimeZone.currentSystemDefault().id
        assertEquals(Tasks.Task.DateTime("2020-08-25T00:00:00.0000000", zone), remote.dueDateTime)
        assertEquals(Tasks.Task.DateTime("2020-08-26T10:20:30.4560000", zone), remote.completedDateTime)
        assertEquals("2020-07-24T23:03:09.1230000Z", remote.createdDateTime)
        assertEquals("2021-03-21T15:41:39.4200000Z", remote.lastModifiedDateTime)
    }

    @Test
    fun writesChecklistItemDatesInUtc() {
        val item = Task(creationDate = 1595631789123, completionDate = 1616341299420)
            .toChecklistItem("id")
        assertEquals("2020-07-24T23:03:09.1230000Z", item.createdDateTime)
        assertEquals("2021-03-21T15:41:39.4200000Z", item.checkedDateTime)
    }
}
