package org.tasks.filters

import co.touchlab.kermit.Logger
import com.todoroo.astrid.api.PermaSql
import org.tasks.data.dao.TaskDao.TaskCriteria.activeAndVisible
import org.tasks.data.entity.Alarm
import org.tasks.data.entity.CaldavTask
import org.tasks.data.entity.Tag
import org.tasks.data.entity.Task
import org.tasks.data.sql.Criterion.Companion.and
import org.tasks.data.sql.Criterion.Companion.exists
import org.tasks.data.sql.Criterion.Companion.or
import org.tasks.data.sql.Field.Companion.field
import org.tasks.data.sql.Join.Companion.inner
import org.tasks.data.sql.Query.Companion.select
import org.tasks.data.sql.UnaryCriterion
import org.tasks.data.sql.UnaryCriterion.Companion.isNotNull
import org.tasks.data.entity.Filter as FilterEntity

/**
 * Rebuilds custom filters from backups made before 13.2, like Android's
 * `FilterCriteriaProvider.rebuildFilter`. The criterion column holds one row per condition:
 * `identifier|value|text|type|sql`, with `|` inside a field escaped as [SEPARATOR_ESCAPE].
 */
object LegacyFilterCriteria {
    private const val TYPE_ADD = 0
    private const val TYPE_SUBTRACT = 1
    private const val TYPE_INTERSECT = 2
    private const val TYPE_UNIVERSE = 3

    private const val IDENTIFIER_GTASKS = "gtaskslist"
    private const val IDENTIFIER_CALDAV = "caldavlist"

    /** SQL templates by criterion identifier, as built by Android's `FilterCriteriaProvider`. */
    private val templates: Map<String, String?> =
        mapOf(
            "active" to null,
            "title" to select(Task.ID)
                .from(Task.TABLE)
                .where(and(activeAndVisible(), Task.TITLE.like("%?%")))
                .toString(),
            "importance" to select(Task.ID)
                .from(Task.TABLE)
                .where(and(activeAndVisible(), Task.IMPORTANCE.lte("?")))
                .toString(),
            "startDate" to select(Task.ID)
                .from(Task.TABLE)
                .where(
                    and(
                        activeAndVisible(),
                        or(field("?").eq(0), Task.HIDE_UNTIL.gt(0)),
                        Task.HIDE_UNTIL.lte("?")
                    )
                )
                .toString(),
            "dueDate" to select(Task.ID)
                .from(Task.TABLE)
                .where(
                    and(
                        activeAndVisible(),
                        or(field("?").eq(0), Task.DUE_DATE.gt(0)),
                        // due before the date, or all-day tasks due before EOD today if the date is NOW
                        or(
                            Task.DUE_DATE.lte("?"),
                            and(
                                field("${Task.DUE_DATE} / 1000 % 60").eq(0),
                                field("?").eq(field(PermaSql.VALUE_NOW)),
                                Task.DUE_DATE.lte(PermaSql.VALUE_EOD)
                            )
                        )
                    )
                )
                .toString(),
            IDENTIFIER_CALDAV to select(CaldavTask.TASK)
                .from(CaldavTask.TABLE)
                .join(inner(Task.TABLE, CaldavTask.TASK.eq(Task.ID)))
                .where(
                    and(
                        activeAndVisible(),
                        CaldavTask.DELETED.eq(0),
                        CaldavTask.CALENDAR.eq("?")
                    )
                )
                .toString(),
            "tag_is" to select(Tag.TASK)
                .from(Tag.TABLE)
                .join(inner(Task.TABLE, Tag.TASK.eq(Task.ID)))
                .where(and(activeAndVisible(), Tag.NAME.eq("?")))
                .toString(),
            "tag_contains" to select(Tag.TASK)
                .from(Tag.TABLE)
                .join(inner(Task.TABLE, Tag.TASK.eq(Task.ID)))
                .where(and(activeAndVisible(), Tag.NAME.like("%?%")))
                .toString(),
            "recur" to select(Task.ID)
                .from(Task.TABLE)
                .where(field("LENGTH(${Task.RECURRENCE})>0").eq(1))
                .toString(),
            "completed" to select(Task.ID)
                .from(Task.TABLE)
                .where(field("${Task.COMPLETION_DATE.lt(1)}").eq(0))
                .toString(),
            "hidden" to select(Task.ID)
                .from(Task.TABLE)
                .where(field("${Task.HIDE_UNTIL.gt(PermaSql.VALUE_NOW)}").eq(1))
                .toString(),
            "parent" to select(Task.ID)
                .from(Task.TABLE)
                .join(inner(Task.TABLE.`as`("children"), Task.ID.eq(field("children.parent"))))
                .where(isNotNull(field("children._id")))
                .toString(),
            "subtask" to select(Task.ID)
                .from(Task.TABLE)
                .where(field("${Task.PARENT}>0").eq(1))
                .toString(),
            "reminders" to select(Task.ID)
                .from(Task.TABLE)
                .where(exists(select(field("1")).from(Alarm.TABLE).where(Alarm.TASK.eq(Task.ID))))
                .toString(),
        )

    private class Row(val identifier: String, val value: String, val text: String, val type: Int) {
        val template: String? get() = templates[identifier]

        fun serialize() = listOf(
            escape(identifier),
            escape(if (type == TYPE_UNIVERSE) "" else value),
            escape(text),
            type,
            template ?: "",
        ).joinToString(SERIALIZATION_SEPARATOR)
    }

    /**
     * Returns [filter] with its SQL rebuilt from its criterion and Google Tasks list conditions
     * turned into CalDAV list conditions. A blank or malformed criterion leaves it unchanged.
     */
    fun rebuildFilter(filter: FilterEntity): FilterEntity {
        val rows = parse(filter.criterion) ?: return filter
        return filter.copy(
            sql = rows.sql(),
            criterion = rows.joinToString("\n") { it.serialize() }.trim(),
        )
    }

    private fun parse(criterion: String?): List<Row>? {
        if (criterion.isNullOrBlank()) {
            return null
        }
        return criterion.trim().split("\n").map { line ->
            val fields = line.split(SERIALIZATION_SEPARATOR).map { unescape(it) }
            if (fields.size != 4 && fields.size != 5) {
                Logger.e { "invalid row: $line" }
                return null
            }
            val identifier = fields[0].takeIf { it != IDENTIFIER_GTASKS } ?: IDENTIFIER_CALDAV
            if (identifier !in templates) {
                throw RuntimeException("Unknown identifier: $identifier")
            }
            // Keeps the backup's value and text. Android looks the value up in the current tags and
            // lists (not imported yet → its label ends up in the SQL); nothing reads the text back.
            Row(identifier, fields[1], fields[2], fields[3].toInt())
        }
    }

    private fun List<Row>.sql(): String {
        val sql = StringBuilder(" WHERE ")
        for (row in this) {
            when (row.type) {
                TYPE_ADD -> sql.append(" OR ")
                TYPE_SUBTRACT -> sql.append(" AND NOT ")
                TYPE_INTERSECT -> sql.append(" AND ")
            }
            val template = row.template
            if (row.type == TYPE_UNIVERSE || template == null) {
                sql.append(activeAndVisible())
            } else {
                val subSql = template.replace("?", UnaryCriterion.sanitize(row.value)).trim()
                sql.append(Task.ID).append(" IN (").append(subSql).append(")")
            }
        }
        return sql.toString()
    }

    private fun escape(item: String) = item.replace(SERIALIZATION_SEPARATOR, SEPARATOR_ESCAPE)

    private fun unescape(item: String) = item.replace(SEPARATOR_ESCAPE, SERIALIZATION_SEPARATOR)
}
