package com.cruxcoach.android.data

import com.cruxcoach.android.fakes.createTestUserPreferences
import com.cruxcoach.domain.board.HoldRole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 0.1.x → 0.2.0 LED default-color migration
 * ([UserPreferences.migrateLegacyLedDefaultsIfNeeded]).
 *
 * Goal: every user who is "on the default" ends up on the *current* default
 * (start=Magenta, hand=Blue, finish=Green, foot=Red), regardless of how they
 * got there — never touched, reset, or having manually dialled an old default
 * back in. Genuine custom colors and the Kilter preset must survive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LedDefaultColorMigrationTest {

    private fun TestScope.prefs() = createTestUserPreferences(backgroundScope)

    // Old shipped defaults (foot Teal 0x1D for v0.1.0–0.1.1, Mint 0x1E for
    // v0.1.2–0.1.4) and the new 0.2.0 default, for readability.
    private val newDefault = LedHoldColors(
        start = LedHoldColors.CRUXCOACH_START,
        hand = LedHoldColors.CRUXCOACH_HAND,
        finish = LedHoldColors.CRUXCOACH_FINISH,
        foot = LedHoldColors.CRUXCOACH_FOOT,
    )

    @Test
    fun `never-customised user already renders the new default and migration leaves it there`() = runTest {
        val p = prefs()
        // No LED keys written at all → null fallback is the current default.
        assertEquals(newDefault, p.ledHoldColors.first())
        p.migrateLegacyLedDefaultsIfNeeded()
        assertEquals(newDefault, p.ledHoldColors.first())
    }

    @Test
    fun `old default tuple (foot Teal) is migrated to the new default`() = runTest {
        val p = prefs()
        p.setLedColor(HoldRole.START, 0xEC)
        p.setLedColor(HoldRole.HAND, 0x03)
        p.setLedColor(HoldRole.FINISH, 0xE3)
        p.setLedColor(HoldRole.FOOT, 0x1D)
        p.migrateLegacyLedDefaultsIfNeeded()
        assertEquals(newDefault, p.ledHoldColors.first())
    }

    @Test
    fun `old default tuple (foot Mint) is migrated to the new default`() = runTest {
        val p = prefs()
        p.setLedColor(HoldRole.START, 0xEC)
        p.setLedColor(HoldRole.HAND, 0x03)
        p.setLedColor(HoldRole.FINISH, 0xE3)
        p.setLedColor(HoldRole.FOOT, 0x1E)
        p.migrateLegacyLedDefaultsIfNeeded()
        assertEquals(newDefault, p.ledHoldColors.first())
    }

    @Test
    fun `genuine custom colors are left untouched`() = runTest {
        val p = prefs()
        val custom = LedHoldColors(start = 0xF4, hand = 0x1F, finish = 0xE0, foot = 0x03)
        p.setLedColor(HoldRole.START, custom.start)
        p.setLedColor(HoldRole.HAND, custom.hand)
        p.setLedColor(HoldRole.FINISH, custom.finish)
        p.setLedColor(HoldRole.FOOT, custom.foot)
        p.migrateLegacyLedDefaultsIfNeeded()
        assertEquals(custom, p.ledHoldColors.first())
    }

    @Test
    fun `Kilter preset is left untouched`() = runTest {
        val p = prefs()
        p.setKilterColors()
        p.migrateLegacyLedDefaultsIfNeeded()
        assertEquals(LedHoldColors.kilterStandard(), p.ledHoldColors.first())
    }

    @Test
    fun `partial old-default (only some roles set) is left untouched`() = runTest {
        // Only foot stored as an old-default value; the other three are null
        // (already the new default). Not a full-tuple match → no action, and
        // the stored foot is a deliberate single-role choice we must keep.
        val p = prefs()
        p.setLedColor(HoldRole.FOOT, 0x1E)
        p.migrateLegacyLedDefaultsIfNeeded()
        val after = p.ledHoldColors.first()
        assertEquals(0x1E, after.foot)
        assertEquals(LedHoldColors.CRUXCOACH_START, after.start)
    }

    @Test
    fun `migration runs once - a recreated old tuple after migration is preserved`() = runTest {
        val p = prefs()
        // First run on a fresh install: marks the one-time flag.
        p.migrateLegacyLedDefaultsIfNeeded()
        // Later, the user deliberately dials an old default tuple back in.
        p.setLedColor(HoldRole.START, 0xEC)
        p.setLedColor(HoldRole.HAND, 0x03)
        p.setLedColor(HoldRole.FINISH, 0xE3)
        p.setLedColor(HoldRole.FOOT, 0x1E)
        // A subsequent cold-start call must NOT wipe it (guard already set).
        p.migrateLegacyLedDefaultsIfNeeded()
        assertEquals(
            LedHoldColors(start = 0xEC, hand = 0x03, finish = 0xE3, foot = 0x1E),
            p.ledHoldColors.first(),
        )
    }
}
