package com.cruxcoach.android.ui.board

import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.data.BoardFilterSnapshot
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.fakes.TestClimb
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The Board Playlist's dice reads the browser's persisted filters, and those
 * were last edited on whatever board the user was browsing then. On a board
 * that has no routes, no benchmarks and no BoardSesh provenance, carrying them
 * over unchecked leaves the dice returning nothing with nothing on screen to
 * explain it — and the filter sheet hides exactly those controls there, so the
 * user cannot clear what is blocking them either.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RandomBoardClimbPickerTest {

    private fun snapshot(
        boardBrand: String,
        climbType: String = "BOULDER",
        benchmarkOnly: Boolean = false,
        originFilter: String = "ALL",
        quantumRuleMask: Long = 0L,
    ) = BoardFilterSnapshot(
        angle = 40,
        layoutId = 9101,
        minGrade = 0,
        maxGrade = 16,
        minAscensionists = 0,
        gradeScale = GradeScale.FRENCH,
        sortField = "ASCENSIONISTS",
        sortDirection = "DESC",
        statusFilter = "ALL",
        climbType = climbType,
        benchmarkOnly = benchmarkOnly,
        originFilter = originFilter,
        myClimbsOnly = false,
        quantumRuleMask = quantumRuleMask,
        boardBrand = boardBrand,
    )

    private class Capture {
        val boardBrand = slot<String>()
        val climbType = slot<ClimbTypeFilter>()
        val productSizeId = slot<Int>()
        val excludedMask = slot<Long>()
    }

    private fun picker(
        filter: BoardFilterSnapshot,
        capture: Capture,
        results: List<com.cruxcoach.data.repository.ClimbWithStats>,
    ): RandomBoardClimbPicker {
        val repository = mockk<BoardRepository>(relaxed = true)
        every {
            repository.searchClimbsSorted(
                angle = any(),
                layoutId = any(),
                boardBrand = capture(capture.boardBrand),
                minDifficulty = any(),
                maxDifficulty = any(),
                minAscensionists = any(),
                sortField = any<ClimbSortField>(),
                sortDirection = any<SortDirection>(),
                limit = any(),
                offset = any(),
                climbType = capture(capture.climbType),
                selProductSizeId = capture(capture.productSizeId),
                hsmExcludedMask = capture(capture.excludedMask),
                showUngraded = any(),
            )
        } returns results
        val personal = mockk<PersonalBoardRepository>(relaxed = true) {
            every { getIgnoredClimbUuids() } returns emptySet()
            every { getUserSentClimbUuids() } returns emptySet()
            every { getUserAttemptedClimbUuids() } returns emptySet()
        }
        val preferences = mockk<UserPreferences>(relaxed = true) {
            coEvery { getBoardFilterSnapshot() } returns filter
            every { boardProductSizeId } returns flowOf(9201)
        }
        // No cell, so the picker keeps the persisted board family.
        val cells = mockk<BoardCellManager>(relaxed = true) {
            every { snapshot() } returns null
        }
        return RandomBoardClimbPicker(repository, personal, preferences, cells)
    }

    private fun quantumClimb() = TestClimb.stats(
        uuid = "quantum-1",
        name = "Quantum climb",
        setterUsername = "walltopia",
        difficulty = 12.0,
        quality = 3.0,
        ascensionists = 4,
        frames = "",
    )

    @Test
    fun `a route filter left over from another board cannot empty a Quantum dice roll`() = runTest {
        val capture = Capture()
        val pick = picker(
            filter = snapshot(BoardBrand.QUANTUM.wireValue, climbType = "ROUTE"),
            capture = capture,
            results = listOf(quantumClimb()),
        ).pick()

        assertNotNull(pick)
        // Quantum's vendor catalogue is single-frame: "route" is not a thing
        // it can return, so asking for one would always come back empty.
        assertEquals(ClimbTypeFilter.BOULDER, capture.climbType.captured)
    }

    @Test
    fun `Quantum ignores the product-size edge predicate the same way the browser does`() = runTest {
        val capture = Capture()
        picker(
            filter = snapshot(BoardBrand.QUANTUM.wireValue),
            capture = capture,
            results = listOf(quantumClimb()),
        ).pick()

        assertEquals(0, capture.productSizeId.captured)
    }

    @Test
    fun `Quantum rule filters reach the dice instead of being silently dropped`() = runTest {
        val capture = Capture()
        picker(
            filter = snapshot(BoardBrand.QUANTUM.wireValue, quantumRuleMask = 0b1010L),
            capture = capture,
            results = listOf(quantumClimb()),
        ).pick()

        assertEquals(0b1010L, capture.excludedMask.captured)
    }

    @Test
    fun `a benchmark-only filter is dropped on a board that has no benchmarks`() = runTest {
        val capture = Capture()
        // The catalogue row carries benchmarkDifficulty = 0.0, so a surviving
        // benchmark filter would reject it after the query and return null.
        val pick = picker(
            filter = snapshot(BoardBrand.QUANTUM.wireValue, benchmarkOnly = true),
            capture = capture,
            results = listOf(quantumClimb()),
        ).pick()

        assertNotNull(pick)
    }

    @Test
    fun `Kilter keeps every filter it persisted`() = runTest {
        val capture = Capture()
        val pick = picker(
            filter = snapshot(BoardBrand.KILTER.wireValue, climbType = "ROUTE"),
            capture = capture,
            results = listOf(quantumClimb()),
        ).pick()

        assertNotNull(pick)
        assertEquals(ClimbTypeFilter.ROUTE, capture.climbType.captured)
        assertEquals(9201, capture.productSizeId.captured)
        assertEquals(0L, capture.excludedMask.captured)
    }
}
