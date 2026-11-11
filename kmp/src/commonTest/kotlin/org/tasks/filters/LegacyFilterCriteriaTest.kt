package org.tasks.filters

import org.tasks.data.entity.Filter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class LegacyFilterCriteriaTest {
    private fun legacyFilter(criterion: String?) =
        Filter(title = "Filter", sql = "WHERE tasks.dueDate<NOW()", values = "{}", criterion = criterion)

    private fun rebuild(criterion: String?) = LegacyFilterCriteria.rebuildFilter(legacyFilter(criterion))

    @Test
    fun turnsGoogleTasksListIntoCaldavList() {
        val filter = rebuild(LEGACY_GOOGLE_TASKS_FILTER)

        assertEquals(
            " WHERE $ACTIVE AND tasks._id IN ($CALDAV_SQL WHERE ($ACTIVE AND (caldav_tasks.cd_deleted=0) AND (caldav_tasks.cd_calendar='MTE2MjM0NTY3')))",
            filter.sql,
        )
        assertEquals(
            "active||My Tasks|3|\n" +
                "caldavlist|MTE2MjM0NTY3|In List: ?|2|$CALDAV_SQL WHERE ($ACTIVE AND (caldav_tasks.cd_deleted=0) AND (caldav_tasks.cd_calendar='?'))",
            filter.criterion,
        )
        assertEquals("{}", filter.values)
    }

    @Test
    fun joinsConditionsWithTheirOperators() {
        val filter = rebuild(
            "active||My Tasks|3|\n" +
                "importance|1|Priority at least ?|2|\n" +
                "dueDate|EODT()|Due By: ?|0|\n" +
                "completed|Complete|Complete|1|"
        )

        assertEquals(
            " WHERE $ACTIVE" +
                " AND tasks._id IN ($TASKS_SQL WHERE ($ACTIVE AND (tasks.importance<='1')))" +
                " OR tasks._id IN ($TASKS_SQL WHERE ($ACTIVE AND ((EODT()=0) OR (tasks.dueDate>0))" +
                " AND ((tasks.dueDate<='EODT()') OR ((tasks.dueDate / 1000 % 60=0) AND (EODT()=NOW()) AND (tasks.dueDate<='EOD()')))))" +
                " AND NOT tasks._id IN ($TASKS_SQL WHERE ((tasks.completed<1)=0))",
            filter.sql,
        )
    }

    @Test
    fun unescapesPipesAndQuotesValues() {
        val filter = rebuild(
            "active||My Tasks|3|\n" +
                "title|rent !PIPE! bills|Title contains: ?|2|\n" +
                "tag_is|O'Brien|Tag: ?|2|"
        )

        assertEquals(
            " WHERE $ACTIVE" +
                " AND tasks._id IN ($TASKS_SQL WHERE ($ACTIVE AND (tasks.title LIKE '%rent | bills%')))" +
                " AND tasks._id IN (SELECT tags.task AS task FROM tags INNER JOIN tasks ON ((tags.task=tasks._id))" +
                " WHERE ($ACTIVE AND (tags.name='O''Brien')))",
            filter.sql,
        )
        assertEquals(
            "title|rent !PIPE! bills|Title contains: ?|2|",
            filter.criterion!!.lines()[1].substringBefore("SELECT"),
        )
    }

    @Test
    fun rebuildingTwiceChangesNothing() {
        val once = rebuild(LEGACY_FILTER_WITH_EVERY_CRITERION)

        assertEquals(once, LegacyFilterCriteria.rebuildFilter(once))
    }

    @Test
    fun keepsFilterWithoutCriteria() {
        listOf(null, "", "  \n").forEach {
            val filter = legacyFilter(it)
            assertSame(filter, LegacyFilterCriteria.rebuildFilter(filter))
        }
    }

    @Test
    fun keepsFilterWithMalformedRow() {
        val filter = legacyFilter("active||My Tasks|3|\ndueDate|EOD()")

        assertSame(filter, LegacyFilterCriteria.rebuildFilter(filter))
    }

    @Test
    fun failsOnUnknownCriterion() {
        assertFailsWith<RuntimeException> { rebuild("active||My Tasks|3|\nproducteev|1|x|2|") }
    }
}

private const val ACTIVE =
    "((tasks.completed<=0) AND (tasks.deleted<=0) AND ((tasks.hideUntil<=(strftime('%s','now')*1000)) OR (tasks.completed>0)))"
private const val TASKS_SQL = "SELECT tasks._id AS _id FROM tasks"
private const val CALDAV_SQL =
    "SELECT caldav_tasks.cd_task AS cd_task FROM caldav_tasks INNER JOIN tasks ON ((caldav_tasks.cd_task=tasks._id))"

/** A Google Tasks list filter as saved before 13.2 (rows: identifier|value|text|type|sql). */
internal const val LEGACY_GOOGLE_TASKS_FILTER =
    "active||My Tasks|3|\n" +
        "gtaskslist|MTE2MjM0NTY3|In List: ?|2|SELECT google_tasks.gt_task AS gt_task FROM google_tasks " +
        "INNER JOIN tasks ON ((google_tasks.gt_task=tasks._id)) WHERE ($ACTIVE " +
        "AND (google_tasks.gt_deleted=0) AND (google_tasks.gt_list_id='?')) "

internal const val LEGACY_FILTER_WITH_EVERY_CRITERION =
    "active||My Tasks|3|\n" +
        "title|rent !PIPE! bills|Title contains: ?|2|\n" +
        "importance|1|Priority at least ?|2|\n" +
        "startDate|EOD()|Start by: ?|2|\n" +
        "dueDate|EODT()|Due By: ?|0|\n" +
        "gtaskslist|MTE2MjM0NTY3|In List: ?|0|\n" +
        "caldavlist|4f2b8d1e|In List: ?|2|\n" +
        "tag_is|O'Brien|Tag: ?|2|\n" +
        "tag_contains|work|Tag contains: ?|2|\n" +
        "recur|Repeats|Repeats|1|\n" +
        "completed|Complete|Complete|1|\n" +
        "hidden|Not started|Not started|1|\n" +
        "parent|Has subtasks|Has subtasks|2|\n" +
        "subtask|Is subtask|Is subtask|2|\n" +
        "reminders|Has reminders|Has reminders|2|"
