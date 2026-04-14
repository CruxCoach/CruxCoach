package com.cruxcoach.data.repository

import com.cruxcoach.domain.model.BodyStat

interface BodyStatRepository {
    fun insert(stat: BodyStat): Long
    fun upsert(stat: BodyStat)
    fun getByDate(date: String): List<BodyStat>
    fun getByStatName(statName: String): List<BodyStat>
    fun getByStatNameForDateRange(statName: String, startDate: String, endDate: String): List<BodyStat>
    fun getLatestByStatName(statName: String): BodyStat?
    fun getAll(): List<BodyStat>
    fun getAllDates(): List<String>
    fun deleteById(id: Long)
    fun deleteByDateAndStatName(date: String, statName: String)
}
