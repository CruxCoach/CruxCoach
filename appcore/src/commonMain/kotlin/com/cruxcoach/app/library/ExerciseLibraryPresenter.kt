package com.cruxcoach.app.library

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.athlete.catalog.CatalogFilter
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class ExerciseLibraryState(
    val loading: Boolean = true,
    val catalog: ExerciseCatalog = ExerciseCatalog.EMPTY,
    val profile: AthleteProfile = AthleteProfile(),
    val injuries: List<Injury> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val query: String = "",
    val categories: Set<ExerciseCategoryV2> = emptySet(),
    val mineOnly: Boolean = false,
    val withoutClimbing: Boolean = false,
    val fingerFree: Boolean = false,
    val favoritesOnly: Boolean = false,
    /** True while the climbing/finger toggles still hold the injury defaults. */
    val injuryDefaultsApplied: Boolean = false,
    /** One-shot events for the screen. */
    val picked: Boolean = false,
    val pickRejected: Boolean = false,
    /** The "what do you have?" sheet's selection while it is open; null when closed. */
    val equipmentDraft: Set<EquipmentV2>? = null,
    val failed: Boolean = false,
) {
    val filter: CatalogFilter get() = CatalogFilter(
        categories = categories,
        ownedEquipment = if (mineOnly && profile.equipmentConfigured) profile.equipment + EquipmentV2.NONE else null,
        withoutClimbing = withoutClimbing,
        fingerFreeOnly = fingerFree,
        favoritesOnly = favoritesOnly,
        favorites = favorites,
    )
}

/** Port of Android `ExerciseCatalogViewModel`: the exercise library with its filters, favourites and pick mode. */
class ExerciseLibraryPresenter(
    private val service: AthleteService,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope = libraryScope(),
) {
    private val _state = MutableStateFlow(ExerciseLibraryState())
    val state: StateFlow<ExerciseLibraryState> = _state.asStateFlow()
    private var userTouchedSafetyFilters = false
    /** "My equipment" starts on once equipment is set up, so the list shows what the athlete can do; a tap is theirs. */
    private var userTouchedMine = false
    private val fail: (Throwable) -> Unit = { _state.update { s -> s.copy(loading = false, failed = true) } }

    init {
        scope.launchGuarded(fail) {
            withContext(io) {
                service.ensureReady()
                val repo = service.repo
                combine(
                    service.catalogStore.catalog,
                    repo.observeProfile(),
                    repo.observeActiveInjuries(),
                    repo.observeFavorites(),
                ) { catalog, profile, injuries, favorites -> Quad(catalog, profile, injuries, favorites) }
                    .collect { (catalog, profile, injuries, favorites) ->
                        _state.update { s ->
                            // An injury adapts the filters once, until the athlete changes them.
                            val pauseClimbing = injuries.any { it.climbingPaused }
                            // Only a finger injury on both hands hides finger work: with one hand
                            // hurt, one-arm work on the healthy hand stays visible and the row
                            // says "healthy side only".
                            val fingerInjury = injuries.any {
                                it.region == InjuryRegion.FINGER && (it.side == null || it.side == InjurySide.BOTH)
                            }
                            val applyDefaults = !userTouchedSafetyFilters && (pauseClimbing || fingerInjury)
                            s.copy(
                                loading = false,
                                catalog = catalog,
                                profile = profile,
                                injuries = injuries,
                                favorites = favorites,
                                mineOnly = if (userTouchedMine) s.mineOnly else profile.equipmentConfigured,
                                withoutClimbing = if (applyDefaults) pauseClimbing else s.withoutClimbing,
                                fingerFree = if (applyDefaults) fingerInjury else s.fingerFree,
                                injuryDefaultsApplied = applyDefaults || (s.injuryDefaultsApplied && !userTouchedSafetyFilters),
                            )
                        }
                    }
            }
        }
    }

    private data class Quad(val catalog: ExerciseCatalog, val profile: AthleteProfile, val injuries: List<Injury>, val favorites: Set<String>)

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun toggleCategory(c: ExerciseCategoryV2?) = _state.update { s ->
        s.copy(categories = when {
            c == null -> emptySet()
            c in s.categories -> s.categories - c
            else -> s.categories + c
        })
    }

    fun toggleMine() { userTouchedMine = true; _state.update { it.copy(mineOnly = !it.mineOnly) } }

    /** Equipment set up from the "my equipment" hint; the filter then applies at once. */
    fun saveEquipment(equipment: Set<EquipmentV2>) = io {
        service.savePlaceEquipment(null, equipment)
        _state.update { it.copy(mineOnly = true) }
    }

    /** Opens the equipment sheet from the "my equipment" hint; Android starts it empty. */
    fun openEquipmentSheet() = _state.update { it.copy(equipmentDraft = emptySet()) }

    /** Android `toggleEquipment`: the second pick-up block comes with the first, and dropping the first drops both. */
    fun toggleEquipmentDraft(e: EquipmentV2) = _state.update { s ->
        val selected = s.equipmentDraft ?: return@update s
        s.copy(equipmentDraft = when {
            e in selected && e == EquipmentV2.PICKUP_BLOCK -> selected - e - EquipmentV2.PICKUP_BLOCK_PAIR
            e in selected -> selected - e
            e == EquipmentV2.PICKUP_BLOCK_PAIR -> selected + e + EquipmentV2.PICKUP_BLOCK + ALWAYS_THERE
            else -> selected + e + ALWAYS_THERE
        })
    }

    /** Closing the sheet keeps what was picked, like Android's sheet (it saves on every change). */
    fun closeEquipmentSheet() {
        val draft = _state.value.equipmentDraft ?: return
        _state.update { it.copy(equipmentDraft = null) }
        if (draft.isNotEmpty()) saveEquipment(draft + ALWAYS_THERE)
    }

    fun toggleFavorites() = _state.update { it.copy(favoritesOnly = !it.favoritesOnly) }

    fun toggleWithoutClimbing() {
        userTouchedSafetyFilters = true
        _state.update { it.copy(withoutClimbing = !it.withoutClimbing, injuryDefaultsApplied = false) }
    }

    fun toggleFingerFree() {
        userTouchedSafetyFilters = true
        _state.update { it.copy(fingerFree = !it.fingerFree, injuryDefaultsApplied = false) }
    }

    fun toggleFavorite(slug: String) {
        val favorite = slug !in _state.value.favorites
        io { service.repo.setFavorite(slug, favorite) }
    }

    /** "Never suggest" — the exercise stays findable here. */
    fun toggleExcluded(slug: String) {
        val excluded = slug !in _state.value.profile.excludedExercises
        io { service.setExcluded(slug, excluded) }
    }

    /** Pick mode: appends the exercise to the open training [workoutId]. */
    fun pick(workoutId: String, slug: String) = io {
        val ok = service.addExercise(workoutId, slug)
        _state.update { if (ok) it.copy(picked = true) else it.copy(pickRejected = true) }
    }

    fun consumeEvents() = _state.update { it.copy(picked = false, pickRejected = false) }

    fun close() = scope.cancel()

    private companion object {
        val ALWAYS_THERE = setOf(EquipmentV2.NONE, EquipmentV2.MAT)
    }

    private fun io(block: suspend () -> Unit) {
        scope.launchGuarded(fail) { withContext(io) { service.ensureReady(); block() } }
    }
}
