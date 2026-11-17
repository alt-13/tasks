@file:Suppress("ClassName")

package com.todoroo.astrid.service

import org.tasks.data.dao.FilterDao
import org.tasks.filters.LegacyFilterCriteria
import javax.inject.Inject

class Upgrade_13_2 @Inject constructor(
    private val filterDao: FilterDao,
) {
    internal suspend fun rebuildFilters() =
        filterDao.getFilters().forEach {
            filterDao.update(LegacyFilterCriteria.rebuildFilter(it))
        }

    companion object {
        const val VERSION = 130200
    }
}