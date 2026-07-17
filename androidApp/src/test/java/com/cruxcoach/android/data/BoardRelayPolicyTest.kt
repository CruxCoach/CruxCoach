package com.cruxcoach.android.data

import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardRelayPolicyTest {
    @Test
    fun `relay is available to hosts on Aurora protocol boards`() {
        listOf(
            BoardBrand.KILTER,
            BoardBrand.TENSION,
            BoardBrand.GRASSHOPPER,
            BoardBrand.DECOY,
            BoardBrand.SOILL,
            BoardBrand.TOUCHSTONE,
        ).forEach { brand ->
            assertEquals(
                BoardRelayAvailability.AVAILABLE,
                BoardRelayPolicy.availability(brand, SessionRole.HOST),
            )
        }
    }

    @Test
    fun `MoonBoard is not offered an Aurora frame relay`() {
        assertEquals(
            BoardRelayAvailability.UNSUPPORTED_PROTOCOL,
            BoardRelayPolicy.availability(BoardBrand.MOONBOARD, SessionRole.NONE),
        )
    }

    @Test
    fun `session participants cannot front the hosts board`() {
        assertEquals(
            BoardRelayAvailability.SESSION_PARTICIPANT,
            BoardRelayPolicy.availability(BoardBrand.KILTER, SessionRole.PARTICIPANT),
        )
    }

    @Test
    fun `stopping relay ends its own session instead of disconnecting twice`() {
        val plan = BoardRelayPolicy.stopPlan(
            relayStartedSession = true,
            sessionRole = SessionRole.HOST,
            releaseBoardRequested = true,
        )

        assertTrue(plan.stopHostSession)
        assertFalse(plan.releaseBoardDirectly)
    }

    @Test
    fun `stopping relay preserves an independently started session and board`() {
        val plan = BoardRelayPolicy.stopPlan(
            relayStartedSession = false,
            sessionRole = SessionRole.HOST,
            releaseBoardRequested = true,
        )

        assertFalse(plan.stopHostSession)
        assertFalse(plan.releaseBoardDirectly)
    }

    @Test
    fun `relay-only stop preserves its helper session after CruxCoach guests join`() {
        val plan = BoardRelayPolicy.stopPlan(
            relayStartedSession = true,
            sessionRole = SessionRole.HOST,
            releaseBoardRequested = true,
            hasCruxCoachGuests = true,
        )

        assertFalse(plan.stopHostSession)
        assertFalse(plan.releaseBoardDirectly)
    }

    @Test
    fun `relay without a session releases a connected board on stop`() {
        val plan = BoardRelayPolicy.stopPlan(
            relayStartedSession = false,
            sessionRole = SessionRole.NONE,
            releaseBoardRequested = true,
        )

        assertFalse(plan.stopHostSession)
        assertTrue(plan.releaseBoardDirectly)
    }

    @Test
    fun `session stop explicitly ends an independently started host session`() {
        val plan = BoardRelayPolicy.stopPlan(
            relayStartedSession = false,
            sessionRole = SessionRole.HOST,
            releaseBoardRequested = true,
            endHostSessionRequested = true,
        )

        assertTrue(plan.stopHostSession)
        assertFalse(plan.releaseBoardDirectly)
    }

    @Test
    fun `explicit session stop also ends a relay helper session with guests`() {
        val plan = BoardRelayPolicy.stopPlan(
            relayStartedSession = true,
            sessionRole = SessionRole.HOST,
            releaseBoardRequested = true,
            endHostSessionRequested = true,
            hasCruxCoachGuests = true,
        )

        assertTrue(plan.stopHostSession)
        assertFalse(plan.releaseBoardDirectly)
    }
}
