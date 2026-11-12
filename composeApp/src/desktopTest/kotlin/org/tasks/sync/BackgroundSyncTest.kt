package org.tasks.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun runsAfterSyncBeforeCompletion() = runTest {
        val events = mutableListOf<String>()
        launchBackgroundSync(
            onError = errors::add,
            completion = { events.add("completion $it") },
            afterSync = { events.add("afterSync") },
        ) { events.add("sync") }.join()

        assertEquals(listOf("sync", "afterSync", "completion true"), events)
    }

    @Test
    fun runsAfterSyncWhenSyncFails() = runTest {
        var afterSync = false
        launchBackgroundSync(errors::add, results::add, afterSync = { afterSync = true }) {
            throw IllegalStateException("boom")
        }.join()

        assertTrue(afterSync)
        assertEquals(listOf(false), results)
        assertEquals(1, errors.size)
    }

    @Test
    fun skipsAfterSyncWhenCancelledDuringSync() = runTest {
        val started = CompletableDeferred<Unit>()
        var afterSync = false
        val job = launchBackgroundSync(errors::add, results::add, afterSync = { afterSync = true }) {
            started.complete(Unit)
            awaitCancellation()
        }
        started.await()

        job.cancel()
        job.join()

        assertFalse(afterSync)
        assertEquals(listOf(false), results)
    }

    @Test
    fun finishesAfterSyncWhenCancelledDuringIt() = runTest {
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        var finished = false
        val job = launchBackgroundSync(
            onError = errors::add,
            completion = results::add,
            afterSync = {
                started.complete(Unit)
                proceed.await()
                finished = true
            },
        ) {}
        started.await()

        job.cancel()
        assertTrue(results.isEmpty())
        proceed.complete(Unit)
        job.join()

        assertTrue(finished)
        assertEquals(listOf(true), results)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun reportsAfterSyncError() = runTest {
        val error = IllegalStateException("boom")
        launchBackgroundSync(errors::add, results::add, afterSync = { throw error }) {}.join()

        assertEquals(listOf<Throwable>(error), errors)
        assertEquals(listOf(true), results)
    }
}
