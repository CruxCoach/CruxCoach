package com.cruxcoach.data.repository

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.model.BodyStat

class BodyStatRepositoryImpl(
    private val database: SecureDatabase
) : BodyStatRepository {

    private val queries = database.bodyStatsQueries

    override fun insert(stat: BodyStat): Long {
        queries.insert(
            date = stat.date,
            stat_name = stat.statName,
            value_ = stat.value,
            unit = stat.unit
        )
        return queries.getByDateAndStatName(stat.date, stat.statName)
            .executeAsOneOrNull()?.id ?: -1
    }

    override fun upsert(stat: BodyStat) {
        queries.upsert(
            date = stat.date,
            stat_name = stat.statName,
            date_ = stat.date,
            stat_name_ = stat.statName,
            value_ = stat.value,
            unit = stat.unit
        )
    }

    override fun getByDate(date: String): List<BodyStat> {
        return queries.getByDate(date).executeAsList().map { it.toDomain() }
    }

    override fun getByStatName(statName: String): List<BodyStat> {
        return queries.getByStatName(statName).executeAsList().map { it.toDomain() }
    }

    override fun getByStatNameForDateRange(
        statName: String,
        startDate: String,
        endDate: String
    ): List<BodyStat> {
        return queries.getByStatNameForDateRange(statName, startDate, endDate)
            .executeAsList().map { it.toDomain() }
    }

    override fun getLatestByStatName(statName: String): BodyStat? {
        return queries.getLatestByStatName(statName).executeAsOneOrNull()?.toDomain()
    }

    override fun getAll(): List<BodyStat> {
        return queries.getAll().executeAsList().map { it.toDomain() }
    }

    override fun getAllDates(): List<String> {
        return queries.getAllDates().executeAsList()
    }

    override fun deleteById(id: Long) {
        queries.deleteById(id)
    }

    override fun deleteByDateAndStatName(date: String, statName: String) {
        queries.deleteByDateAndStatName(date, statName)
    }

    private fun com.cruxcoach.db.secure.Body_stats.toDomain(): BodyStat {
        return BodyStat(
            id = id,
            date = date,
            statName = stat_name,
            value = value_,
            unit = unit
        )
    }
}
