package com.cruxcoach.data

import com.cruxcoach.data.repository.ClimbWithStats
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The match/no-match badge may only be rendered where the flag is a real
 * answer. `is_nomatch` is NOT NULL DEFAULT 0 in the blob, so every climb we
 * never learned the setting for still reads "matching allowed" — showing the
 * green badge there would be invented information.
 */
class ClimbWithStatsMatchStateTest {

    private fun climb(
        origin: String,
        isNomatch: Boolean = false,
        boardBrand: String = "kilter",
    ) = ClimbWithStats(
        uuid = "u",
        layoutId = 1L,
        setterUsername = "setter",
        name = "climb",
        frames = "p1r12",
        framesCount = 1L,
        difficultyAverage = null,
        qualityAverage = null,
        ascensionistCount = null,
        isNomatch = isNomatch,
        origin = origin,
        boardBrand = boardBrand,
    )

    @Test
    fun kilterClimbsHaveAKnownMatchState() {
        assertTrue(climb(origin = "kilter").isMatchStateKnown)
        assertTrue(climb(origin = "kilter", isNomatch = true).isMatchStateKnown)
    }

    @Test
    fun communityClimbsHaveNoKnownMatchState() {
        // The editor writes is_nomatch=0 on every draft and the publisher
        // sends allowMatch=true unconditionally — the setter is never asked.
        assertFalse(climb(origin = "cruxcoach").isMatchStateKnown)
    }

    @Test
    fun boardseshClimbsHaveNoKnownMatchState() {
        // BoardSesh-only user climbs never reach Kilter; the feed has no
        // such field at all.
        assertFalse(climb(origin = "boardsesh").isMatchStateKnown)
    }

    @Test
    fun unknownOriginIsTreatedAsUnknownMatchState() {
        assertFalse(climb(origin = "some-future-origin").isMatchStateKnown)
    }

    @Test
    fun `moonboard problems never claim a known match state`() {
        // No-match is an Aurora rule; a MoonBoard problem has none. Every
        // MoonBoard row still carries origin='kilter' as its native-catalogue
        // marker, so provenance alone would wrongly say "known".
        assertFalse(climb(origin = "kilter", boardBrand = "moonboard").isMatchStateKnown)
    }
}
