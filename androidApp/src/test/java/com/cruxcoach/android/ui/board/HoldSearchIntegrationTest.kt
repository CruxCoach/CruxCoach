package com.cruxcoach.android.ui.board

import com.cruxcoach.android.fakes.FakeBoardRepository
import com.cruxcoach.data.repository.AuroraClimbWithStats
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.domain.board.HoldHeatmapComputer
import com.cruxcoach.domain.board.HoldRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests verifying that hold-search and heatmap features work
 * correctly through the FakeBoardRepository, mirroring how the ViewModel
 * calls the repository layer.
 *
 * Tests the full pipeline:
 *   1. searchClimbUuidsByHold (SQL LIKE simulation)
 *   2. UUID intersection for multi-hold search
 *   3. getAllFramesForHeatmap → HoldHeatmapComputer pipeline
 */
class HoldSearchIntegrationTest {

    private fun climb(
        uuid: String,
        frames: String,
        difficulty: Double = 10.0,
        ascensionists: Long = 50
    ) = AuroraClimbWithStats(
        uuid = uuid,
        layoutId = 1,
        setterUsername = "setter",
        name = "Climb $uuid",
        frames = frames,
        framesCount = 1,
        difficultyAverage = difficulty,
        qualityAverage = 3.0,
        ascensionistCount = ascensionists
    )

    private fun frames(vararg holds: Pair<Int, Int>): String =
        holds.joinToString("") { (p, r) -> "p${p}r${r}" }

    // ═══ searchClimbUuidsByHold (single-hold LIKE search) ═══

    @Test
    fun `searchClimbUuidsByHold finds climbs containing hold pattern`() {
        val repo = FakeBoardRepository()
        val f1 = frames(100 to HoldRole.START, 200 to HoldRole.HAND, 300 to HoldRole.FINISH)
        val f2 = frames(100 to HoldRole.START, 201 to HoldRole.HAND, 301 to HoldRole.FINISH)
        val f3 = frames(101 to HoldRole.START, 202 to HoldRole.HAND, 302 to HoldRole.FINISH)
        repo.addClimb(climb("a", f1))
        repo.addClimb(climb("b", f2))
        repo.addClimb(climb("c", f3))

        val pattern = HoldHeatmapComputer.holdLikePattern(100) // "p100r"
        val result = repo.searchClimbUuidsByHold(pattern, 40, 0.0, 100.0, 0, ClimbTypeFilter.BOULDER)

        assertEquals(listOf("a", "b"), result.sorted())
    }

    @Test
    fun `searchClimbUuidsByHold no matches returns empty`() {
        val repo = FakeBoardRepository()
        repo.addClimb(climb("a", frames(100 to HoldRole.START)))

        val pattern = HoldHeatmapComputer.holdLikePattern(999)
        val result = repo.searchClimbUuidsByHold(pattern, 40, 0.0, 100.0, 0, ClimbTypeFilter.BOULDER)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `searchClimbUuidsByHold respects difficulty filter`() {
        val repo = FakeBoardRepository()
        val f = frames(100 to HoldRole.START, 200 to HoldRole.FINISH)
        repo.addClimb(climb("easy", f, difficulty = 5.0))
        repo.addClimb(climb("hard", f, difficulty = 25.0))

        val pattern = HoldHeatmapComputer.holdLikePattern(100)
        val result = repo.searchClimbUuidsByHold(pattern, 40, 10.0, 30.0, 0, ClimbTypeFilter.BOULDER)

        assertEquals(listOf("hard"), result)
    }

    @Test
    fun `searchClimbUuidsByHold respects ascensionist filter`() {
        val repo = FakeBoardRepository()
        val f = frames(100 to HoldRole.START, 200 to HoldRole.FINISH)
        repo.addClimb(climb("popular", f, ascensionists = 100))
        repo.addClimb(climb("obscure", f, ascensionists = 2))

        val pattern = HoldHeatmapComputer.holdLikePattern(100)
        val result = repo.searchClimbUuidsByHold(pattern, 40, 0.0, 100.0, 10, ClimbTypeFilter.BOULDER)

        assertEquals(listOf("popular"), result)
    }

    // ═══ Multi-hold UUID intersection (ViewModel logic) ═══

    @Test
    fun `multi-hold intersection finds climbs containing ALL holds`() {
        val repo = FakeBoardRepository()
        val f1 = frames(100 to HoldRole.START, 200 to HoldRole.HAND, 300 to HoldRole.FINISH)
        val f2 = frames(100 to HoldRole.START, 201 to HoldRole.HAND, 300 to HoldRole.FINISH)
        val f3 = frames(101 to HoldRole.START, 200 to HoldRole.HAND, 301 to HoldRole.FINISH)
        repo.addClimb(climb("a", f1)) // has 100 + 200
        repo.addClimb(climb("b", f2)) // has 100 but not 200
        repo.addClimb(climb("c", f3)) // has 200 but not 100

        // Simulate ViewModel's findUuidsMatchingAllHolds
        val selectedHolds = setOf(100, 200)
        var resultUuids: Set<String>? = null
        for (holdId in selectedHolds) {
            val pattern = HoldHeatmapComputer.holdLikePattern(holdId)
            val uuids = repo.searchClimbUuidsByHold(
                pattern, 40, 0.0, 100.0, 0, ClimbTypeFilter.BOULDER
            ).toSet()
            resultUuids = resultUuids?.intersect(uuids) ?: uuids
            if (resultUuids.isEmpty()) break
        }

        assertEquals(setOf("a"), resultUuids)
    }

    @Test
    fun `multi-hold intersection with no overlap returns empty`() {
        val repo = FakeBoardRepository()
        repo.addClimb(climb("a", frames(100 to HoldRole.START, 200 to HoldRole.FINISH)))
        repo.addClimb(climb("b", frames(101 to HoldRole.START, 201 to HoldRole.FINISH)))

        // Hold 100 is in "a", hold 201 is in "b" — no climb has both
        val selectedHolds = listOf(100, 201)
        var resultUuids: Set<String>? = null
        for (holdId in selectedHolds) {
            val pattern = HoldHeatmapComputer.holdLikePattern(holdId)
            val uuids = repo.searchClimbUuidsByHold(
                pattern, 40, 0.0, 100.0, 0, ClimbTypeFilter.BOULDER
            ).toSet()
            resultUuids = resultUuids?.intersect(uuids) ?: uuids
            if (resultUuids.isEmpty()) break
        }

        assertTrue(resultUuids!!.isEmpty())
    }

    @Test
    fun `multi-hold intersection short-circuits on first empty`() {
        val repo = FakeBoardRepository()
        repo.addClimb(climb("a", frames(100 to HoldRole.START, 200 to HoldRole.FINISH)))

        // Hold 999 doesn't exist — intersection should be empty after first miss
        val selectedHolds = listOf(999, 100)
        var resultUuids: Set<String>? = null
        var iterations = 0
        for (holdId in selectedHolds) {
            iterations++
            val pattern = HoldHeatmapComputer.holdLikePattern(holdId)
            val uuids = repo.searchClimbUuidsByHold(
                pattern, 40, 0.0, 100.0, 0, ClimbTypeFilter.BOULDER
            ).toSet()
            resultUuids = resultUuids?.intersect(uuids) ?: uuids
            if (resultUuids.isEmpty()) break
        }

        assertTrue(resultUuids!!.isEmpty())
        assertEquals(1, iterations, "Should short-circuit after first empty result")
    }

    // ═══ getAllFramesForHeatmap → HoldHeatmapComputer pipeline ═══

    @Test
    fun `heatmap pipeline produces correct global heatmap`() {
        val repo = FakeBoardRepository()
        val f1 = frames(100 to HoldRole.START, 200 to HoldRole.HAND, 300 to HoldRole.FINISH)
        val f2 = frames(100 to HoldRole.START, 201 to HoldRole.HAND, 300 to HoldRole.FINISH)
        val f3 = frames(101 to HoldRole.START, 202 to HoldRole.HAND, 301 to HoldRole.FINISH)
        repo.addClimb(climb("a", f1))
        repo.addClimb(climb("b", f2))
        repo.addClimb(climb("c", f3))

        val frameRows = repo.getAllFramesForHeatmap(40, 0.0, 100.0, 0, ClimbTypeFilter.BOULDER)
        val rawHeatmap = HoldHeatmapComputer.computeGlobalHeatmap(frameRows.map { it.frames })
        val normalized = HoldHeatmapComputer.normalizeHeatmap(rawHeatmap)

        // Hold 100 appears in 2 climbs, Hold 300 in 2 climbs, Hold 101 in 1
        assertEquals(2, rawHeatmap[100])
        assertEquals(2, rawHeatmap[300])
        assertEquals(1, rawHeatmap[101])

        // Normalized max should be 1.0
        val maxNorm = normalized.values.max()
        assertTrue(maxNorm > 0.99f && maxNorm <= 1.0f)
    }

    @Test
    fun `heatmap pipeline filters by difficulty`() {
        val repo = FakeBoardRepository()
        val f = frames(100 to HoldRole.START, 200 to HoldRole.FINISH)
        repo.addClimb(climb("easy", f, difficulty = 5.0))
        repo.addClimb(climb("mid", f, difficulty = 15.0))
        repo.addClimb(climb("hard", f, difficulty = 25.0))

        // Only mid-range climbs
        val frameRows = repo.getAllFramesForHeatmap(40, 10.0, 20.0, 0, ClimbTypeFilter.BOULDER)
        assertEquals(1, frameRows.size)
        assertEquals("mid", frameRows[0].uuid)
    }

    @Test
    fun `heatmap pipeline with role filter only counts start holds`() {
        val repo = FakeBoardRepository()
        val f1 = frames(100 to HoldRole.START, 200 to HoldRole.HAND, 300 to HoldRole.FOOT, 400 to HoldRole.FINISH)
        val f2 = frames(100 to HoldRole.START, 201 to HoldRole.HAND, 301 to HoldRole.FOOT, 400 to HoldRole.FINISH)
        repo.addClimb(climb("a", f1))
        repo.addClimb(climb("b", f2))

        val frameRows = repo.getAllFramesForHeatmap(40, 0.0, 100.0, 0, ClimbTypeFilter.BOULDER)
        val startHeatmap = HoldHeatmapComputer.computeHeatmapByRole(
            frameRows.map { it.frames }, HoldRole.START
        )

        // Only hold 100 is a start hold (in both climbs)
        assertEquals(1, startHeatmap.size)
        assertEquals(2, startHeatmap[100])
    }

    @Test
    fun `heatmap pipeline empty repo returns empty heatmap`() {
        val repo = FakeBoardRepository()

        val frameRows = repo.getAllFramesForHeatmap(40, 0.0, 100.0, 0, ClimbTypeFilter.BOULDER)
        val rawHeatmap = HoldHeatmapComputer.computeGlobalHeatmap(frameRows.map { it.frames })
        val normalized = HoldHeatmapComputer.normalizeHeatmap(rawHeatmap)

        assertTrue(frameRows.isEmpty())
        assertTrue(rawHeatmap.isEmpty())
        assertTrue(normalized.isEmpty())
    }

    // ═══ HoldSearchState logic ═══

    @Test
    fun `HoldSearchState defaults are correct`() {
        val state = HoldSearchState()
        assertTrue(state.selectedHolds.isEmpty())
        assertEquals(HeatmapMode.OFF, state.heatmapMode)
        assertTrue(state.heatmapData.isEmpty())
        assertEquals(0, state.matchCount)
        assertEquals(false, state.isSearching)
        assertEquals(false, state.holdFilterActive)
        assertTrue(state.holdFilterUuids.isEmpty())
        assertEquals(false, state.showSheet)
    }

    @Test
    fun `HoldSearchState toggles sheet correctly`() {
        val state = HoldSearchState()
        val toggled = state.copy(showSheet = !state.showSheet)
        assertEquals(true, toggled.showSheet)
        val toggledBack = toggled.copy(showSheet = !toggled.showSheet)
        assertEquals(false, toggledBack.showSheet)
    }

    @Test
    fun `HoldSearchState hold selection add and remove`() {
        val state = HoldSearchState()

        // Add hold
        val withHold = state.copy(selectedHolds = state.selectedHolds + 100)
        assertEquals(setOf(100), withHold.selectedHolds)

        // Add another
        val withTwo = withHold.copy(selectedHolds = withHold.selectedHolds + 200)
        assertEquals(setOf(100, 200), withTwo.selectedHolds)

        // Remove first
        val withOne = withTwo.copy(selectedHolds = withTwo.selectedHolds - 100)
        assertEquals(setOf(200), withOne.selectedHolds)
    }

    @Test
    fun `HoldSearchState clear resets all fields`() {
        val active = HoldSearchState(
            selectedHolds = setOf(100, 200),
            matchCount = 5,
            holdFilterActive = true,
            holdFilterUuids = setOf("a", "b"),
            heatmapMode = HeatmapMode.GLOBAL,
            heatmapData = mapOf(100 to 0.5f)
        )

        val cleared = active.copy(
            selectedHolds = emptySet(),
            matchCount = 0,
            holdFilterActive = false,
            holdFilterUuids = emptySet()
        )

        assertTrue(cleared.selectedHolds.isEmpty())
        assertEquals(0, cleared.matchCount)
        assertEquals(false, cleared.holdFilterActive)
        assertTrue(cleared.holdFilterUuids.isEmpty())
        // heatmapMode and heatmapData remain (they're separate concern)
        assertEquals(HeatmapMode.GLOBAL, cleared.heatmapMode)
    }

    // ═══ HeatmapMode enum ═══

    @Test
    fun `HeatmapMode has all expected entries`() {
        val modes = HeatmapMode.entries
        assertEquals(7, modes.size)
        assertTrue(modes.map { it.name }.containsAll(
            listOf("OFF", "GLOBAL", "PERSONAL", "START", "HAND", "FOOT", "FINISH")
        ))
    }

    // ═══ holdLikePattern doesn't produce false-positive LIKE matches ═══

    @Test
    fun `holdLikePattern for hold 10 does not match hold 100`() {
        val pattern10 = HoldHeatmapComputer.holdLikePattern(10) // "p10r"
        val frameWith100 = frames(100 to HoldRole.START) // "p100r12"

        // The LIKE pattern is used as '%' || pattern || '%' in SQL.
        // "p100r12" does contain "p10" but NOT "p10r" — it contains "p100r".
        // Let's verify this distinction.
        assertTrue(!frameWith100.contains(pattern10),
            "Pattern 'p10r' should NOT match inside 'p100r12'")

        val frameWith10 = frames(10 to HoldRole.START) // "p10r12"
        assertTrue(frameWith10.contains(pattern10),
            "Pattern 'p10r' should match inside 'p10r12'")
    }

    @Test
    fun `holdLikePattern for hold 1 does not match hold 10 or 100`() {
        val pattern1 = HoldHeatmapComputer.holdLikePattern(1) // "p1r"
        val frame10 = frames(10 to HoldRole.START) // "p10r12"
        val frame100 = frames(100 to HoldRole.START) // "p100r12"
        val frame1 = frames(1 to HoldRole.START) // "p1r12"

        assertTrue(!frame10.contains(pattern1))
        assertTrue(!frame100.contains(pattern1))
        assertTrue(frame1.contains(pattern1))
    }
}
