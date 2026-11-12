package org.tasks

import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DataFilesTest {
    private val fs = FileSystem.SYSTEM
    private val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "DataFilesTest-${Random.nextLong().toULong()}"
    private val documents = root / "documents"
    private val group = root / "group"

    init {
        fs.createDirectories(documents)
        fs.createDirectories(group)
    }

    @AfterTest
    fun cleanup() = fs.deleteRecursively(root)

    @Test
    fun freshInstallMovesNothing() {
        moveDataFiles(documents, group, fs)

        assertEquals(emptyMap(), contents(documents))
        assertEquals(emptyMap(), contents(group))
    }

    @Test
    fun upgradeMovesEverything() {
        val files = mapOf(
            "database" to "db",
            "database-wal" to "wal",
            "database-shm" to "shm",
            "tasks.preferences_pb" to "prefs",
            "entitlement.json" to "pro",
            "vtodo/account/list/task.ics" to "ics",
        )
        files.forEach { (name, content) -> write(documents / name, content) }

        moveDataFiles(documents, group, fs)

        assertEquals(emptyMap(), contents(documents))
        assertEquals(files, contents(group))
    }

    @Test
    fun leavesUnknownFilesInSource() {
        write(documents / "database", "db")
        write(documents / "other", "other")

        moveDataFiles(documents, group, fs)

        assertEquals(mapOf("other" to "other"), contents(documents))
        assertEquals(mapOf("database" to "db"), contents(group))
    }

    @Test
    fun movesNothingWhenDestinationHasDatabase() {
        write(documents / "database", "old db")
        write(documents / "database-wal", "old wal")
        write(documents / "tasks.preferences_pb", "old prefs")
        write(group / "database", "new db")

        moveDataFiles(documents, group, fs)

        assertEquals(
            mapOf("database" to "old db", "database-wal" to "old wal", "tasks.preferences_pb" to "old prefs"),
            contents(documents),
        )
        assertEquals(mapOf("database" to "new db"), contents(group))
    }

    @Test
    fun failedMoveLeavesSourceIntact() {
        val files = mapOf(
            "database" to "db",
            "database-wal" to "wal",
            "database-shm" to "shm",
            "tasks.preferences_pb" to "prefs",
        )
        files.forEach { (name, content) -> write(documents / name, content) }
        val failing = object : ForwardingFileSystem(fs) {
            override fun atomicMove(source: Path, target: Path) {
                if (source.name == "database-shm") throw IOException("disk full")
                super.atomicMove(source, target)
            }
        }

        assertFailsWith<IOException> { moveDataFiles(documents, group, failing) }

        assertEquals(files, contents(documents))
        assertEquals(emptyMap(), contents(group))
    }

    private fun write(path: Path, content: String) {
        path.parent?.let { fs.createDirectories(it) }
        fs.write(path) { writeUtf8(content) }
    }

    private fun contents(dir: Path): Map<String, String> =
        fs.listRecursively(dir)
            .filter { fs.metadata(it).isRegularFile }
            .associate { it.relativeTo(dir).segments.joinToString("/") to fs.read(it) { readUtf8() } }
}
