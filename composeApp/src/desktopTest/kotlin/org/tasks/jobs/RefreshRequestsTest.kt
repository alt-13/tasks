package org.tasks.jobs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.tasks.jobs.RefreshRequests.Companion.BACKGROUND_SYNC_INTERVAL

class RefreshRequestsTest {
    private val now = 1_000_000L

    @Test
    fun noRequestWithoutBackgroundSync() {
        assertNull(RefreshRequests().refresh(now + 5_000).backgroundSync(false, now).next)
    }

    @Test
    fun syncRunsAfterInterval() {
        assertEquals(now + BACKGROUND_SYNC_INTERVAL, RefreshRequests().backgroundSync(true, now).next)
    }

    @Test
    fun updatingKeepsPendingSyncTime() {
        val requests = RefreshRequests().backgroundSync(true, now).backgroundSync(true, now + 10_000)

        assertEquals(now + BACKGROUND_SYNC_INTERVAL, requests.next)
    }

    @Test
    fun disablingRemovesRequest() {
        assertNull(RefreshRequests().backgroundSync(true, now).backgroundSync(false, now).next)
    }

    @Test
    fun earlierRefreshMovesRequestForward() {
        val requests = RefreshRequests().backgroundSync(true, now).refresh(now + 5_000)

        assertEquals(now + 5_000, requests.next)
    }

    @Test
    fun laterRefreshKeepsSyncTime() {
        val requests = RefreshRequests()
            .backgroundSync(true, now)
            .refresh(now + 2 * BACKGROUND_SYNC_INTERVAL)

        assertEquals(now + BACKGROUND_SYNC_INTERVAL, requests.next)
    }

    @Test
    fun keepsEarliestRefresh() {
        val requests = RefreshRequests().backgroundSync(true, now).refresh(now + 5_000).refresh(now + 9_000)

        assertEquals(now + 5_000, requests.next)
    }

    @Test
    fun runStartRequestsNextSyncAndDropsPastRefresh() {
        val later = now + 2 * BACKGROUND_SYNC_INTERVAL
        val requests = RefreshRequests()
            .backgroundSync(true, now)
            .refresh(now + 5_000)
            .started(later)
            .backgroundSync(true, later)

        assertEquals(RefreshRequests(syncAt = later + BACKGROUND_SYNC_INTERVAL), requests)
    }

    @Test
    fun runStartKeepsFutureRefresh() {
        val requests = RefreshRequests()
            .backgroundSync(true, now)
            .refresh(now + 10_000)
            .started(now + 5_000)
            .backgroundSync(true, now + 5_000)

        assertEquals(now + 10_000, requests.next)
    }
}
