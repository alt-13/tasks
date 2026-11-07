package org.tasks.data.entity

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LegacyBackupNamesTest {
    // same settings as the backup importer
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    @Test
    fun readsTaskFieldsFromBackupsBeforeMay2020() {
        // Gson output of the Java Task entity
        val task = json.decodeFromString<Task>(
            """
            {"title":"Old task","priority":1,"dueDate":1588334400000,"hideUntil":0,
             "created":1588000000000,"modified":1588100000000,"completed":1588200000000,
             "deleted":1588300000000,"notes":"","estimatedSeconds":0,"elapsedSeconds":0,
             "timerStart":0,"notificationFlags":6,"notifications":0,"lastNotified":1588150000000,
             "snoozeTime":0,"recurrence":"","repeatUntil":0,"calendarUri":"content://event/1",
             "remoteId":"abc","collapsed":true,"parentUuid":"0"}
            """
        )

        assertEquals(1588000000000, task.creationDate)
        assertEquals(1588100000000, task.modificationDate)
        assertEquals(1588200000000, task.completionDate)
        assertEquals(1588300000000, task.deletionDate)
        assertEquals(6, task.ringFlags)
        assertEquals(1588150000000, task.reminderLast)
        assertEquals("content://event/1", task.calendarURI)
        assertTrue(task.isCollapsed)
        assertTrue(task.isCompleted)
        assertTrue(task.isDeleted)
    }

    @Test
    fun readsGeofenceFieldsFromBackupsBeforeMay2020() {
        val geofence = json.decodeFromString<Geofence>(
            """{"place":"p1","radius":250,"arrival":true,"departure":true}"""
        )

        assertEquals(Geofence(place = "p1", isArrival = true, isDeparture = true), geofence)
    }

    @Test
    fun currentNamesStillRoundTrip() {
        val task = Task(
            creationDate = 1,
            modificationDate = 2,
            completionDate = 3,
            deletionDate = 4,
            ringFlags = 5,
            reminderLast = 6,
            calendarURI = "uri",
            remoteId = "abc",
            isCollapsed = true,
        )
        val geofence = Geofence(place = "p1", isArrival = true, isDeparture = true)

        assertEquals(task, json.decodeFromString<Task>(json.encodeToString(task)))
        assertEquals(geofence, json.decodeFromString<Geofence>(json.encodeToString(geofence)))
    }
}
