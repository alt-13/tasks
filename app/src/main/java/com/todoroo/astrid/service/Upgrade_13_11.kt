@file:Suppress("ClassName")

package com.todoroo.astrid.service

import org.tasks.data.dao.CaldavDao
import org.tasks.data.dao.FilterDao
import org.tasks.data.dao.LocationDao
import org.tasks.data.dao.TagDataDao
import org.tasks.themes.migrateLegacyIcon
import javax.inject.Inject

class Upgrade_13_11 @Inject constructor(
    private val locationDao: LocationDao,
    private val filterDao: FilterDao,
    private val tagDataDao: TagDataDao,
    private val caldavDao: CaldavDao,
) {
    internal suspend fun migrateIcons() {
        locationDao.getPlaces().forEach {
            locationDao.update(it.copy(icon = it.icon.migrateLegacyIcon()))
        }
        filterDao.getFilters().forEach {
            filterDao.update(it.copy(icon = it.icon.migrateLegacyIcon()))
        }
        tagDataDao.getAll().forEach {
            tagDataDao.update(it.copy(icon = it.icon.migrateLegacyIcon()))
        }
        caldavDao.getCalendars().forEach {
            caldavDao.update(it.copy(icon = it.icon.migrateLegacyIcon()))
        }
    }

    companion object {
        const val VERSION = 131100
    }
}