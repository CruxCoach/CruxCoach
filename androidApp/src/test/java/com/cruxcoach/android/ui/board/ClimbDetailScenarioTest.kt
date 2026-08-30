package com.cruxcoach.android.ui.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClimbDetailScenarioTest {
    @Test
    fun `scenario pair stays adb addressable and shares climb identity`() {
        assertEquals(
            listOf("detail/disconnected", "detail/connected"),
            ClimbDetailScenarios.all.map { it.id }.toList(),
        )
        assertEquals(
            ClimbDetailScenarios.Disconnected.state.identity,
            ClimbDetailScenarios.Connected.state.identity,
        )
    }

    @Test
    fun `unknown scenario is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ClimbDetailScenarios.require("detail/unknown")
        }
    }
}
