package com.cruxcoach.android.ui.board

import com.cruxcoach.android.fakes.FakeBoardRepository
import com.cruxcoach.android.fakes.FakePersonalBoardRepository
import com.cruxcoach.android.fakes.TestClimb
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.SortDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for Board Browser status filter optimizations.
 *
 * Verifies that:
 * - getClimbsByUuids filters correctly by UUID set
 * - FakeBoardRepository behaves consistently with the real implementation
 * - Sorting in Kotlin (used for SENT/ATTEMPTED) produces correct order
 * - Status filter logic (SENT/ATTEMPTED/NEW/UNSENT) works correctly
 */
class BoardBrowserStatusFilterTest {

    private fun climb(
        uuid: String,
        name: String = "Climb $uuid",
        difficulty: Double = 10.0,
        quality: Double = 3.0,
        ascensionists: Long = 100,
    ): ClimbWithStats = TestClimb.stats(
        uuid = uuid, name = name, difficulty = difficulty,
        quality = quality, ascensionists = ascensionists,
        // Preserve this file's pre-dedupe defaults (empty frames was a
        // quirk that several status-filter assertions implicitly relied
        // on — keep it explicit instead of inheriting TestClimb's
        // shared default).
        frames = "",
    )

    // ── getClimbsByUuids in FakeBoardRepository ──────────────────

    @Test
    fun `getClimbsByUuids returns only climbs matching UUID set`() {
        val repo = FakeBoardRepository()
        val c1 = climb("uuid-1")
        val c2 = climb("uuid-2")
        val c3 = climb("uuid-3")
        repo.addClimbs(c1, c2, c3)

        val result = repo.getClimbsByUuids(
            setOf("uuid-1", "uuid-3"), angle = 40, layoutId = 1, boardBrand = "kilter",
            minDifficulty = 0.0, maxDifficulty = 100.0,
            minAscensionists = 0, climbType = ClimbTypeFilter.BOULDER
        )

        assertEquals(2, result.size)
        assertEquals(setOf("uuid-1", "uuid-3"), result.map { it.uuid }.toSet())
    }

    @Test
    fun `getClimbsByUuids returns empty for empty UUID set`() {
        val repo = FakeBoardRepository()
        repo.addClimb(climb("uuid-1"))

        val result = repo.getClimbsByUuids(
            emptySet(), angle = 40, layoutId = 1, boardBrand = "kilter",
            minDifficulty = 0.0, maxDifficulty = 100.0,
            minAscensionists = 0, climbType = ClimbTypeFilter.BOULDER
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getClimbsByUuids returns empty when no UUIDs match`() {
        val repo = FakeBoardRepository()
        repo.addClimb(climb("uuid-1"))

        val result = repo.getClimbsByUuids(
            setOf("no-match"), angle = 40, layoutId = 1, boardBrand = "kilter",
            minDifficulty = 0.0, maxDifficulty = 100.0,
            minAscensionists = 0, climbType = ClimbTypeFilter.BOULDER
        )

        assertTrue(result.isEmpty())
    }

    // ── Status filter set logic (via PersonalBoardRepository) ────

    @Test
    fun `SENT filter returns only sent climb UUIDs`() {
        val boardRepo = FakeBoardRepository()
        val personalRepo = FakePersonalBoardRepository()
        val climbs = (1..5).map { climb("uuid-$it") }
        climbs.forEach { boardRepo.addClimb(it) }
        personalRepo.markSent("uuid-2")
        personalRepo.markSent("uuid-4")

        val sentUuids = personalRepo.getUserSentClimbUuids()
        val filtered = climbs.filter { it.uuid in sentUuids }

        assertEquals(2, filtered.size)
        assertEquals(setOf("uuid-2", "uuid-4"), filtered.map { it.uuid }.toSet())
    }

    @Test
    fun `ATTEMPTED filter returns bid-only UUIDs excluding sent`() {
        val personalRepo = FakePersonalBoardRepository()
        personalRepo.markSent("uuid-1")
        personalRepo.markAttempted("uuid-2")
        personalRepo.markAttempted("uuid-3")

        val sentUuids = personalRepo.getUserSentClimbUuids()
        val attemptedUuids = personalRepo.getUserAttemptedClimbUuids()

        assertTrue("uuid-1" !in attemptedUuids, "sent climb should not be in attempted")
        assertEquals(setOf("uuid-2", "uuid-3"), attemptedUuids)
    }

    @Test
    fun `NEW filter excludes both sent and attempted`() {
        val boardRepo = FakeBoardRepository()
        val personalRepo = FakePersonalBoardRepository()
        val climbs = (1..5).map { climb("uuid-$it") }
        climbs.forEach { boardRepo.addClimb(it) }
        personalRepo.markSent("uuid-1")
        personalRepo.markAttempted("uuid-2")

        val sentUuids = personalRepo.getUserSentClimbUuids()
        val attemptedUuids = personalRepo.getUserAttemptedClimbUuids()
        val newClimbs = climbs.filter { it.uuid !in sentUuids && it.uuid !in attemptedUuids }

        assertEquals(3, newClimbs.size)
        assertEquals(setOf("uuid-3", "uuid-4", "uuid-5"), newClimbs.map { it.uuid }.toSet())
    }

    @Test
    fun `UNSENT filter excludes sent but includes attempted`() {
        val boardRepo = FakeBoardRepository()
        val personalRepo = FakePersonalBoardRepository()
        val climbs = (1..5).map { climb("uuid-$it") }
        climbs.forEach { boardRepo.addClimb(it) }
        personalRepo.markSent("uuid-1")
        personalRepo.markAttempted("uuid-2")

        val sentUuids = personalRepo.getUserSentClimbUuids()
        val unsentClimbs = climbs.filter { it.uuid !in sentUuids }

        assertEquals(4, unsentClimbs.size)
        assertTrue("uuid-2" in unsentClimbs.map { it.uuid }, "attempted should be in unsent")
    }

    // ── Kotlin-side sorting (used for SENT/ATTEMPTED results) ────

    @Test
    fun `sort by difficulty ascending`() {
        val climbs = listOf(
            climb("a", difficulty = 15.0),
            climb("b", difficulty = 5.0),
            climb("c", difficulty = 10.0)
        )
        val sorted = sortInKotlin(climbs, ClimbSortField.DIFFICULTY, SortDirection.ASC)

        assertEquals(listOf(5.0, 10.0, 15.0), sorted.map { it.difficultyAverage })
    }

    @Test
    fun `sort by difficulty descending`() {
        val climbs = listOf(
            climb("a", difficulty = 5.0),
            climb("b", difficulty = 15.0),
            climb("c", difficulty = 10.0)
        )
        val sorted = sortInKotlin(climbs, ClimbSortField.DIFFICULTY, SortDirection.DESC)

        assertEquals(listOf(15.0, 10.0, 5.0), sorted.map { it.difficultyAverage })
    }

    @Test
    fun `sort by quality ascending`() {
        val climbs = listOf(
            climb("a", quality = 4.5),
            climb("b", quality = 2.0),
            climb("c", quality = 3.3)
        )
        val sorted = sortInKotlin(climbs, ClimbSortField.QUALITY, SortDirection.ASC)

        assertEquals(listOf(2.0, 3.3, 4.5), sorted.map { it.qualityAverage })
    }

    @Test
    fun `sort by quality descending`() {
        val climbs = listOf(
            climb("a", quality = 2.0),
            climb("b", quality = 4.5),
            climb("c", quality = 3.3)
        )
        val sorted = sortInKotlin(climbs, ClimbSortField.QUALITY, SortDirection.DESC)

        assertEquals(listOf(4.5, 3.3, 2.0), sorted.map { it.qualityAverage })
    }

    @Test
    fun `sort by ascensionists descending`() {
        val climbs = listOf(
            climb("a", ascensionists = 50),
            climb("b", ascensionists = 200),
            climb("c", ascensionists = 10)
        )
        val sorted = sortInKotlin(climbs, ClimbSortField.ASCENSIONISTS, SortDirection.DESC)

        assertEquals(listOf(200L, 50L, 10L), sorted.map { it.ascensionistCount })
    }

    @Test
    fun `sort by name case-insensitive ascending`() {
        val climbs = listOf(
            climb("a", name = "Zebra"),
            climb("b", name = "alpha"),
            climb("c", name = "Beta")
        )
        val sorted = sortInKotlin(climbs, ClimbSortField.NAME, SortDirection.ASC)

        assertEquals(listOf("alpha", "Beta", "Zebra"), sorted.map { it.name })
    }

    @Test
    fun `sort by name case-insensitive descending`() {
        val climbs = listOf(
            climb("a", name = "alpha"),
            climb("b", name = "Zebra"),
            climb("c", name = "Beta")
        )
        val sorted = sortInKotlin(climbs, ClimbSortField.NAME, SortDirection.DESC)

        assertEquals(listOf("Zebra", "Beta", "alpha"), sorted.map { it.name })
    }

    @Test
    fun `sort handles null difficulty gracefully`() {
        val climbs = listOf(
            climb("a", difficulty = 10.0),
            ClimbWithStats(
                uuid = "b", layoutId = 1, setterUsername = null, name = "NoDiff",
                frames = "", framesCount = 1, difficultyAverage = null,
                qualityAverage = null, ascensionistCount = 0
            ),
            climb("c", difficulty = 5.0)
        )
        val sorted = sortInKotlin(climbs, ClimbSortField.DIFFICULTY, SortDirection.ASC)

        // null treated as 0.0 → sorted first
        assertEquals("b", sorted.first().uuid)
    }

    @Test
    fun `sort empty list returns empty`() {
        val sorted = sortInKotlin(emptyList(), ClimbSortField.QUALITY, SortDirection.DESC)
        assertTrue(sorted.isEmpty())
    }

    // ── Count accuracy ───────────────────────────────────────────

    @Test
    fun `SENT count matches actual filtered result size`() {
        val boardRepo = FakeBoardRepository()
        val personalRepo = FakePersonalBoardRepository()
        val climbs = (1..100).map { climb("uuid-$it") }
        climbs.forEach { boardRepo.addClimb(it) }
        (1..15).forEach { personalRepo.markSent("uuid-$it") }

        val sentUuids = personalRepo.getUserSentClimbUuids()
        val sentClimbs = boardRepo.getClimbsByUuids(
            sentUuids, angle = 40, layoutId = 1, boardBrand = "kilter",
            minDifficulty = 0.0, maxDifficulty = 100.0,
            minAscensionists = 0, climbType = ClimbTypeFilter.BOULDER
        )

        assertEquals(15, sentClimbs.size)
        assertEquals(sentUuids.size.toLong(), sentClimbs.size.toLong())
    }

    // ── Helper: delegates to the production sort so any drift in the
    //     ViewModel's sort logic is caught by these tests instead of
    //     hidden behind a copy. ────────────────────────────────────

    private fun sortInKotlin(
        climbs: List<ClimbWithStats>, field: ClimbSortField, dir: SortDirection
    ): List<ClimbWithStats> = boardBrowserSortInKotlin(climbs, field, dir)
}
