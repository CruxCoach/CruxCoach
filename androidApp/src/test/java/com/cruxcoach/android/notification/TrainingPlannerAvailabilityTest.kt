package com.cruxcoach.android.notification

import kotlin.test.Test
import kotlin.test.assertFalse

class TrainingPlannerAvailabilityTest {
    @Test
    fun `hidden planner does not schedule user reminders`() {
        assertFalse(TrainingPlannerAvailability.remindersEnabled)
    }
}
