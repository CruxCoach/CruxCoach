package com.cruxcoach.android.ui.board

import com.cruxcoach.android.fakes.FakeBoardRepository
import com.cruxcoach.android.fakes.TestClimb
import com.cruxcoach.android.ui.navigation.ClimbNavigationState
import com.cruxcoach.data.repository.ClimbWithStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the Setter Filter feature.
 *
 * Verifies that:
 * - ClimbNavigationState carries the pendingSetterFilter correctly
 * - FakeBoardRepository searchClimbsByName matches setter_username (like the real SQL)
 * - countSearchClimbs also matches setter_username
 * - The filter is consumed (reset to null) after reading
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

    // ── ClimbNavigationState pendingSetterFilter ──────────────

    @Test
    fun `pendingSetterFilter is null by default`() {
        val navState = ClimbNavigationState()
        assertNull(navState.pendingSetterFilter)
    }

    @Test
    fun `pendingSetterFilter stores and returns setter name`() {
        val navState = ClimbNavigationState()
        navState.pendingSetterFilter = "CoolSetter123"
        assertEquals("CoolSetter123", navState.pendingSetterFilter)
    }

    @Test
    fun `pendingSetterFilter can be consumed and reset to null`() {
        val navState = ClimbNavigationState()
        navState.pendingSetterFilter = "TestSetter"

        // Simulate consume pattern from BoardBrowserViewModel
        val consumed = navState.pendingSetterFilter
        navState.pendingSetterFilter = null

        assertEquals("TestSetter", consumed)
        assertNull(navState.pendingSetterFilter)
    }

    @Test
    fun `pendingSetterFilter can be overwritten before consumption`() {
        val navState = ClimbNavigationState()
        navState.pendingSetterFilter = "FirstSetter"
        navState.pendingSetterFilter = "SecondSetter"
        assertEquals("SecondSetter", navState.pendingSetterFilter)
    }

    // ── FakeBoardRepository search matches setter_username ────

    @Test
    fun `searchClimbsByName matches setter username`() {
        val repo = FakeBoardRepository()
        repo.addClimbs(
            climb("1", name = "Alpha", setter = "JohnDoe"),
            climb("2", name = "Beta", setter = "JaneDoe"),
            climb("3", name = "Gamma", setter = "MikeSmith")
        )

        val results = repo.searchClimbsByName("JohnDoe", angle = 40, layoutId = 1)
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

        val results = repo.searchClimbsByName("johndoe", angle = 40, layoutId = 1)
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

        val results = repo.searchClimbsByName("JohnDoe", angle = 40, layoutId = 1)
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

        val results = repo.searchClimbsByName("NonExistent", angle = 40, layoutId = 1)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `searchClimbsByName handles null setter username`() {
        val repo = FakeBoardRepository()
        repo.addClimbs(
            climb("1", name = "Alpha", setter = null),
            climb("2", name = "Beta", setter = "JohnDoe")
        )

        val results = repo.searchClimbsByName("JohnDoe", angle = 40, layoutId = 1)
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

        val count = repo.countSearchClimbs("JohnDoe", angle = 40, layoutId = 1)
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

        val count = repo.countSearchClimbs("JohnDoe", angle = 40, layoutId = 1)
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

        val aliceClimbs = repo.searchClimbsByName(setterA, angle = 40, layoutId = 1)
        val bobClimbs = repo.searchClimbsByName(setterB, angle = 40, layoutId = 1)

        assertEquals(2, aliceClimbs.size)
        assertTrue(aliceClimbs.all { it.setterUsername == setterA })

        assertEquals(3, bobClimbs.size)
        assertTrue(bobClimbs.all { it.setterUsername == setterB })
    }

    // ── End-to-end: pendingSetterFilter → search query flow ───

    @Test
    fun `setter filter flow - set filter then consume into search query`() {
        val navState = ClimbNavigationState()
        val repo = FakeBoardRepository()
        repo.addClimbs(
            climb("1", name = "Easy One", setter = "ProSetter"),
            climb("2", name = "Hard One", setter = "ProSetter"),
            climb("3", name = "Medium One", setter = "OtherGuy")
        )

        // Step 1: Detail screen sets the setter filter
        navState.pendingSetterFilter = "ProSetter"

        // Step 2: Browser consumes it (simulating refreshBoardData)
        val searchQuery = navState.pendingSetterFilter!!
        navState.pendingSetterFilter = null

        // Step 3: Search with the consumed query
        val results = repo.searchClimbsByName(searchQuery, angle = 40, layoutId = 1)

        assertEquals(2, results.size)
        assertTrue(results.all { it.setterUsername == "ProSetter" })
        assertNull(navState.pendingSetterFilter)
    }
}
