package com.cruxcoach.android.ui.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AscentLoggingScenarioTest {
    @Test
    fun `fixture ids and form states stay deterministic`() {
        val scenarios = AscentLoggingScenarios.all.toList()

        assertEquals(
            listOf("log/new-send", "log/new-attempt", "log/edit-send", "log/success", "log/error"),
            scenarios.map { it.id },
        )
        assertEquals(scenarios.size, scenarios.map { it.id }.toSet().size)
        assertTrue(AscentLoggingScenarios.NewSend.isSend)
        assertFalse(AscentLoggingScenarios.NewAttempt.isSend)
        assertTrue(AscentLoggingScenarios.EditSend.isEditing)
        assertEquals(4, AscentLoggingScenarios.EditSend.quality)
        assertEquals(AscentLoggingScenarioKind.SUCCESS, AscentLoggingScenarios.Success.kind)
        assertEquals(
            com.cruxcoach.domain.board.AttemptLogSubmissionState.FAILED,
            AscentLoggingScenarios.Error.submissionState,
        )
    }

    @Test
    fun `addressable fixture lookup rejects drift`() {
        AscentLoggingScenarios.all.forEach { scenario ->
            assertEquals(scenario, AscentLoggingScenarios.require(scenario.id))
        }
        assertFailsWith<IllegalArgumentException> {
            AscentLoggingScenarios.require("log/unknown")
        }
    }
}
