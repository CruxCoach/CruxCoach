package com.cruxcoach.app.ui

import com.cruxcoach.app.library.CustomExercisePresenter
import com.cruxcoach.app.library.CustomExerciseState
import com.cruxcoach.app.library.LibraryKeys
import com.cruxcoach.app.util.watch
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode

class CustomExerciseScreenState(
    val name: String,
    val showNameError: Boolean,
    /** The fields below the name were filled in from it; each can still be changed. */
    val guessed: Boolean,
    val categories: List<LibraryChoiceUi>,
    val kinds: List<LibraryChoiceUi>,
    val loads: List<LibraryChoiceUi>,
    val unilateral: Boolean,
    val equipment: List<LibraryChoiceUi>,
    val domains: List<LibraryChoiceUi>,
    val note: String,
    val saving: Boolean,
    val failed: Boolean,
    /** One-shot: the new exercise's slug, to open its page; "" while not saved. */
    val savedSlug: String,
)

/** An own exercise (Android `CustomExerciseScreen`). */
class CustomExerciseScreenModel internal constructor(private val presenter: CustomExercisePresenter) {

    val currentState: CustomExerciseScreenState get() = map(presenter.state.value)

    fun watch(onState: (CustomExerciseScreenState) -> Unit): Subscription {
        val watch = presenter.state.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun setName(value: String) = presenter.setName(value)
    fun setCategory(code: String) { LibraryKeys.categoryOf(code)?.let { presenter.setCategory(it) } }
    fun setKind(code: String) { LibraryKeys.kindOf(code)?.let { presenter.setKind(it) } }
    fun setLoad(code: String) { LibraryKeys.loadOf(code)?.let { presenter.setLoad(it) } }
    fun setUnilateral(value: Boolean) = presenter.setUnilateral(value)
    fun toggleEquipment(code: String) { LibraryKeys.equipmentOf(code)?.let { presenter.toggleEquipment(it) } }
    fun toggleDomain(code: String) { LibraryKeys.domainOf(code)?.let { presenter.toggleDomain(it) } }
    fun setNote(value: String) = presenter.setNote(value)
    fun save() = presenter.save()
    fun consumeSaved() = presenter.consumeSaved()
    fun close() = presenter.close()

    private fun map(s: CustomExerciseState) = CustomExerciseScreenState(
        name = s.name,
        showNameError = s.showNameError,
        guessed = s.guessed,
        categories = ExerciseCategoryV2.entries.map { LibraryChoiceUi(LibraryKeys.code(it.name), LibraryKeys.category(it), it == s.category) },
        kinds = ExerciseKind.entries.map { LibraryChoiceUi(LibraryKeys.code(it.name), LibraryKeys.kind(it), it == s.kind) },
        loads = LoadMode.entries.map { LibraryChoiceUi(LibraryKeys.code(it.name), LibraryKeys.load(it), it == s.load) },
        unilateral = s.unilateral,
        equipment = EquipmentV2.entries.filterNot { it == EquipmentV2.NONE }
            .map { LibraryChoiceUi(LibraryKeys.code(it.name), LibraryKeys.equipment(it), it in s.equipment) },
        domains = LoadDomain.entries.map { LibraryChoiceUi(LibraryKeys.code(it.name), LibraryKeys.domain(it), it in s.domains) },
        note = s.note,
        saving = s.saving,
        failed = s.failed,
        savedSlug = s.savedSlug ?: "",
    )
}
