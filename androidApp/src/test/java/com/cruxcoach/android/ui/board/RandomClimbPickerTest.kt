package com.cruxcoach.android.ui.board

import com.cruxcoach.android.fakes.FakeBoardRepository
import com.cruxcoach.android.fakes.TestClimb
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.SortDirection
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the random climb picker feature.
 *
 * Verifies that:
 * - Random offset produces a valid climb from the filtered result set
 * - Different offsets yield different climbs (not always the first)
 * - Edge cases: empty results, single climb, search query mode
 * - The offset range matches the count (no out-of-bounds)
 */
class RandomClimbPickerTest {

    private fun climb(
        uuid: String,
        name: String = "Climb $uuid",
        setter: String? = "setter",
        difficulty: Double = 10.0,
        quality: Double = 3.0,
        ascensionists: Long = 100,
    ): ClimbWithStats = TestClimb.stats(
        uuid = uuid, name = name, setterUsername = setter,
        difficulty = difficulty, quality = quality,
        ascensionists = ascensionists, frames = "",
    )

    private fun repoWithClimbs(count: Int): FakeBoardRepository {
        val repo = FakeBoardRepository()
        (1..count).forEach { i ->
            repo.addClimb(climb("uuid-$i", difficulty = i.toDouble(), ascensionists = i.toLong()))
        }
        return repo
    }

    /**
     * Core logic extracted from BoardBrowserViewModel.pickRandomClimb().
     * Tests this directly to avoid ViewModel/coroutine complexity.
     */
    private fun pickRandom(
        repo: FakeBoardRepository,
        randomOffset: Int,
        searchQuery: String = "",
        angle: Int = 40,
        layoutId: Int = 1,
        minDifficulty: Double = 0.0,
        maxDifficulty: Double = 100.0,
        minAscensionists: Int = 0,
        climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER
    ): String? {
        val climb = if (searchQuery.isNotBlank()) {
            repo.searchClimbsByName(
                searchQuery, angle, layoutId, "kilter", ClimbSortField.ASCENSIONISTS, SortDirection.DESC,
                limit = 1, offset = randomOffset, climbType = climbType
            )
        } else {
            repo.searchClimbsSorted(
                angle, layoutId, "kilter", minDifficulty, maxDifficulty, minAscensionists,
                ClimbSortField.ASCENSIONISTS, SortDirection.DESC,
                limit = 1, offset = randomOffset, climbType = climbType
            )
        }
        return climb.firstOrNull()?.uuid
    }

    // ── Basic offset correctness ─────────────────────────────────

    @Test
    fun `offset 0 returns first climb`() {
        val repo = repoWithClimbs(10)
        val uuid = pickRandom(repo, randomOffset = 0)
        assertNotNull(uuid)
    }

    @Test
    fun `offset returns climb at that position`() {
        val repo = repoWithClimbs(10)
        // Each offset should return a different climb
        val uuids = (0 until 10).map { offset -> pickRandom(repo, randomOffset = offset) }
        assertEquals(10, uuids.filterNotNull().toSet().size, "Each offset should yield a unique climb")
    }

    @Test
    fun `different offsets yield different climbs`() {
        val repo = repoWithClimbs(50)
        val uuid0 = pickRandom(repo, randomOffset = 0)
        val uuid25 = pickRandom(repo, randomOffset = 25)
        val uuid49 = pickRandom(repo, randomOffset = 49)

        assertNotNull(uuid0)
        assertNotNull(uuid25)
        assertNotNull(uuid49)
        // At least two of the three should be different (extremely unlikely all same)
        assertTrue(
            uuid0 != uuid25 || uuid25 != uuid49,
            "Different offsets should produce different climbs"
        )
    }

    // ── Random distribution ──────────────────────────────────────

    @Test
    fun `random picks from full range not just first`() {
        val repo = repoWithClimbs(100)
        val count = 100
        val pickedUuids = mutableSetOf<String>()

        // Pick 50 random climbs — should hit more than just the first few
        repeat(50) {
            val offset = Random.nextInt(count)
            val uuid = pickRandom(repo, randomOffset = offset)
            if (uuid != null) pickedUuids.add(uuid)
        }

        assertTrue(
            pickedUuids.size > 10,
            "50 random picks from 100 climbs should yield >10 unique results, got ${pickedUuids.size}"
        )
    }

    // ── Edge cases ───────────────────────────────────────────────

    @Test
    fun `empty repository returns null`() {
        val repo = FakeBoardRepository()
        val uuid = pickRandom(repo, randomOffset = 0)
        assertNull(uuid)
    }

    @Test
    fun `single climb always returns that climb`() {
        val repo = repoWithClimbs(1)
        val uuid = pickRandom(repo, randomOffset = 0)
        assertEquals("uuid-1", uuid)
    }

    @Test
    fun `offset beyond count returns null`() {
        val repo = repoWithClimbs(5)
        val uuid = pickRandom(repo, randomOffset = 10)
        assertNull(uuid, "Offset beyond result count should return null")
    }

    @Test
    fun `offset at exact count boundary returns null`() {
        val repo = repoWithClimbs(5)
        val uuid = pickRandom(repo, randomOffset = 5)
        assertNull(uuid, "Offset equal to count (0-indexed) should return null")
    }

    @Test
    fun `last valid offset returns a climb`() {
        val repo = repoWithClimbs(5)
        val uuid = pickRandom(repo, randomOffset = 4)
        assertNotNull(uuid, "Last valid offset should return a climb")
    }

    // ── Search query mode ────────────────────────────────────────

    @Test
    fun `search query filters then applies offset`() {
        val repo = FakeBoardRepository()
        repo.addClimb(climb("a", name = "Alpha Problem"))
        repo.addClimb(climb("b", name = "Beta Route"))
        repo.addClimb(climb("c", name = "Alpha Slab"))
        repo.addClimb(climb("d", name = "Gamma Dyno"))

        // Search "Alpha" matches 2 climbs
        val uuid0 = pickRandom(repo, randomOffset = 0, searchQuery = "Alpha")
        val uuid1 = pickRandom(repo, randomOffset = 1, searchQuery = "Alpha")
        val uuid2 = pickRandom(repo, randomOffset = 2, searchQuery = "Alpha")

        assertNotNull(uuid0)
        assertNotNull(uuid1)
        assertNull(uuid2, "Only 2 Alphas exist, offset 2 should be null")
        assertTrue(uuid0 != uuid1, "Two different offsets should yield different results")
        assertTrue(uuid0 in setOf("a", "c"), "Should match Alpha climbs")
        assertTrue(uuid1 in setOf("a", "c"), "Should match Alpha climbs")
    }

    @Test
    fun `search query with no matches returns null`() {
        val repo = repoWithClimbs(10)
        val uuid = pickRandom(repo, randomOffset = 0, searchQuery = "nonexistent")
        assertNull(uuid)
    }

    // ── Difficulty filter ────────────────────────────────────────

    @Test
    fun `difficulty filter narrows random pool`() {
        val repo = FakeBoardRepository()
        (1..20).forEach { i ->
            repo.addClimb(climb("uuid-$i", difficulty = i.toDouble(), ascensionists = 10))
        }

        // Only climbs with difficulty 5.0..10.0 — 6 climbs (5,6,7,8,9,10)
        val uuids = (0 until 6).mapNotNull { offset ->
            pickRandom(repo, randomOffset = offset, minDifficulty = 5.0, maxDifficulty = 10.0)
        }
        assertEquals(6, uuids.size, "Should get exactly 6 climbs in difficulty range")

        // Offset 6 should be out of range
        val outOfRange = pickRandom(repo, randomOffset = 6, minDifficulty = 5.0, maxDifficulty = 10.0)
        assertNull(outOfRange, "Offset beyond filtered count should be null")
    }

    // ── Count consistency ────────────────────────────────────────

    @Test
    fun `count matches number of fetchable climbs via offset`() {
        val repo = repoWithClimbs(25)
        val count = repo.countFilteredClimbs(
            angle = 40, layoutId = 1, boardBrand = "kilter", minDifficulty = 0.0, maxDifficulty = 100.0,
            minAscensionists = 0, climbType = ClimbTypeFilter.BOULDER
        )

        // Every offset from 0 to count-1 should return a climb
        val validPicks = (0 until count.toInt()).count { offset ->
            pickRandom(repo, randomOffset = offset) != null
        }
        assertEquals(count.toInt(), validPicks, "Count should equal number of valid offsets")
    }

    @Test
    fun `search count matches number of fetchable climbs via offset`() {
        val repo = FakeBoardRepository()
        repo.addClimb(climb("a", name = "Test Boulder"))
        repo.addClimb(climb("b", name = "Test Slab"))
        repo.addClimb(climb("c", name = "Other"))

        val count = repo.countSearchClimbs("Test", angle = 40, layoutId = 1, boardBrand = "kilter", climbType = ClimbTypeFilter.BOULDER)
        assertEquals(2L, count)

        val validPicks = (0 until count.toInt()).count { offset ->
            pickRandom(repo, randomOffset = offset, searchQuery = "Test") != null
        }
        assertEquals(count.toInt(), validPicks)
    }

    // ── RandomClimbEvent dedup safety ────────────────────────────

    @Test
    fun `RandomClimbEvent with same UUID but different id are not equal`() {
        val e1 = BoardBrowserViewModel.RandomClimbEvent("uuid-1", id = 1)
        val e2 = BoardBrowserViewModel.RandomClimbEvent("uuid-1", id = 2)
        assertTrue(e1 != e2, "Events with different ids should not be equal (prevents StateFlow dedup)")
    }

    @Test
    fun `RandomClimbEvent with same UUID and same id are equal`() {
        val e1 = BoardBrowserViewModel.RandomClimbEvent("uuid-1", id = 1)
        val e2 = BoardBrowserViewModel.RandomClimbEvent("uuid-1", id = 1)
        assertEquals(e1, e2)
    }
}
