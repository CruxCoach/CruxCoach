package com.cruxcoach.android.ui.workout

import androidx.lifecycle.SavedStateHandle
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The effort and sleep sliders move in half steps. A snapped slider value can come out a hair off its mark
 * (6.9999995 for 7), which then sat in the saved log and could pick the label of the step below.
 */
class PostWorkoutSliderSnapTest {

    private val vm = PostWorkoutViewModel(SavedStateHandle(), mockk(relaxed = true))

    @Test
    fun `effort lands on its half step`() {
        vm.updateRpe(6.9999995f)
        assertEquals(7f, vm.state.value.rpe)
        vm.updateRpe(5.0000005f)
        assertEquals(5f, vm.state.value.rpe)
        vm.updateRpe(8.4999995f)
        assertEquals(8.5f, vm.state.value.rpe)
    }

    @Test
    fun `sleep lands on its half step and stays in range`() {
        vm.updateSleepHours(7.4999995f)
        assertEquals(7.5f, vm.state.value.sleepHours)
        vm.updateSleepHours(14.0000010f)
        assertEquals(14f, vm.state.value.sleepHours)
    }
}
