package com.cruxcoach.android.ui.board

import com.cruxcoach.domain.board.ActiveSessionPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ActiveSessionScenarioTest {
    @Test
    fun `fixture ids and phases stay deterministic`() {
        val scenarios = ActiveSessionScenarios.all.toList()

        assertEquals(
            listOf(
                "session/active",
                "session/resting",
                "session/paused",
                "session/active-no-climb",
            ),
            scenarios.map { it.id },
        )
        assertEquals(scenarios.size, scenarios.map { it.id }.toSet().size)
        assertEquals(ActiveSessionPhase.RESTING, ActiveSessionScenarios.Resting.state.phase)
        assertEquals(75, ActiveSessionScenarios.Resting.state.restSecondsRemaining)
        assertNull(ActiveSessionScenarios.Paused.state.restSecondsRemaining)
        assertNull(ActiveSessionScenarios.ActiveNoClimb.state.currentClimb)
    }

    @Test
    fun `addressable fixture lookup rejects drift`() {
        ActiveSessionScenarios.all.forEach { scenario ->
            assertEquals(scenario, ActiveSessionScenarios.require(scenario.id))
        }
        assertFailsWith<IllegalArgumentException> {
            ActiveSessionScenarios.require("session/unknown")
        }
    }
}
