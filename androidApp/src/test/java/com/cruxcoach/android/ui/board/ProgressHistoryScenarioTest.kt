package com.cruxcoach.android.ui.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProgressHistoryScenarioTest {
    @Test
    fun `scenario ids remain adb addressable`() {
        assertEquals(
            listOf("progress/history", "progress/empty", "progress/error"),
            ProgressHistoryScenarios.all.map { it.id }.toList(),
        )
        assertEquals(
            ProgressHistoryScenarios.History,
            ProgressHistoryScenarios.require("progress/history"),
        )
    }

    @Test
    fun `unknown scenario is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ProgressHistoryScenarios.require("progress/unknown")
        }
    }
}
