package org.tasks.backup

import okio.Buffer
import okio.IOException
import org.tasks.data.GoogleTaskAccount
import org.tasks.data.GoogleTaskList
import org.tasks.data.entity.CaldavAccount
import org.tasks.data.entity.CaldavCalendar
import org.tasks.data.entity.Filter
import org.tasks.data.entity.Place
import org.tasks.data.entity.TagData
import org.tasks.data.entity.TaskAttachment
import org.tasks.data.entity.TaskListMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupJsonReaderTest {
    private fun reader(json: String) = BackupJsonReader(Buffer().writeUtf8(json))

    @Test
    fun copiesNestedValuesVerbatim() = reader(
        """{"a" : {"b":[1, "x,y]}", {"c":"\"q\\"}], "d":null}, "e": true}"""
    ).use {
        it.beginObject()
        assertEquals("a", it.nextName())
        assertEquals("""{"b":[1, "x,y]}", {"c":"\"q\\"}], "d":null}""", it.jsonString())
        assertEquals("e", it.nextName())
        assertEquals("true", it.jsonString())
        assertFalse(it.hasNext())
        it.endObject()
    }

    @Test
    fun readsNumbersAndSkipsValuesAcrossWhitespace() = reader(
        "{\n\t\"version\" : 151306 ,\r\n \"skip\": {\"x\": [1, {}]},\n \"timestamp\": 1793000000000\n}"
    ).use {
        it.beginObject()
        assertEquals("version", it.nextName())
        assertEquals(151306, it.nextInt())
        assertEquals("skip", it.nextName())
        it.skipValue()
        assertEquals("timestamp", it.nextName())
        assertEquals(1793000000000, it.nextLong())
        it.endObject()
    }

    @Test
    fun emptyContainersHaveNoNext() = reader("""{"a":[],"b":{}}""").use {
        it.beginObject()
        it.nextName()
        it.beginArray()
        assertFalse(it.hasNext())
        it.endArray()
        it.nextName()
        it.beginObject()
        assertFalse(it.hasNext())
        it.endObject()
        it.endObject()
    }

    @Test
    fun keepsUnicodeAndEscapes() = reader("""["Café ✓ 🙂 é"]""").use {
        val values = mutableListOf<String>()
        it.forEach<String> { value -> values.add(value) }
        assertEquals(listOf("Café ✓ 🙂 é"), values)
    }

    @Test
    fun truncatedInputThrowsIOException() {
        assertFailsWith<IOException> {
            reader("""{"data":{"tasks":[{"task":{"title":"Milk""").use {
                it.beginObject()
                it.nextName()
                it.skipValue()
            }
        }
    }

    @Test
    fun unexpectedTokenThrowsIOException() {
        assertFailsWith<IOException> { reader("[]").use { it.beginObject() } }
        assertFailsWith<IOException> { reader("{}").use { it.beginObject(); it.jsonString() } }
    }

    @Test
    fun readsFirstJsonFormat() {
        val backup = readBackup(BACKUP_V491)

        assertEquals(491, backup.version)
        assertEquals(listOf("Call mom", "Pay rent"), backup.tasks.map { it.task.title })
        val first = backup.tasks.first()
        assertEquals(1518562800000, first.task.dueDate)
        assertEquals("RRULE:FREQ=WEEKLY;INTERVAL=1;FROM=COMPLETION", first.task.recurrence)
        assertEquals(1518559200000, first.alarms!!.single().time)
        @Suppress("DEPRECATION")
        assertEquals("Home", first.locations!!.single().name)
        assertEquals("0f3c9a51d2", first.tags!!.single().tagUid)
        assertEquals("MDk4NzY1NDMyMQ", first.google!!.single().listId)
        assertEquals("""{"uri":"content://org.tasks/attachments/list.jpg"}""", first.comments!!.single().picture)
        assertTrue(""""lastSync": 1518472460000""" in backup.rawTasks.first())
        assertEquals(2, backup.tagData.single().color)
        assertEquals("dueDate\t0\t\t0\nimportance\t1\t\t1", backup.filters.single().criterion)
        assertEquals(7, backup.googleTaskLists.single().color)
        assertNull(backup.googleTaskLists.single().account)
    }

    @Test
    fun readsGsonFormatWithCaldavAndPreferences() {
        val backup = readBackup(BACKUP_V111201)

        assertEquals(111201, backup.version)
        val (caldav, google) = backup.tasks
        assertEquals("Ficus & cactus", caldav.task.notes)
        assertEquals(1637100000000, caldav.task.creationDate)
        assertEquals(6, caldav.task.ringFlags)
        assertTrue(caldav.geofences!!.single().isArrival)
        assertEquals("9c0e7a0b-3d1f-4f7e-8a51-2b6f0d9c4e11.ics", caldav.caldavTasks!!.single().obj)
        assertNull(caldav.vtodo)
        assertEquals(1L, google.google!!.single().remoteOrder)
        assertTrue(""""lastSync":1637150001000""" in backup.rawTasks.first())
        assertEquals("-1", backup.places.single().icon)
        assertEquals("1062", backup.tagData.single().icon)
        assertEquals(1041, backup.googleTaskLists.single().icon)
        assertEquals("alice@gmail.com", backup.googleTaskAccounts.single().account)
        assertEquals("https://cloud.example.com/remote.php/dav/", backup.caldavAccounts.single().url)
        assertEquals("a1f04c2e-9b8d-4e7f-a6b5-c4d3e2f1a0b9", backup.caldavCalendars.single().account)
        assertEquals("3e51b0c4-7a2d-4f19-8c3e-1d2f3a4b5c6d", backup.taskListMetadata.single().tagUuid)
        assertEquals(mapOf("p_theme" to 2, "p_default_importance_key" to 2), backup.intPrefs)
        assertEquals(mapOf("p_last_sync" to 1637150000000), backup.longPrefs)
        assertEquals("4", backup.stringPrefs["notif_default_reminder"])
        assertEquals(mapOf("p_show_completed_tasks" to true), backup.boolPrefs)
        assertEquals(mapOf("p_default_reminders_key" to setOf("2", "4")), backup.setPrefs)
    }

    @Test
    fun readsCurrentFormat() {
        val backup = readBackup(BACKUP_V151306)

        assertEquals(151306, backup.version)
        val (caldav, local) = backup.tasks
        assertEquals("Café ✓ 🙂", caldav.task.title)
        assertEquals(3L, caldav.dirtyVersion)
        assertEquals(2L, caldav.syncedVersion)
        assertEquals(-900000L,caldav.alarms!!.single().time)
        assertTrue(caldav.vtodo!!.startsWith("BEGIN:VCALENDAR\r\nBEGIN:VTODO\r\n"))
        assertEquals("c0ffee00-1111-2222-3333-444455556666", caldav.caldavTasks!!.single().calendar)
        assertEquals(1792995000000, local.task.completionDate)
        assertEquals("att-1", local.attachments!!.single().attachmentUid)
        assertEquals("Scanned", local.comments!!.single().message)
        assertEquals("work_outline", backup.tagData.single().icon)
        assertEquals(CaldavAccount.TYPE_TASKS, backup.caldavAccounts.single().accountType)
        assertEquals("receipt.pdf", backup.taskAttachments.single().name)
        assertEquals(mapOf("p_theme_color" to "7"), backup.stringPrefs)
    }

    private class Backup {
        var version = 0
        val tasks = mutableListOf<TaskBackup>()
        val rawTasks = mutableListOf<String>()
        val places = mutableListOf<Place>()
        val tagData = mutableListOf<TagData>()
        val filters = mutableListOf<Filter>()
        val caldavAccounts = mutableListOf<CaldavAccount>()
        val caldavCalendars = mutableListOf<CaldavCalendar>()
        val taskListMetadata = mutableListOf<TaskListMetadata>()
        val taskAttachments = mutableListOf<TaskAttachment>()
        val googleTaskAccounts = mutableListOf<GoogleTaskAccount>()
        val googleTaskLists = mutableListOf<GoogleTaskList>()
        var intPrefs = emptyMap<String, Int>()
        var longPrefs = emptyMap<String, Long>()
        var stringPrefs = emptyMap<String, String>()
        var boolPrefs = emptyMap<String, Boolean>()
        var setPrefs = emptyMap<String, Set<String>>()
    }

    // Walks the file the same way TasksJsonImporter does, decoding every section it imports.
    private fun readBackup(json: String) = Backup().apply {
        reader(json).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "version" -> version = reader.nextInt()
                    "data" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (val name = reader.nextName()) {
                                "tasks" -> {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        val raw = reader.jsonString()
                                        rawTasks.add(raw)
                                        tasks.add(BackupJsonReader.lenientJson.decodeFromString(raw))
                                    }
                                    reader.endArray()
                                }
                                "places" -> reader.forEach<Place> { places.add(it) }
                                "tags" -> reader.forEach<TagData> { tagData.add(it) }
                                "filters" -> reader.forEach<Filter> { filters.add(it) }
                                "caldavAccounts" -> reader.forEach<CaldavAccount> { caldavAccounts.add(it) }
                                "caldavCalendars" -> reader.forEach<CaldavCalendar> { caldavCalendars.add(it) }
                                "taskListMetadata" -> reader.forEach<TaskListMetadata> { taskListMetadata.add(it) }
                                "taskAttachments" -> reader.forEach<TaskAttachment> { taskAttachments.add(it) }
                                "googleTaskAccounts" -> reader.forEach<GoogleTaskAccount> { googleTaskAccounts.add(it) }
                                "googleTaskLists" -> reader.forEach<GoogleTaskList> { googleTaskLists.add(it) }
                                "intPrefs" -> intPrefs = decode(reader.jsonString())
                                "longPrefs" -> longPrefs = decode(reader.jsonString())
                                "stringPrefs" -> stringPrefs = decode(reader.jsonString())
                                "boolPrefs" -> boolPrefs = decode(reader.jsonString())
                                "setPrefs" -> setPrefs = decode(reader.jsonString())
                                else -> error("Unexpected section $name")
                            }
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
    }

    private inline fun <reified T> decode(json: String): T = BackupJsonReader.lenientJson.decodeFromString(json)
}
