package org.tasks.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSyncTest {
    private val errors = mutableListOf<Throwable>()
    private val results = mutableListOf<Boolean>()

    @Test
    fun completesWithTrueWhenSyncFinishes() = runTest {
        launchBackgroundSync(errors::add, results::add) {}.join()

        assertEquals(listOf(true), results)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun reportsErrorAndCompletesWithFalse() = runTest {
        val error = IllegalStateException("boom")
        launchBackgroundSync(errors::add, results::add) { throw error }.join()

        assertEquals(listOf(false), results)
        assertEquals(listOf<Throwable>(error), errors)
    }

    @Test
    fun cancelStopsSyncAndCompletesWithFalse() = runTest {
        val started = CompletableDeferred<Unit>()
        var stopped = false
        val job = launchBackgroundSync(errors::add, results::add) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        started.await()
        assertTrue(results.isEmpty())

        job.cancel()
        job.join()

        assertTrue(stopped)
        assertEquals(listOf(false), results)
        assertTrue(errors.isEmpty())
    }
}
