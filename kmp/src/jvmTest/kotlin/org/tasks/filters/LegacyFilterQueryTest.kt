package org.tasks.filters

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.tasks.TaskListTest
import org.tasks.data.entity.Task
import org.tasks.data.entity.Filter as FilterEntity

class LegacyFilterQueryTest : TaskListTest() {
    private fun rebuilt(criterion: String) =
        CustomFilter(LegacyFilterCriteria.rebuildFilter(FilterEntity(title = "Filter", criterion = criterion)))

    @Test
    fun googleTasksListFilterFindsTasksInThatList() = runBlocking {
        newTask("in list")
        taskDao.createNew(Task(title = "elsewhere"))

        assertEquals(
            listOf("in list"),
            titles(filter = rebuilt("active||My Tasks|3|\ngtaskslist|$CALENDAR|In List: ?|2|")),
        )
    }

    @Test
    fun filterWithEveryCriterionRuns() = runBlocking {
        newTask("task")

        assertEquals(emptyList<String>(), titles(filter = rebuilt(LEGACY_FILTER_WITH_EVERY_CRITERION)))
    }
}
