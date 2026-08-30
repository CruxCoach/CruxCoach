package com.cruxcoach.android.ui.board

import com.cruxcoach.android.fakes.FakeBoardRepository
import com.cruxcoach.android.fakes.TestClimb
import com.cruxcoach.data.repository.ClimbWithStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the Setter Filter feature.
 *
 * Verifies that:
 * - FakeBoardRepository searchClimbsByName matches setter_username (like the real SQL)
 * - countSearchClimbs also matches setter_username
 * - Search by setter name returns only that setter's climbs
 */
class SetterFilterTest {

    private fun climb(
        uuid: String,
        name: String = "Climb $uuid",
        setter: String? = "defaultSetter",
        difficulty: Double = 10.0,
        ascensionists: Long = 100,
    ): ClimbWithStats = TestClimb.stats(
        uuid = uuid, name = name, setterUsername = setter,
        difficulty = difficulty, ascensionists = ascensionists, frames = "",
    )

    // ── FakeBoardRepository search matches setter_username ────

    @Test
    fun `searchClimbsByName matches setter username`() {
        val repo = FakeBoardRepository()
        repo.addClimbs(
            climb("1", name = "Alpha", setter = "JohnDoe"),
            climb("2", name = "Beta", setter = "JaneDoe"),
            climb("3", name = "Gamma", setter = "MikeSmith")
        )

        val results = repo.searchClimbsByName("JohnDoe", angle = 40, layoutId = 1, boardBrand = "kilter")
        assertEquals(1, results.size)
        assertEquals("1", results.first().uuid)
    }

    @Test
    fun `searchClimbsByName matches setter username case-insensitively`() {
        val repo = FakeBoardRepository()
        repo.addClimbs(
            climb("1", name = "Alpha", setter = "JohnDoe"),
            climb("2", name = "Beta", setter = "JaneDoe")
        )

        val results = repo.searchClimbsByName("johndoe", angle = 40, layoutId = 1, boardBrand = "kilter")
        assertEquals(1, results.size)
        assertEquals("1", results.first().uuid)
    }

    @Test
    fun `searchClimbsByName returns climbs matching either name or setter`() {
        val repo = FakeBoardRepository()
        repo.addClimbs(
            climb("1", name = "JohnDoe's Problem", setter = "OtherSetter"),
            climb("2", name = "Beta", setter = "JohnDoe"),
            climb("3", name = "Gamma", setter = "MikeSmith")
        )

        val results = repo.searchClimbsByName("JohnDoe", angle = 40, layoutId = 1, boardBrand = "kilter")
        assertEquals(2, results.size)
        assertEquals(setOf("1", "2"), results.map { it.uuid }.toSet())
    }

    @Test
    fun `searchClimbsByName returns empty when setter not found`() {
        val repo = FakeBoardRepository()
        repo.addClimbs(
            climb("1", name = "Alpha", setter = "JohnDoe"),
            climb("2", name = "Beta", setter = "JaneDoe")
        )

        val results = repo.searchClimbsByName("NonExistent", angle = 40, layoutId = 1, boardBrand = "kilter")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `searchClimbsByName handles null setter username`() {
        val repo = FakeBoardRepository()
        repo.addClimbs(
            climb("1", name = "Alpha", setter = null),
            climb("2", name = "Beta", setter = "JohnDoe")
        )

        val results = repo.searchClimbsByName("JohnDoe", angle = 40, layoutId = 1, boardBrand = "kilter")
        assertEquals(1, results.size)
        assertEquals("2", results.first().uuid)
    }

    @Test
    fun `countSearchClimbs matches setter username`() {
        val repo = FakeBoardRepository()
        repo.addClimbs(
            climb("1", name = "Alpha", setter = "JohnDoe"),
            climb("2", name = "Beta", setter = "JohnDoe"),
            climb("3", name = "Gamma", setter = "MikeSmith")
        )

        val count = repo.countSearchClimbs("JohnDoe", angle = 40, layoutId = 1, boardBrand = "kilter")
        assertEquals(2L, count)
    }

    @Test
    fun `countSearchClimbs counts name and setter matches without duplicates`() {
        val repo = FakeBoardRepository()
        // This climb matches both name and setter -- should only be counted once
        repo.addClimbs(
            climb("1", name = "JohnDoe's Boulder", setter = "JohnDoe"),
            climb("2", name = "Beta", setter = "MikeSmith")
        )

        val count = repo.countSearchClimbs("JohnDoe", angle = 40, layoutId = 1, boardBrand = "kilter")
        assertEquals(1L, count)
    }

    // ── Setter filter produces unique results per setter ──────

    @Test
    fun `filtering by exact setter username returns only their climbs`() {
        val repo = FakeBoardRepository()
        val setterA = "AliceSetter"
        val setterB = "BobSetter"
        repo.addClimbs(
            climb("1", name = "Problem 1", setter = setterA),
            climb("2", name = "Problem 2", setter = setterA),
            climb("3", name = "Problem 3", setter = setterB),
            climb("4", name = "Problem 4", setter = setterB),
            climb("5", name = "Problem 5", setter = setterB)
        )

        val aliceClimbs = repo.searchClimbsByName(setterA, angle = 40, layoutId = 1, boardBrand = "kilter")
        val bobClimbs = repo.searchClimbsByName(setterB, angle = 40, layoutId = 1, boardBrand = "kilter")

        assertEquals(2, aliceClimbs.size)
        assertTrue(aliceClimbs.all { it.setterUsername == setterA })

        assertEquals(3, bobClimbs.size)
        assertTrue(bobClimbs.all { it.setterUsername == setterB })
    }

}
