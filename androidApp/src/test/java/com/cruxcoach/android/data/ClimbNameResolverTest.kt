package com.cruxcoach.android.data

import com.cruxcoach.android.fakes.TestClimb
import com.cruxcoach.data.repository.BoardRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ClimbNameResolver].
 *
 * The resolver collapses 5 UUID shapes (raw / lower / upper / hyphenated-lower
 * / hyphenated-upper) onto a single DB row. The DB lookup is case-sensitive,
 * so the test uses a mock with exact-string `every` matchers — an
 * equalsIgnoreCase fake would hide bugs in the case-folding ladder.
 */
class ClimbNameResolverTest {

    private val repo: BoardRepository = mockk(relaxed = true)
    private val resolver = ClimbNameResolver(repo)

    private fun climb(uuid: String, name: String = "Test Climb", diff: Double? = 18.5) =
        TestClimb.stats(uuid = uuid, name = name, difficulty = diff, ascensionists = 42L)

    @Test
    fun `raw uuid hit returns immediately without fallbacks`() {
        val uuid = "305ecf35-4ab5-4c9c-afd5-91af0848004b"
        every { repo.getClimbByUuid(uuid, 40) } returns climb(uuid)

        assertEquals("Test Climb", resolver.resolveName(uuid, 40))
        // Confirm no second lookup happened (fallback ladder not reached).
        verify(exactly = 1) { repo.getClimbByUuid(any(), 40) }
    }

    @Test
    fun `uppercase-no-hyphen BLE-style uuid is resolved via lowercase hyphenated`() {
        // BLE advertises UUIDs as uppercase-no-hyphens; DB stores lowercase-hyphenated.
        // This is the most important translation in the resolver.
        val stored = "305ecf35-4ab5-4c9c-afd5-91af0848004b"
        val ble = "305ECF354AB54C9CAFD591AF0848004B"
        every { repo.getClimbByUuid(any(), 40) } returns null
        every { repo.getClimbByUuid(stored, 40) } returns climb(stored, name = "LED Flow")

        assertEquals("LED Flow", resolver.resolveName(ble, 40))
    }

    @Test
    fun `lowercase no-hyphen is resolved via hyphenation + lowercase`() {
        val stored = "305ecf35-4ab5-4c9c-afd5-91af0848004b"
        val input = "305ecf354ab54c9cafd591af0848004b"
        every { repo.getClimbByUuid(any(), 40) } returns null
        every { repo.getClimbByUuid(stored, 40) } returns climb(stored)

        assertEquals("Test Climb", resolver.resolveName(input, 40))
    }

    @Test
    fun `uppercase-hyphenated uuid is resolved via lowercase`() {
        val stored = "305ecf35-4ab5-4c9c-afd5-91af0848004b"
        val input = "305ECF35-4AB5-4C9C-AFD5-91AF0848004B"
        every { repo.getClimbByUuid(any(), 40) } returns null
        every { repo.getClimbByUuid(stored, 40) } returns climb(stored)

        assertEquals("Test Climb", resolver.resolveName(input, 40))
    }

    @Test
    fun `returns null when no format variant matches`() {
        every { repo.getClimbByUuid(any(), any()) } returns null

        assertNull(resolver.resolveName("305ECF354AB54C9CAFD591AF0848004B", 40))
    }

    @Test
    fun `short non-32-char input skips hyphenation branch`() {
        every { repo.getClimbByUuid(any(), any()) } returns null

        assertNull(resolver.resolveName("abc", 40))
        // 3 lookups (raw / lower / upper) — hyphenation branch is skipped because length != 32.
        verify(exactly = 3) { repo.getClimbByUuid(any(), 40) }
    }

    @Test
    fun `resolveInfo returns name and difficulty together`() {
        val uuid = "305ecf35-4ab5-4c9c-afd5-91af0848004b"
        every { repo.getClimbByUuid(uuid, 40) } returns climb(uuid, name = "Crimpy", diff = 22.7)

        val info = resolver.resolveInfo(uuid, 40)
        assertEquals(ClimbDisplayInfo("Crimpy", 22.7), info)
    }

    @Test
    fun `resolveInfo returns null when resolver finds no match`() {
        every { repo.getClimbByUuid(any(), any()) } returns null

        assertNull(resolver.resolveInfo("305ECF354AB54C9CAFD591AF0848004B", 40))
    }

    @Test
    fun `angle is propagated to every resolution lookup`() {
        val uuid = "305ECF354AB54C9CAFD591AF0848004B"
        every { repo.getClimbByUuid(any(), any()) } returns null

        resolver.resolveName(uuid, angle = 70)

        // Resolution lookups (raw, lowercase, uppercase, hyphen-lower,
        // hyphen-upper) MUST all carry the requested angle so a
        // wrong-angle climb_stats row never wins. The "uuid-not-in-
        // climbs vs stats-missing-for-angle" diagnostic at the end of
        // resolveClimb does one extra angle=0 lookup intentionally
        // (it asks "does this uuid exist for ANY angle?") — that
        // single call is exempt and asserted-on-purpose so a future
        // refactor doesn't silently spam getClimbByUuid with random
        // angles in the resolution path.
        verify(exactly = 5) { repo.getClimbByUuid(any(), 70) }
        verify(exactly = 1) { repo.getClimbByUuid(uuid.lowercase(), 0) }
        verify(exactly = 0) {
            repo.getClimbByUuid(any(), match { it != 70 && it != 0 })
        }
    }

    @Test
    fun `name-only resolution works at default angle=0 for BLE presence banners`() {
        val stored = "305ecf35-4ab5-4c9c-afd5-91af0848004b"
        val ble = "305ECF354AB54C9CAFD591AF0848004B"
        every { repo.getClimbByUuid(any(), 0) } returns null
        every { repo.getClimbByUuid(stored, 0) } returns climb(stored, name = "V5 warm-up")

        assertEquals("V5 warm-up", resolver.resolveName(ble))
    }
}
