package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.BoardPlan
import com.cruxcoach.athlete.logic.EnergyBeforeTraining
import com.cruxcoach.athlete.logic.EnergyReport
import com.cruxcoach.athlete.logic.RedsSignal
import com.cruxcoach.athlete.logic.SessionSuggestion
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.domain.playlist.GeneratorType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Holistic audit P2-17: little energy before maximal finger work is named on Today's card – a hint, never a lock. */
class EnergyBeforeTrainingTest {

    private val catalog = ExerciseCatalog(1, listOf(
        ExerciseDefinition("finger.max_hang", ExerciseCategoryV2.FINGER, ExerciseKind.HANG, LoadMode.BODYWEIGHT_PLUS,
            equipment = listOf(EquipmentV2.HANGBOARD), domains = listOf(LoadDomain.FINGER), tags = listOf("strength")),
        ExerciseDefinition("finger.repeaters", ExerciseCategoryV2.FINGER, ExerciseKind.HANG, LoadMode.BODYWEIGHT,
            equipment = listOf(EquipmentV2.HANGBOARD), domains = listOf(LoadDomain.FINGER), tags = listOf("endurance")),
        ExerciseDefinition("warmup.finger_ramp", ExerciseCategoryV2.WARMUP, ExerciseKind.HANG, LoadMode.BODYWEIGHT_PLUS,
            equipment = listOf(EquipmentV2.HANGBOARD), domains = listOf(LoadDomain.FINGER), tags = listOf("strength")),
        ExerciseDefinition("pull.pull_up", ExerciseCategoryV2.PULL, ExerciseKind.REPS, LoadMode.BODYWEIGHT,
            domains = listOf(LoadDomain.ELBOW), tags = listOf("strength")),
    ))

    private fun suggestion(vararg items: RoutineItem, board: BoardPlan? = null) =
        SessionSuggestion(SuggestionFocus.FINGER_STRENGTH, Routine("suggestion", "today", items = items.toList()), emptyList(), 45, boardPlan = board)

    private val lowEnergy = EnergyReport(signals = listOf(RedsSignal.HIGH_DEFICIT), avgIntakeKcal = 1650, avgNeedKcal = 2300, comparedDays = 5)
    private val lowCarbs = EnergyReport(signals = listOf(RedsSignal.LOW_CARBS_ON_TRAINING_DAYS), lowCarbDays = 3, lowCarbPerKg = 2.1)

    @Test
    fun maximalHangsOrALimitBoardSessionAfterLowEnergyGetTheHint() {
        val hangs = suggestion(RoutineItem("finger.max_hang", sets = 5), RoutineItem("pull.pull_up"))
        val hint = EnergyBeforeTraining.hint(lowEnergy, hangs, catalog)!!
        assertEquals(EnergyBeforeTraining.Basis.DEFICIT, hint.basis)
        assertEquals(1650, hint.report.avgIntakeKcal)
        for (type in listOf(GeneratorType.LIMIT, GeneratorType.PROJECTING)) {
            val board = suggestion(RoutineItem("warmup.finger_ramp", warmup = true), board = BoardPlan(type, 60))
            assertEquals(EnergyBeforeTraining.Basis.LOW_CARBS, EnergyBeforeTraining.hint(lowCarbs, board, catalog)?.basis)
        }
        // Both signals: the energy itself comes first.
        val both = lowEnergy.copy(signals = listOf(RedsSignal.LOW_CARBS_ON_TRAINING_DAYS, RedsSignal.HIGH_DEFICIT))
        assertEquals(EnergyBeforeTraining.Basis.DEFICIT, EnergyBeforeTraining.hint(both, hangs, catalog)?.basis)
    }

    @Test
    fun lighterTrainingOrEnoughEnergyGetNoHint() {
        val hangs = suggestion(RoutineItem("finger.max_hang"))
        // Enough energy, or other warnings only.
        assertNull(EnergyBeforeTraining.hint(EnergyReport(), hangs, catalog))
        assertNull(EnergyBeforeTraining.hint(EnergyReport(signals = listOf(RedsSignal.FAST_PACE, RedsSignal.LOW_BMI)), hangs, catalog))
        // Not maximal on the fingers: endurance hangs, a strength hang only as the warm-up, a volume board session, no suggestion.
        assertNull(EnergyBeforeTraining.hint(lowEnergy, suggestion(RoutineItem("finger.repeaters"), RoutineItem("pull.pull_up")), catalog))
        assertNull(EnergyBeforeTraining.hint(lowEnergy, suggestion(RoutineItem("warmup.finger_ramp", warmup = true)), catalog))
        assertNull(EnergyBeforeTraining.hint(lowEnergy, suggestion(board = BoardPlan(GeneratorType.VOLUME, 45)), catalog))
        assertNull(EnergyBeforeTraining.hint(lowEnergy, null, catalog))
    }
}
