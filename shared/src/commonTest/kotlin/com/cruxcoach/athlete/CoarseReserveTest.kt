package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.NextTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reserve of a long plain hold is asked in steps of five seconds (device walk 2026-11-22): which holds those are,
 * and the words of the four answers – the same on the question, the row of the done set and its correction.
 */
class CoarseReserveTest {

    private fun def(slug: String, kind: ExerciseKind, domains: List<LoadDomain> = emptyList(), durationS: Int? = null) =
        ExerciseDefinition(slug, ExerciseCategoryV2.CORE, kind, LoadMode.BODYWEIGHT, domains = domains,
            defaults = Prescription(sets = 3, durationS = durationS), i18n = mapOf("en" to ExerciseText(slug)))

    @Test
    fun theFourAnswersAreZeroFiveTenAndFifteenPlus() {
        assertEquals(listOf("0", "5", "10", "15+"), (0..3).map { NextTargets.coarseReserveLabel(it) })
        // Stored levels outside the four read as the nearest answer.
        assertEquals("15+", NextTargets.coarseReserveLabel(5))
        assertEquals("0", NextTargets.coarseReserveLabel(-1))
        assertEquals(5, NextTargets.COARSE_RESERVE_STEP_S)
    }

    @Test
    fun onlyLongHoldsThatAreNotForTheFingersAreAskedThatWay() {
        val plank = def("core.side_plank", ExerciseKind.TIME, durationS = 30)
        assertTrue(NextTargets.longPlainHold(plank, 30))
        assertTrue(NextTargets.longPlainHold(plank, NextTargets.LONG_HOLD_S))
        // A short strength hold keeps its seconds, whatever the exercise usually plans.
        assertTrue(!NextTargets.longPlainHold(plank, NextTargets.LONG_HOLD_S - 1))
        // Without a planned time the exercise's own counts.
        assertTrue(NextTargets.longPlainHold(plank, null))
        assertTrue(!NextTargets.longPlainHold(def("core.l_sit", ExerciseKind.TIME, durationS = 10), null))
        // A hang and a timed hold for the fingers are never asked in steps of five seconds.
        assertTrue(!NextTargets.longPlainHold(def("finger.max_hang", ExerciseKind.HANG, listOf(LoadDomain.FINGER), 30), 30))
        assertTrue(!NextTargets.longPlainHold(def("finger.block_hold", ExerciseKind.TIME, listOf(LoadDomain.FINGER), 30), 30))
        assertTrue(!NextTargets.longPlainHold(def("push.push_up", ExerciseKind.REPS), 30))
    }
}
