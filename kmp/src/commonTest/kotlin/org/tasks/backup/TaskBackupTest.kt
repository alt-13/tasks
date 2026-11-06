package org.tasks.backup

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskBackupTest {
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    @Test
    fun decodesTaskWithChildren() {
        val backup = json.decodeFromString<TaskBackup>(
            """{"task":{"title":"Milk"},"dirtyVersion":2,"syncedVersion":1,""" +
                """"geofences":[{"place":"p1","isArrival":true}],"tags":[{"name":"home"}],"unknown":1}"""
        )

        assertEquals("Milk", backup.task.title)
        assertEquals(2L, backup.dirtyVersion)
        assertEquals(1L, backup.syncedVersion)
        assertTrue(backup.geofences!!.single().isArrival)
        assertEquals("home", backup.tags!!.single().name)
        assertNull(backup.locations)
    }

    @Suppress("DEPRECATION")
    @Test
    fun decodesLegacyLocations() {
        val location = json.decodeFromString<TaskBackup>(
            """{"task":{},"locations":[{"name":"Shop","latitude":1.5,"longitude":-2.5,"departure":true}]}"""
        ).locations!!.single()

        assertEquals("Shop", location.name)
        assertEquals(1.5, location.latitude)
        assertEquals(-2.5, location.longitude)
        assertTrue(location.departure)
    }
}
