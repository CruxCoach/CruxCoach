package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.boardcell.BoardCellId
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.boardcell.BoardCellSnapshot
import com.cruxcoach.android.boardcell.BoardPlaylistEntry
import com.cruxcoach.android.boardcell.BoardPlaylistState
import com.cruxcoach.android.boardcell.BoardProjection
import com.cruxcoach.android.boardcell.PhysicalBoardId
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val layoutId = slot<Int>()
        val angle = slot<Int>()
        val climbType = slot<ClimbTypeFilter>()
        val productSizeId = slot<Int>()
        val excludedMask = slot<Long>()
    }

    private fun picker(
        filter: BoardFilterSnapshot,
        capture: Capture,
        results: List<com.cruxcoach.data.repository.ClimbWithStats>,
        cell: BoardCellSnapshot? = null,
        knownClimbs: Map<String, com.cruxcoach.data.repository.ClimbWithStats> = emptyMap(),
        /** This device is the one on the cell's physical board. */
        connectedToCellBoard: Boolean = false,
    ): RandomBoardClimbPicker {
        val repository = mockk<BoardRepository>(relaxed = true)
        every { repository.getClimbByUuid(any(), any()) } answers { knownClimbs[firstArg()] }
        every { repository.getClimbByUuidNormalized(any(), any()) } returns null
        every {
            repository.searchClimbsSorted(
                angle = capture(capture.angle),
                layoutId = capture(capture.layoutId),
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
        val cells = mockk<BoardCellManager>(relaxed = true)
        every { cells.snapshot() } returns cell
        every { cells.matchesPhysicalBoard(any(), any()) } returns connectedToCellBoard
        val ble = mockk<BoardBleConnection>(relaxed = true)
        every { ble.connectedBoardDescriptor } returns MutableStateFlow(
            if (connectedToCellBoard) {
                DiscoveredBoard(
                    displayName = "board", serial = "SER", apiLevel = 3,
                    address = "AA:BB:CC:DD:EE:FF", rssi = -50,
                )
            } else null
        )
        return RandomBoardClimbPicker(repository, personal, preferences, cells, ble)
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
        val pick: RandomClimbRoll = picker(
            filter = snapshot(BoardBrand.QUANTUM.wireValue, climbType = "ROUTE"),
            capture = capture,
            results = listOf(quantumClimb()),
        ).roll()

        assertTrue(pick is RandomClimbRoll.Picked)
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
        ).roll()

        assertEquals(0, capture.productSizeId.captured)
    }

    @Test
    fun `MoonBoard ignores a stale Aurora product size instead of emptying the dice roll`() = runTest {
        val capture = Capture()
        val pick = picker(
            filter = snapshot(BoardBrand.MOONBOARD.wireValue),
            capture = capture,
            results = listOf(quantumClimb().copy(boardBrand = BoardBrand.MOONBOARD.wireValue)),
        ).roll()

        assertTrue(pick is RandomClimbRoll.Picked)
        // The Nokia carried Kilter product-size id 10 while browsing MoonBoard.
        // MoonBoard has no product_sizes rows, so forwarding any positive id
        // makes SQLite reject every otherwise matching climb.
        assertEquals(0, capture.productSizeId.captured)
    }

    @Test
    fun `Quantum rule filters reach the dice instead of being silently dropped`() = runTest {
        val capture = Capture()
        picker(
            filter = snapshot(BoardBrand.QUANTUM.wireValue, quantumRuleMask = 0b1010L),
            capture = capture,
            results = listOf(quantumClimb()),
        ).roll()

        assertEquals(0b1010L, capture.excludedMask.captured)
    }

    @Test
    fun `a benchmark-only filter is dropped on a board that has no benchmarks`() = runTest {
        val capture = Capture()
        // The catalogue row carries benchmarkDifficulty = 0.0, so a surviving
        // benchmark filter would reject it after the query and return null.
        val pick: RandomClimbRoll = picker(
            filter = snapshot(BoardBrand.QUANTUM.wireValue, benchmarkOnly = true),
            capture = capture,
            results = listOf(quantumClimb()),
        ).roll()

        assertTrue(pick is RandomClimbRoll.Picked)
    }

    @Test
    fun `Kilter keeps every filter it persisted`() = runTest {
        val capture = Capture()
        val pick: RandomClimbRoll = picker(
            filter = snapshot(BoardBrand.KILTER.wireValue, climbType = "ROUTE"),
            capture = capture,
            results = listOf(quantumClimb()),
        ).roll()

        assertTrue(pick is RandomClimbRoll.Picked)
        assertEquals(ClimbTypeFilter.ROUTE, capture.climbType.captured)
        assertEquals(9201, capture.productSizeId.captured)
        assertEquals(0L, capture.excludedMask.captured)
    }

    // ── The board the group is actually on ───────────────────────────────
    //
    // The persisted filters describe the board this device last looked at.
    // Taking only the family from the group and keeping that board's layout
    // and angle asks the catalogue an impossible question — brand=quantum,
    // layout=1 — which returns nothing for as long as the group lasts, under
    // a message blaming filters the Quantum filter sheet does not even show.

    private fun cell(
        brand: String,
        entries: List<BoardPlaylistEntry> = emptyList(),
        currentEntryId: String? = entries.firstOrNull()?.entryId,
        projection: BoardProjection? = null,
    ): BoardCellSnapshot {
        val physical = PhysicalBoardId("$brand:ble:AA:BB:CC:DD:EE:FF")
        return BoardCellSnapshot(
            cellId = BoardCellId.forPhysical(physical),
            physicalBoardId = physical,
            epoch = 1,
            sequence = 1,
            controllerId = "controller",
            lineageId = "lineage",
            members = setOf("controller", "me"),
            projection = projection,
            playlist = BoardPlaylistState(entries = entries, currentEntryId = currentEntryId),
        )
    }

    private fun catalogueClimb(uuid: String, brand: BoardBrand, layoutId: Long) =
        TestClimb.stats(
            uuid = uuid, name = "On the group's board", setterUsername = "s",
            difficulty = 12.0, quality = 3.0, ascensionists = 4, frames = "",
        ).copy(boardBrand = brand.wireValue, layoutId = layoutId)

    @Test
    fun `joining a Quantum board takes its layout and angle from the group, not from Kilter`() = runTest {
        val capture = Capture()
        val onTheList = "55555555-5555-5555-5555-555555555555"

        picker(
            // Last browsed Kilter Original at 40 degrees.
            filter = snapshot(BoardBrand.KILTER.wireValue),
            capture = capture,
            results = listOf(quantumClimb()),
            cell = cell(
                BoardBrand.QUANTUM.wireValue,
                entries = listOf(BoardPlaylistEntry("e1", onTheList, angle = 25)),
            ),
            knownClimbs = mapOf(onTheList to catalogueClimb(onTheList, BoardBrand.QUANTUM, 9102)),
        ).roll()

        assertEquals(BoardBrand.QUANTUM.wireValue, capture.boardBrand.captured)
        assertEquals(9102, capture.layoutId.captured)
        assertEquals(25, capture.angle.captured)
    }

    @Test
    fun `joining a Kilter board from Quantum works the same way round`() = runTest {
        val capture = Capture()
        val onTheList = "66666666-6666-6666-6666-666666666666"

        val roll = picker(
            filter = snapshot(BoardBrand.QUANTUM.wireValue),
            capture = capture,
            results = listOf(quantumClimb()),
            cell = cell(
                BoardBrand.KILTER.wireValue,
                entries = listOf(BoardPlaylistEntry("e1", onTheList, angle = 50)),
            ),
            knownClimbs = mapOf(onTheList to catalogueClimb(onTheList, BoardBrand.KILTER, 1)),
        ).roll()

        assertTrue(roll is RandomClimbRoll.Picked)
        assertEquals(BoardBrand.KILTER.wireValue, capture.boardBrand.captured)
        assertEquals(1, capture.layoutId.captured)
        assertEquals(50, capture.angle.captured)
        // And Kilter's own filters come back with it.
        assertEquals(9201, capture.productSizeId.captured)
    }

    @Test
    fun `what the board is showing counts as much as what the list points at`() = runTest {
        val capture = Capture()
        val projected = "77777777-7777-7777-7777-777777777777"

        picker(
            filter = snapshot(BoardBrand.KILTER.wireValue),
            capture = capture,
            results = listOf(quantumClimb()),
            cell = cell(
                BoardBrand.QUANTUM.wireValue,
                projection = BoardProjection(projected, angle = 30, projectionSurvivesDisconnect = false),
            ),
            knownClimbs = mapOf(projected to catalogueClimb(projected, BoardBrand.QUANTUM, 9103)),
        ).roll()

        assertEquals(9103, capture.layoutId.captured)
        assertEquals(30, capture.angle.captured)
    }

    @Test
    fun `an empty list is seeded from the current browser filters`() = runTest {
        val capture = Capture()

        val roll = picker(
            filter = snapshot(BoardBrand.KILTER.wireValue),
            capture = capture,
            results = listOf(quantumClimb()),
            cell = cell(BoardBrand.QUANTUM.wireValue),
        ).roll()

        assertTrue(roll is RandomClimbRoll.Picked)
        assertEquals(BoardBrand.KILTER.wireValue, capture.boardBrand.captured)
        assertEquals(9101, capture.layoutId.captured)
        assertEquals(40, capture.angle.captured)
    }

    /**
     * The hole review 2 found, and the reason a brand comparison is not the
     * check it looks like: `PhysicalBoardId` is a family plus a serial or BLE
     * address, and says nothing about the model. Kilter Original and Kilter
     * Homewall are the same brand and different layouts.
     */
    @Test
    fun `an empty same-brand group uses the current browser layout`() = runTest {
        val capture = Capture()

        val roll = picker(
            // Kilter Original configured locally…
            filter = snapshot(BoardBrand.KILTER.wireValue),
            capture = capture,
            results = listOf(quantumClimb()),
            // …a Kilter group whose board could just as well be a Homewall.
            cell = cell(BoardBrand.KILTER.wireValue),
            connectedToCellBoard = false,
        ).roll()

        assertTrue(roll is RandomClimbRoll.Picked)
        assertEquals(BoardBrand.KILTER.wireValue, capture.boardBrand.captured)
        assertEquals(9101, capture.layoutId.captured)
    }

    /**
     * The case the refusal must not break: start a group on your own board and
     * roll the dice to seed the list. This device is the one attached to that
     * controller, so the board it browses, renders and sends with is the same
     * board — its configuration is authoritative.
     */
    @Test
    fun `on your own board the local layout is the group's layout`() = runTest {
        val capture = Capture()

        val roll = picker(
            filter = snapshot(BoardBrand.KILTER.wireValue),
            capture = capture,
            results = listOf(quantumClimb()),
            cell = cell(BoardBrand.KILTER.wireValue),
            connectedToCellBoard = true,
        ).roll()

        assertTrue(roll is RandomClimbRoll.Picked)
        assertEquals(BoardBrand.KILTER.wireValue, capture.boardBrand.captured)
        assertEquals(9101, capture.layoutId.captured)
        assertEquals(40, capture.angle.captured)
    }

    @Test
    fun `a same-brand group on a different layout rolls for the group's layout`() = runTest {
        val capture = Capture()
        val onTheList = "99999999-9999-9999-9999-999999999999"

        picker(
            // Configured for Kilter Original (layout 9101 in this fixture)…
            filter = snapshot(BoardBrand.KILTER.wireValue),
            capture = capture,
            results = listOf(quantumClimb()),
            // …but the group's climbs are on a different Kilter layout.
            cell = cell(
                BoardBrand.KILTER.wireValue,
                entries = listOf(BoardPlaylistEntry("e1", onTheList, angle = 40)),
            ),
            knownClimbs = mapOf(onTheList to catalogueClimb(onTheList, BoardBrand.KILTER, 8)),
            // Even while attached to it: the group's own climbs win.
            connectedToCellBoard = true,
        ).roll()

        assertEquals(8, capture.layoutId.captured)
    }

    @Test
    fun `a same-brand group at a different angle rolls at the group's angle`() = runTest {
        val capture = Capture()
        val onTheList = "10101010-1010-1010-1010-101010101010"

        picker(
            // Locally browsing at 40 degrees.
            filter = snapshot(BoardBrand.KILTER.wireValue),
            capture = capture,
            results = listOf(quantumClimb()),
            cell = cell(
                BoardBrand.KILTER.wireValue,
                entries = listOf(BoardPlaylistEntry("e1", onTheList, angle = 20)),
            ),
            knownClimbs = mapOf(onTheList to catalogueClimb(onTheList, BoardBrand.KILTER, 9101)),
            connectedToCellBoard = true,
        ).roll()

        assertEquals(
            "the wall is at the angle the group set it to, not the one this phone last browsed",
            20, capture.angle.captured,
        )
    }

    @Test
    fun `an unresolved playlist reference falls back to browser filters`() = runTest {
        val capture = Capture()
        val unknown = "88888888-8888-8888-8888-888888888888"

        val roll = picker(
            filter = snapshot(BoardBrand.KILTER.wireValue),
            capture = capture,
            results = listOf(quantumClimb()),
            cell = cell(
                BoardBrand.QUANTUM.wireValue,
                entries = listOf(BoardPlaylistEntry("e1", unknown, angle = 25)),
            ),
        ).roll()

        assertTrue(roll is RandomClimbRoll.Picked)
        assertEquals(BoardBrand.KILTER.wireValue, capture.boardBrand.captured)
        assertEquals(9101, capture.layoutId.captured)
        assertEquals(40, capture.angle.captured)
    }
}
