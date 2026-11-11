package org.tasks.notifications

import com.todoroo.astrid.alarms.AlarmCalculator
import org.tasks.data.entity.Alarm
import org.tasks.data.entity.Alarm.Companion.TYPE_DATE_TIME
import org.tasks.data.entity.Alarm.Companion.TYPE_SNOOZE
import org.tasks.data.entity.Notification
import org.tasks.data.entity.Task
import org.tasks.reminders.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationWindowTest {
    private val now = 1_000_000_000L

    @Test
    fun keepsTheNextSixtyInFiringOrder() {
        val upcoming = (1..100L).shuffled().map { entry(taskId = it, at = now + it * MINUTE) }

        val window = NotificationWindow.window(upcoming, now)

        assertEquals((1..60L).toList(), window.map { it.taskId })
    }

    @Test
    fun theWindowRollsOnceTheFirstOnesHaveFired() {
        val upcoming = (1..100L).map { entry(taskId = it, at = now + it * MINUTE) }

        val window = NotificationWindow.window(upcoming, now + 60 * MINUTE)

        assertEquals((61..100L).toList(), window.map { it.taskId })
    }

    @Test
    fun skipsPastRemindersAndDuplicates() {
        val upcoming = listOf(
            entry(taskId = 1, at = now - MINUTE),
            entry(taskId = 2, at = now + MINUTE),
            entry(taskId = 2, at = now + MINUTE),
        )

        assertEquals(listOf(2L), NotificationWindow.window(upcoming, now).map { it.taskId })
    }

    @Test
    fun removesRequestsThatLeftTheWindow() {
        val kept = entry(taskId = 1, at = now + MINUTE)
        val edited = entry(taskId = 2, at = now + 2 * MINUTE)
        val completed = entry(taskId = 3, at = now + 3 * MINUTE)
        val pending = listOf(kept, edited, completed).map { NotificationWindow.identifier(it) } +
                "someone-else"
        val window = listOf(kept, edited.copy(timestamp = now + 5 * MINUTE))

        val stale = NotificationWindow.stale(pending, window)

        assertEquals(listOf(edited, completed).map { NotificationWindow.identifier(it) }, stale)
    }

    @Test
    fun readsTheTaskBackFromTheIdentifier() {
        assertEquals(42L, NotificationWindow.taskId(NotificationWindow.identifier(entry(42, now))))
        assertNull(NotificationWindow.taskId("someone-else"))
    }

    @Test
    fun scheduledRequestsNoLongerPendingHaveFired() {
        val fired = entry(taskId = 1, at = now - MINUTE)
        val waiting = entry(taskId = 2, at = now + MINUTE)
        val scheduled = listOf(fired, waiting).map { NotificationWindow.identifier(it) }
        val pending = listOf(NotificationWindow.identifier(waiting), "someone-else")

        assertEquals(listOf(fired), NotificationWindow.fired(scheduled, pending))
    }

    @Test
    fun nothingHasFiredWithoutAPreviousReconcile() {
        val pending = listOf(NotificationWindow.identifier(entry(taskId = 1, at = now)))

        assertEquals(emptyList(), NotificationWindow.fired(emptySet(), pending))
    }

    @Test
    fun aRepeatingAlarmIsScheduledFromItsNextRepeat() {
        val alarm = Alarm(task = 1, time = now - 30 * MINUTE, type = TYPE_DATE_TIME, repeat = 5, interval = 20 * MINUTE)

        val upcoming = upcoming(listOf(alarm))

        assertEquals(listOf(10L, 30L, 50L, 70L), upcoming.map { (it.timestamp - now) / MINUTE })
    }

    @Test
    fun aRepeatingAlarmStopsAtTheLimit() {
        val alarm = Alarm(task = 1, time = now + MINUTE, type = TYPE_DATE_TIME, repeat = 1_000, interval = MINUTE)

        assertEquals(60, upcoming(listOf(alarm)).size)
    }

    @Test
    fun aPastAlarmIsSkipped() {
        val past = Alarm(task = 1, time = now - MINUTE, type = TYPE_DATE_TIME)
        val future = Alarm(task = 1, time = now + MINUTE, type = TYPE_DATE_TIME)

        assertEquals(listOf(now + MINUTE), upcoming(listOf(past, future)).map { it.timestamp })
    }

    @Test
    fun aPendingSnoozeReplacesTheOtherAlarms() {
        val alarms = listOf(
            Alarm(task = 1, time = now + MINUTE, type = TYPE_DATE_TIME),
            Alarm(task = 1, time = now + 10 * MINUTE, type = TYPE_SNOOZE),
        )

        assertEquals(listOf(now + 10 * MINUTE), upcoming(alarms).map { it.timestamp })
    }

    private fun upcoming(alarms: List<Alarm>) =
        AlarmCalculator(Random()).upcoming(
            task = Task(id = 1),
            alarms = alarms,
            defaultDueTime = 0,
            now = now,
            limit = NotificationWindow.SIZE,
        )

    private fun entry(taskId: Long, at: Long) = Notification(taskId = taskId, timestamp = at)

    companion object {
        private const val MINUTE = 60_000L
    }
}
