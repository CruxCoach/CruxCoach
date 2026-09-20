package com.cruxcoach.app.logbook

import com.cruxcoach.app.settings.HistoryRetention
import com.cruxcoach.data.repository.PersonalBoardRepository
import kotlin.test.AfterTest
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

class LogbookPresentersTest {
    private val serialOwner = SerialDispatcher()
    private val serial = serialOwner.dispatcher

    @AfterTest
    fun closeSerialDispatcher() = serialOwner.close()


    private fun PersonalBoardRepository.send(
        uuid: String, climb: String, at: String, tries: Long = 1, angle: Long = 40,
        brand: String = "kilter", diff: Double? = 18.0,
    ) = insertAscent(
        uuid = uuid, climbUuid = climb, angle = angle, isMirror = false, attemptId = 0, bidCount = tries,
        quality = null, difficulty = diff?.toLong(), isBenchmark = false, comment = null, climbedAt = at,
        synced = false, climbName = climb, difficultyAverage = diff, climbFrames = "p1r12", framesCount = 1,
        boardBrand = brand, layoutId = 1,
    )

    private fun PersonalBoardRepository.bid(
        uuid: String, climb: String, at: String, tries: Long = 1, angle: Long = 40, brand: String = "kilter",
    ) = insertBid(
        uuid = uuid, climbUuid = climb, angle = angle, isMirror = false, bidCount = tries, comment = null,
        climbedAt = at, synced = false, climbName = climb, difficultyAverage = 18.0, boardBrand = brand, layoutId = 1,
    )

    private fun seeded() = newPersonalRepo().apply {
        bid("b1", "A", "2026-03-01T10:00:00", tries = 3)
        send("s1", "A", "2026-03-05T10:00:00")            // redpoint: an earlier attempt exists
        send("s2", "B", "2026-03-05T11:00:00")            // flash
        send("s3", "B", "2026-03-06T09:00:00", tries = 2) // repeat of B
        bid("b2", "C", "2026-03-06T10:00:00", tries = 4, angle = 45, brand = "tension")
    }

    private fun logbook(repo: PersonalBoardRepository) = LogbookPresenter(
        repo, MapKeyValueStore(), CoroutineScope(serial), today = { LocalDate(2026, 3, 15) },
    )

    private suspend fun LogbookPresenter.await(predicate: (LogbookState) -> Boolean) =
        withTimeout(CI_WAIT_MS) { state.first(predicate) }

    @Test
    fun `groups by day, newest first, and computes period statistics`() = runBlocking {
        val p = logbook(seeded())
        val s = p.await { it.stats.totalSends == 3 && it.boardComparison.size == 2 }

        assertEquals(listOf("2026-03-06", "2026-03-05", "2026-03-01"), s.days.map { it.date })
        assertEquals(listOf(2, 2, 1), s.days.map { it.entries.size })
        assertEquals(setOf("s2"), s.flashUuids)
        // Attempt volume sums tries over sends AND bids: 3 + 1 + 1 + 2 + 4.
        assertEquals(11, s.stats.totalAttempts)
        // One best outcome per (board, climb): A redpoint, B flash, C attempted.
        assertEquals(OutcomeDistribution(flashes = 1, redpoints = 1, attempts = 1), s.stats.outcomeDistribution)
        assertEquals(3, s.stats.sessionCount)
        assertEquals(listOf("kilter", "tension"), s.availableBoardBrands)
        assertEquals(listOf(40, 45), s.availableAngles)
        assertFalse(s.canLoadMore)
    }

    @Test
    fun `outcome, board and angle filters narrow the list but not the statistics`() = runBlocking {
        val p = logbook(seeded())
        p.await { it.stats.totalSends == 3 }

        p.setOutcomeFilter(LogbookOutcomeFilter.ATTEMPTS)
        assertEquals(listOf("b2", "b1"), p.state.value.ascents.map { it.uuid })
        p.setListBoardFilter("tension")
        assertEquals(listOf("b2"), p.state.value.ascents.map { it.uuid })
        p.setAngleFilter(40)
        assertTrue(p.state.value.ascents.isEmpty())
        assertEquals(0L, p.state.value.totalCount)
        assertEquals(3, p.state.value.stats.totalSends)

        p.setAngleFilter(null)
        p.setListBoardFilter(null)
        p.setOutcomeFilter(LogbookOutcomeFilter.ALL)
        // Android quirk, reproduced: the upper bound is customTo + 1 day compared as a DATE string,
        // so the day after the chosen end is included as well.
        p.setCustomDateRange("2026-03-04", "2026-03-04")
        val s = p.await { it.stats.totalSends == 2 }
        assertEquals(listOf("2026-03-05"), s.days.map { it.date })
        // s1 stays a redpoint: flash eligibility uses the full history, not the period.
        assertEquals(OutcomeDistribution(1, 1, 0), s.stats.outcomeDistribution)

        p.setCustomDateRange("2026-03-09", "2026-03-01")
        assertEquals(LogbookError.INVALID_DATE_RANGE, p.state.value.error)

        p.setStatsInterval(StatsTimeInterval.ALL)
        p.setStatsBoardFilter("tension")
        val scoped = p.await { it.stats.totalSends == 0 && it.stats.totalAttempts == 4 }
        assertEquals(2, scoped.boardComparison.size)
    }

    @Test
    fun `edit and delete route bids and sends to their own tables`() = runBlocking {
        val repo = seeded()
        val p = logbook(repo)
        p.await { it.stats.totalSends == 3 }

        p.edit("b1")
        assertEquals(LogbookEditState("b1", false, 3, 0, ""), p.state.value.editing)
        p.updateEditBidCount(0)
        assertEquals(1, p.state.value.editing!!.bidCount)
        p.updateEditBidCount(5)
        p.updateEditComment("close")
        p.saveEdit()
        val edited = p.await { s -> s.ascents.any { it.uuid == "b1" && it.bidCount == 5L } }
        assertEquals("close", edited.ascents.single { it.uuid == "b1" }.comment)

        p.toggleSelection("b2")
        p.toggleSelection("s3")
        p.requestBatchDelete()
        assertTrue(p.state.value.showBatchDeleteConfirm)
        p.confirmBatchDelete()
        val after = p.await { it.ascents.size == 3 && it.selectedUuids.isEmpty() && it.stats.totalSends == 2 }
        assertEquals(listOf("s2", "s1", "b1"), after.ascents.map { it.uuid })
        assertEquals(listOf("kilter"), after.availableBoardBrands)

        p.requestDelete("b1")
        p.confirmDelete()
        p.await { it.ascents.size == 2 && it.deleteConfirmUuid == null }
        assertEquals(2L, repo.countUserLogbook())
    }

    @Test
    fun `first page is limited to fifty and load more is superseded by the full list`() = runBlocking {
        val repo = newPersonalRepo()
        repeat(120) { i -> repo.send("s$i", "c$i", "2026-02-${(i % 28 + 1).toString().padStart(2, '0')}T10:00:${(i % 60).toString().padStart(2, '0')}") }
        assertEquals(50, repo.getUserLogbookPage(LogbookPresenter.PAGE_SIZE, 0).size)
        val p = logbook(repo)
        val s = p.await { it.stats.totalSends == 120 }
        assertEquals(120, s.ascents.size)
        assertEquals(28, s.days.size)
        assertFalse(s.canLoadMore)
        p.loadMore()
        assertEquals(120, p.state.value.ascents.size)
    }

    @Test
    fun `history prunes to retention and deletes a multi selection`() = runBlocking {
        val repo = newPersonalRepo()
        suspend fun record(climb: String, at: String) = repo.recordClimbHistory(climb, climb, 40, 18.0, "kilter", 1, at, at)
        record("old", "2026-01-01T10:00")
        record("mid", "2026-03-01T10:00")
        record("new", "2026-03-14T10:00")
        record("newest", "2026-03-15T09:00")
        val kv = MapKeyValueStore(mapOf("climb_history_retention_days" to "90", "grade_scale" to "V_SCALE"))
        val p = HistoryPresenter(repo, kv, CoroutineScope(serial)) { LocalDateTime(2026, 3, 15, 12, 0) }
        assertEquals(HistoryRetention.DAYS_90, p.state.value.retention)
        withTimeout(CI_WAIT_MS) { p.state.first { it.entries.size == 4 } }

        p.setRetention(HistoryRetention.DAYS_30)
        assertEquals("30", kv.map["climb_history_retention_days"])
        val pruned = withTimeout(CI_WAIT_MS) { p.state.first { it.entries.size == 3 } }
        assertEquals(listOf("newest", "new", "mid"), pruned.entries.map { it.climbUuid })

        p.toggleSelectAll()
        assertTrue(p.state.value.allSelected)
        p.toggleSelectAll()
        assertFalse(p.state.value.hasSelection)
        p.toggleSelection(pruned.entries[0].id)
        p.toggleSelection(pruned.entries[2].id)
        p.deleteSelected()
        val left = withTimeout(CI_WAIT_MS) { p.state.first { it.entries.size == 1 && it.selectedIds.isEmpty() } }
        assertEquals("new", left.entries.single().climbUuid)

        p.setRetention(HistoryRetention.OFF)
        p.clearHistory()
        withTimeout(CI_WAIT_MS) { p.state.first { it.entries.isEmpty() } }
        assertEquals(0L, repo.climbHistoryCount())
    }
}
