// Port of BasicsViewModel and BasicsGateViewModel in androidApp/.../ui/training/basics/BasicsScreen.kt (0e1bad0c6).
package com.cruxcoach.app.training

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.TrainingPlaces
import com.cruxcoach.athlete.model.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * The basics on the first visit to Training or Nutrition (owner 2026-10-09:
 * "the most important basics, so the whole thing works at all"): body data
 * for targets, energy and loads; climbing level, experience and equipment for
 * suggestions and start loads. Everything is optional and editable later;
 * the longer coach setup stays a step of Today's checklist.
 */

internal data class BasicsRunState(
    val loading: Boolean = true,
    val profile: AthleteProfile = AthleteProfile(),
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    /** Working grade from the board logbook, suggested for the climbing level. */
    val logbookGrade: String? = null,
    val logbookDifficulty: Double? = null,
    /** Saved or put off: the screen closes. */
    val finished: Boolean = false,
    /** The gate's verdict: the basics should open now (asked once, while something essential is missing). */
    val gateOpen: Boolean = false,
)

internal class BasicsPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(BasicsRunState())
    val state: StateFlow<BasicsRunState> = _state.asStateFlow()

    /** Loads what is known; the form prefills from it. */
    fun load() {
        scope.launch(io) {
            service.ensureReady()
            val logbook = runCatching { service.logbookSummary() }.getOrNull()?.takeIf { it.hasGrades }
            _state.update {
                it.copy(loading = false, profile = service.repo.profile(), weightKg = service.currentBodyweight(), heightCm = service.heightCm(),
                    logbookGrade = logbook?.workingGrade, logbookDifficulty = logbook?.workingDifficulty)
            }
        }
    }

    /**
     * The gate (Android `BasicsGateViewModel`): opens the basics once, on the first visit to Training or
     * Nutrition, while something essential is missing. Marked before opening: backing out never brings it back.
     */
    fun checkGate() {
        scope.launch(io) {
            service.ensureReady()
            val p = service.repo.profile()
            val missing = service.currentBodyweight() == null || service.heightCm() == null || p.birthYear == null ||
                !p.equipmentConfigured || p.coach.currentGrade == null
            if (!p.basicsAsked && missing) {
                service.repo.updateProfile { it.copy(basicsAsked = true) }
                _state.update { it.copy(gateOpen = true) }
            }
        }
    }

    fun consumeGate() = _state.update { it.copy(gateOpen = false) }

    /** Saves what was entered; values left empty stay as they were. */
    fun save(
        weightKg: Double?, heightCm: Double?, birthYear: Int?, sex: Sex?,
        grade: String?, experience: ExperienceBand?, equipment: Set<EquipmentV2>?,
    ) {
        scope.launch(io) {
            service.ensureReady()
            val repo = service.repo
            val today = service.today()
            val now = nowMs()
            if (weightKg != null && weightKg != _state.value.weightKg) {
                repo.saveMeasurement(BodyMeasurement(today.toString(), BodyMetric.WEIGHT.key, weightKg, "kg", now))
            }
            if (heightCm != null && heightCm != _state.value.heightCm) {
                repo.saveMeasurement(BodyMeasurement(today.toString(), BodyMetric.HEIGHT.key, heightCm, "cm", now))
            }
            repo.updateProfile { p ->
                // The equipment asked here is the default place's (usually at home).
                val placed = equipment?.let {
                    TrainingPlaces.withEquipment(p, TrainingPlaces.default(p)?.id, it, today.toString())
                } ?: p
                placed.copy(
                    birthYear = birthYear ?: p.birthYear,
                    sex = sex ?: p.sex,
                    basicsAsked = true,
                    coach = p.coach.copy(
                        currentGrade = grade ?: p.coach.currentGrade,
                        experience = experience ?: p.coach.experience,
                        // The coach's age guard rails (youth, 40+) follow the birth year.
                        ageBand = birthYear?.let { ageBandFor(today.year - it) } ?: p.coach.ageBand,
                    ),
                )
            }
            _state.update { it.copy(finished = true) }
        }
    }

    /** "Später": asked once, never pushed again; the checklist and hints still offer every value. */
    fun skip() {
        scope.launch(io) {
            service.ensureReady()
            service.repo.updateProfile { it.copy(basicsAsked = true) }
            _state.update { it.copy(finished = true) }
        }
    }

    fun close() = scope.cancel()

    companion object {
        fun ageBandFor(age: Int): AgeBand = when {
            age < 16 -> AgeBand.UNDER_16
            age < 18 -> AgeBand.Y16_17
            age < 40 -> AgeBand.Y18_39
            age < 55 -> AgeBand.Y40_54
            else -> AgeBand.Y55_PLUS
        }
    }
}
