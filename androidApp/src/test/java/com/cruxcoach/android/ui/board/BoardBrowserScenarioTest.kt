package com.cruxcoach.android.ui.board

import com.cruxcoach.domain.board.BoardBrowserScreenState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BoardBrowserScenarioTest {
    @Test
    fun `fixture ids and recovery states stay deterministic`() {
        val scenarios = BoardBrowserScenarios.all.toList()

        assertEquals(
            listOf("browser/content", "browser/empty", "browser/error"),
            scenarios.map { it.id },
        )
        assertEquals(scenarios.size, scenarios.map { it.id }.toSet().size)
        assertIs<BoardBrowserScreenState.Content>(BoardBrowserScenarios.Content.state)
        assertIs<BoardBrowserScreenState.Empty>(BoardBrowserScenarios.Empty.state)
        assertIs<BoardBrowserScreenState.Error>(BoardBrowserScenarios.Error.state)
    }

    @Test
    fun `addressable fixture lookup rejects drift`() {
        BoardBrowserScenarios.all.forEach { scenario ->
            assertEquals(scenario, BoardBrowserScenarios.require(scenario.id))
        }
        assertFailsWith<IllegalArgumentException> {
            BoardBrowserScenarios.require("browser/unknown")
        }
    }
}
