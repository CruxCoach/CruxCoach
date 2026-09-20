package com.cruxcoach.app.logbook

import com.cruxcoach.app.ui.HistoryScreenModel
import com.cruxcoach.app.ui.LogbookScreenModel
import com.cruxcoach.data.repository.PersonalBoardRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/** Pins the Swift-facing mapping: string codes, flattened stats, no nulls. */
class LogbookFacadeTest {

    private fun PersonalBoardRepository.seed() {
        insertBid(
            uuid = "b1", climbUuid = "A", angle = 40, isMirror = false, bidCount = 3, comment = "hard",
            climbedAt = "2026-03-01T10:00:00", synced = false, climbName = "Alpha",
            difficultyAverage = 18.0, boardBrand = "kilter", layoutId = 1,
        )
        insertAscent(
            uuid = "s1", climbUuid = "B", angle = 45, isMirror = true, attemptId = 0, bidCount = 1,
            quality = 4, difficulty = 21, isBenchmark = false, comment = null,
            climbedAt = "2026-03-05T11:00:00", synced = false, climbName = "Bravo",
            difficultyAverage = 21.0, climbFrames = "p1r12", framesCount = 1,
            boardBrand = "tension", layoutId = 2,
        )
    }

    @Test
    fun `logbook state maps to plain codes and values`() = runBlocking {
        val repo = newPersonalRepo().apply { seed() }
        val presenter = LogbookPresenter(
            repo, MapKeyValueStore(mapOf("grade_scale" to "V_SCALE")),
            CoroutineScope(Dispatchers.Default), today = { LocalDate(2026, 3, 15) },
        )
        val model = LogbookScreenModel(presenter)
        withTimeout(5_000) { presenter.state.first { it.stats.totalSends == 1 } }

        val s = model.currentState
        assertEquals(listOf("2026-03-05", "2026-03-01"), s.days.map { it.date })
        val send = s.days[0].entries.single()
        assertEquals("V5", send.grade)
        assertTrue(send.isSend)
        assertTrue(send.isFlash)
        assertTrue(send.isMirror)
        assertEquals(45, send.angle)
        assertEquals(4, send.quality)
        assertEquals("", send.comment)
        assertEquals("tension", send.boardWire)
        val attempt = s.days[1].entries.single()
        assertFalse(attempt.isSend)
        assertEquals(3, attempt.tries)
        assertEquals("hard", attempt.comment)

        assertEquals("all", s.outcomeFilterCode)
        assertEquals("all", s.intervalCode)
        assertEquals("none", s.errorCode)
        assertEquals("", s.listBoardFilter)
        assertEquals(-1, s.angleFilter)
        assertEquals(listOf(40, 45), s.availableAngles)
        assertEquals(listOf("kilter", "tension"), s.availableBoardWires)
        assertEquals(listOf("Kilter Board", "Tension Board"), s.availableBoardTitles)
        // Volume is tries, not rows: 3 + 1.
        assertEquals(4, s.stats.totalAttempts)
        assertEquals(1, s.stats.outcomeFlashes)
        assertEquals(1, s.stats.outcomeAttempts)
        assertEquals("V5", s.stats.hardestGrade)
        assertEquals(listOf("2026-03-01", "2026-03-05"), s.stats.activityDates)
        assertEquals(listOf(1, 1), s.stats.activityCounts)
        assertEquals("month", s.stats.sendsOverTime.single().kind)
        assertEquals(3, s.stats.sendsOverTime.single().month)
        assertFalse(s.stats.periodComparison.hasData)
        assertEquals("V5", s.stats.records.hardestFlashGrade)
        assertEquals(listOf("Tension Board", "Kilter Board"), s.boardComparison.map { it.boardTitle })

        model.setOutcomeFilter("attempts")
        assertEquals(listOf("b1"), model.currentState.days.flatMap { d -> d.entries.map { it.uuid } })
        model.setOutcomeFilter("nonsense")
        assertEquals("attempts", model.currentState.outcomeFilterCode)
        model.setOutcomeFilter("all")
        model.setAngleFilter(45)
        assertEquals(45, model.currentState.angleFilter)
        model.setAngleFilter(-1)
        model.setListBoardFilter("kilter")
        assertEquals("kilter", model.currentState.listBoardFilter)
        model.setListBoardFilter("")
        model.setInterval("days90")
        withTimeout(5_000) { presenter.state.first { it.statsInterval == StatsTimeInterval.DAYS_90 && it.stats.periodComparison != null } }
        assertEquals("days90", model.currentState.intervalCode)
        assertEquals("days90", model.currentState.stats.periodComparison.intervalCode)
        assertEquals("isoWeek", model.currentState.stats.sendsOverTime.single().kind)
        model.setCustomRange("2026-03-09", "2026-03-01")
        assertEquals("invalidDateRange", model.currentState.errorCode)
        model.consumeError()
        assertEquals("none", model.currentState.errorCode)

        model.edit("s1")
        assertTrue(model.currentState.isEditing)
        assertEquals("s1", model.currentState.editUuid)
        model.dismissEdit()
        assertFalse(model.currentState.isEditing)

        var seen = 0
        val sub = model.watch { seen++ }
        withTimeout(5_000) { while (seen == 0) kotlinx.coroutines.delay(5) }
        sub.cancel()
        model.close()
    }

    @Test
    fun `history state maps entries, retention and selection`() = runBlocking {
        val repo = newPersonalRepo()
        repo.recordClimbHistory("A", "Alpha", 40, 18.0, "kilter", 1, "2026-03-05T10:00", "2026-03-05T10:00")
        val presenter = HistoryPresenter(
            repo, MapKeyValueStore(mapOf("grade_scale" to "FRENCH")),
            CoroutineScope(Dispatchers.Default),
        ) { LocalDateTime(2026, 3, 15, 12, 0) }
        val model = HistoryScreenModel(presenter)
        withTimeout(5_000) { presenter.state.first { it.entries.size == 1 } }

        val entry = model.currentState.entries.single()
        assertEquals("Alpha", entry.climbName)
        assertEquals("6b", entry.grade)
        assertEquals(40, entry.angle)
        assertFalse(entry.isSelected)
        assertEquals("days30", model.currentState.retentionCode)
        assertEquals("none", model.currentState.errorCode)

        model.toggleSelection(entry.id)
        assertEquals(1, model.currentState.selectedCount)
        assertTrue(model.currentState.allSelected)
        assertTrue(model.currentState.entries.single().isSelected)
        model.setRetention("days365")
        assertEquals("days365", model.currentState.retentionCode)
        model.setRetention("bogus")
        assertEquals("days365", model.currentState.retentionCode)
        model.close()
    }
}
