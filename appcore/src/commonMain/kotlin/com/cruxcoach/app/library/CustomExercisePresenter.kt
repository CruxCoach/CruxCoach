package com.cruxcoach.app.library

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.ExerciseGuess
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class CustomExerciseState(
    val name: String = "",
    val category: ExerciseCategoryV2 = ExerciseCategoryV2.PULL,
    val kind: ExerciseKind = ExerciseKind.REPS,
    val load: LoadMode = LoadMode.BODYWEIGHT,
    val unilateral: Boolean = false,
    val equipment: Set<EquipmentV2> = emptySet(),
    val domains: Set<LoadDomain> = emptySet(),
    val note: String = "",
    val showNameError: Boolean = false,
    val saving: Boolean = false,
    val savedSlug: String? = null,
    /** The fields below the name were filled in from it ([ExerciseGuess]); the athlete may change each. */
    val guessed: Boolean = false,
    val failed: Boolean = false,
)

/** Port of Android `CustomExerciseViewModel`: an own exercise, its fields guessed from the name. */
class CustomExercisePresenter(
    private val service: AthleteService,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope = libraryScope(),
) {
    private val _state = MutableStateFlow(CustomExerciseState())
    val state: StateFlow<CustomExerciseState> = _state.asStateFlow()

    private enum class Field { CATEGORY, KIND, LOAD, SIDE, EQUIPMENT, DOMAINS }
    /** Fields the athlete set by hand; the name's guess leaves them alone. */
    private val touched = mutableSetOf<Field>()

    /**
     * Typing the name fills in the rest (owner 2026-10-15: new exercises by
     * typing, like the food entry): "Ringdips mit Zusatzgewicht" → push, reps
     * with added load, rings.
     */
    fun setName(v: String) = _state.update { s ->
        val name = v.take(60)
        val guess = name.takeIf { it.isNotBlank() }?.let { ExerciseGuess.fromText(it) }
            ?: return@update s.copy(name = name, showNameError = false, guessed = false)
        s.copy(
            name = name, showNameError = false, guessed = true,
            category = if (Field.CATEGORY in touched) s.category else guess.category,
            kind = if (Field.KIND in touched) s.kind else guess.kind,
            load = if (Field.LOAD in touched || Field.KIND in touched) s.load else guess.load,
            unilateral = if (Field.SIDE in touched) s.unilateral else guess.unilateral,
            equipment = if (Field.EQUIPMENT in touched) s.equipment else guess.equipment.filterNot { it == EquipmentV2.NONE }.toSet(),
            domains = if (Field.DOMAINS in touched) s.domains else guess.domains.toSet(),
        )
    }

    fun setCategory(c: ExerciseCategoryV2) { touched += Field.CATEGORY; _state.update { it.copy(category = c) } }
    fun setKind(k: ExerciseKind) { touched += Field.KIND; _state.update { it.copy(kind = k, load = ExerciseGuess.defaultLoad(k)) } }
    fun setLoad(l: LoadMode) { touched += Field.LOAD; _state.update { it.copy(load = l) } }
    fun setUnilateral(v: Boolean) { touched += Field.SIDE; _state.update { it.copy(unilateral = v) } }
    fun setNote(v: String) = _state.update { it.copy(note = v.take(300)) }

    fun toggleEquipment(e: EquipmentV2) {
        touched += Field.EQUIPMENT
        _state.update { s -> s.copy(equipment = if (e in s.equipment) s.equipment - e else s.equipment + e) }
    }

    fun toggleDomain(d: LoadDomain) {
        touched += Field.DOMAINS
        _state.update { s -> s.copy(domains = if (d in s.domains) s.domains - d else s.domains + d) }
    }

    fun save() {
        val s = _state.value
        val name = s.name.trim()
        if (name.isEmpty()) { _state.update { it.copy(showNameError = true) }; return }
        if (s.saving) return
        _state.update { it.copy(saving = true) }
        scope.launchGuarded({ _state.update { it.copy(saving = false, failed = true) } }) {
            withContext(io) {
                service.ensureReady()
                val slug = service.createCustomExercise(name, s.category, s.kind, s.load, s.unilateral, s.equipment, s.domains, s.note)
                _state.update { it.copy(saving = false, savedSlug = slug) }
            }
        }
    }

    fun consumeSaved() = _state.update { it.copy(savedSlug = null) }

    fun close() = scope.cancel()
}
