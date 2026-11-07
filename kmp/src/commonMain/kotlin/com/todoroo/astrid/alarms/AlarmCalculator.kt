package com.todoroo.astrid.alarms

import org.tasks.data.entity.Alarm
import org.tasks.data.entity.Notification
import org.tasks.data.entity.Task
import org.tasks.reminders.Random
import org.tasks.time.withMillisOfDay

class AlarmCalculator(
    private val random: Random,
) {
    fun toAlarmEntry(task: Task, alarm: Alarm, defaultDueTime: Int): Notification? {
        val trigger = when (alarm.type) {
            Alarm.TYPE_SNOOZE,
            Alarm.TYPE_DATE_TIME ->
                alarm.time
            Alarm.TYPE_REL_START ->
                when {
                    task.hasStartTime() ->
                        task.hideUntil + alarm.time
                    task.hasStartDate() ->
                        task.hideUntil.withMillisOfDay(defaultDueTime) + alarm.time
                    else ->
                        AlarmService.NO_ALARM
                }
            Alarm.TYPE_REL_END ->
                when {
                    task.hasDueTime() ->
                        task.dueDate + alarm.time
                    task.hasDueDate() ->
                        task.dueDate.withMillisOfDay(defaultDueTime) + alarm.time
                    else ->
                        AlarmService.NO_ALARM
                }
            Alarm.TYPE_RANDOM ->
                calculateNextRandomReminder(random, task, alarm.time)
            else ->
                AlarmService.NO_ALARM
        }
        return when {
            trigger <= AlarmService.NO_ALARM ->
                null
            trigger > task.reminderLast || alarm.type == Alarm.TYPE_SNOOZE ->
                Notification(taskId = alarm.task, timestamp = trigger, type = alarm.type)
            alarm.repeat > 0 -> {
                val past = (task.reminderLast - trigger) / alarm.interval
                val next = trigger + (past + 1) * alarm.interval
                if (past < alarm.repeat && next > task.reminderLast) {
                    Notification(taskId = alarm.task, timestamp = next, type = alarm.type)
                } else {
                    null
                }
            }
            else ->
                null
        }
    }

    /**
     * Every reminder [alarms] will raise for [task] after [now], at most [limit] per alarm.
     * A pending snooze replaces the others, as in [AlarmService.alarmsDueBefore].
     */
    fun upcoming(
        task: Task,
        alarms: List<Alarm>,
        defaultDueTime: Int,
        now: Long,
        limit: Int,
    ): List<Notification> {
        // with reminderLast at now, a repeating alarm starts from its next repeat
        val fromNow = task.copy(reminderLast = maxOf(task.reminderLast, now))
        val entries = alarms
            .flatMap { alarm ->
                when (alarm.type) {
                    // a random reminder counts from the last one, so it needs the real reminderLast
                    Alarm.TYPE_RANDOM, Alarm.TYPE_SNOOZE ->
                        listOfNotNull(toAlarmEntry(task, alarm, defaultDueTime))
                    else ->
                        generateSequence(toAlarmEntry(fromNow, alarm, defaultDueTime)) {
                            toAlarmEntry(task.copy(reminderLast = it.timestamp), alarm, defaultDueTime)
                        }
                            .take(limit)
                            .toList()
                }
            }
            .filter { it.timestamp > now }
        return entries
            .filter { it.type == Alarm.TYPE_SNOOZE }
            .maxByOrNull { it.timestamp }
            ?.let { listOf(it) }
            ?: entries
    }

    private fun calculateNextRandomReminder(random: Random, task: Task, reminderPeriod: Long) =
        if (reminderPeriod > 0) {
            val baseline = when {
                task.reminderLast > 0 -> task.reminderLast
                task.isRecurring -> task.modificationDate
                else -> task.creationDate
            }

            val multiplier = 0.85f + 0.3f * random.nextFloat(task.id + baseline)

            maxOf(
                baseline.plus((reminderPeriod * multiplier).toLong()),
                task.hideUntil
            )
        } else {
            AlarmService.NO_ALARM
        }
}
