package com.cruxcoach.data

import com.cruxcoach.data.repository.BodyStatRepository
import com.cruxcoach.domain.model.BodyStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaistlineExchangeTest {
    @Test
    fun `fixed two formatter is locale independent and rounds`() {
        assertEquals("0.00", formatFixed2(0.0))
        assertEquals("75.50", formatFixed2(75.5))
        assertEquals("75.56", formatFixed2(75.555))
        assertEquals("-2.25", formatFixed2(-2.25))
    }

    @Test
    fun `CSV export always writes dot decimals`() {
        val repository = FakeBodyStatRepository(
            mutableListOf(BodyStat(date = "2026-07-13", statName = "weight", value = 75.5, unit = "kg"))
        )
        val csv = WaistlineExchange.exportToCsv(repository)
        assertTrue(csv.contains("2026-07-13;75.50"), csv)
    }

    @Test
    fun `legacy comma decimal CSV still imports`() {
        val repository = FakeBodyStatRepository()
        val count = WaistlineExchange.importFromCsv(
            "Date;Weight (kg)\n2026-07-13;75,50\n",
            repository,
        )
        assertEquals(1, count)
        assertEquals(75.5, repository.getAll().single().value)
    }

    private class FakeBodyStatRepository(
        private val rows: MutableList<BodyStat> = mutableListOf(),
    ) : BodyStatRepository {
        override fun insert(stat: BodyStat): Long {
            rows += stat
            return rows.size.toLong()
        }
        override fun upsert(stat: BodyStat) {
            rows.removeAll { it.date == stat.date && it.statName == stat.statName }
            rows += stat
        }
        override fun getByDate(date: String) = rows.filter { it.date == date }
        override fun getByStatName(statName: String) = rows.filter { it.statName == statName }
        override fun getByStatNameForDateRange(statName: String, startDate: String, endDate: String) =
            rows.filter { it.statName == statName && it.date in startDate..endDate }
        override fun getLatestByStatName(statName: String) =
            getByStatName(statName).maxByOrNull { it.date }
        override fun getAll(): List<BodyStat> = rows.toList()
        override fun getAllDates() = rows.map { it.date }.distinct()
        override fun deleteById(id: Long) { rows.removeAll { it.id == id } }
        override fun deleteByDateAndStatName(date: String, statName: String) {
            rows.removeAll { it.date == date && it.statName == statName }
        }
    }
}
