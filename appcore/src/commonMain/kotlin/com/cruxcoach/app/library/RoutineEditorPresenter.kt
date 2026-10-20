package com.cruxcoach.app.library

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.catalog.CatalogFilter
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.logic.AreaProgress
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.Circuits
import com.cruxcoach.athlete.logic.VolumeArea
import com.cruxcoach.athlete.logic.WeeklyVolume
import com.cruxcoach.athlete.logic.WorkoutPlanner
import com.cruxcoach.athlete.logic.WorkoutTextParser
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SideMode
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** One editable row; [key] keeps list identity stable while items move (Android `EditorItem`). */
data class LibraryEditorItem(val key: Long, val item: RoutineItem)

data class RoutineEditorState(
    val loading: Boolean = true,
    val routineId: String? = null,
    val createdAt: Long = 0,
    val name: String = "",
    val notes: String = "",
    val items: List<LibraryEditorItem> = emptyList(),
    val dirty: Boolean = false,
    val catalog: ExerciseCatalog = ExerciseCatalog.EMPTY,
    val favorites: Set<String> = emptySet(),
    val saving: Boolean = false,
    val saved: Boolean = false,
    val units: UnitSystem = UnitSystem.METRIC,
    val incrementKg: Double = 1.0,
    /** This week's volume per area, for the hint what this workout adds (comparison review #13). */
    val weekly: List<AreaProgress> = emptyList(),
    // The exercise picker (Android `ExercisePickerSheet`'s remembered state).
    val pickerQuery: String = "",
    val pickerCategory: ExerciseCategoryV2? = null,
    val pickerSelected: List<String> = emptyList(),
    // "Type a workout" (Android `WorkoutTextSheet`).
    val typedText: String = "",
    val typed: WorkoutTextParser.Result = WorkoutTextParser.Result(null, emptyList()),
    /** The text [typed] was read from; null before the first read. Differs from [typedText] while a read is due. */
    val typedRead: String? = null,
    val failed: Boolean = false,
)

/**
 * Port of Android `RoutineEditorViewModel`: an own workout's exercises with
 * sets, reps, time, load and rest, circuits, the multi-select picker and
 * typed workouts.
 */
class RoutineEditorPresenter(
    private val service: AthleteService,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope = libraryScope(),
    /** Where typed text is read: off the main thread, like Android's `Dispatchers.Default`. */
    reader: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(RoutineEditorState())
    val state: StateFlow<RoutineEditorState> = _state.asStateFlow()
    private val typedReader = TypedWorkoutReader(scope, reader, { _state.value.catalog }) { read ->
        // A read of a text that is no longer in the field (cleared, or typed on) is never shown.
        _state.update { s -> if (s.typedText == read.text) s.copy(typed = read.result, typedRead = read.text) else s }
    }
    private var loaded = false
    private var nextKey = 0L
    private val fail: (Throwable) -> Unit = { _state.update { s -> s.copy(loading = false, saving = false, failed = true) } }

    /** [defaultName] is the localized starter-routine name when copying one. */
    fun load(routineId: String?, from: String?, defaultName: String?) {
        if (loaded) return
        loaded = true
        scope.launchGuarded(fail) {
            withContext(io) {
                service.ensureReady()
                val repo = service.repo
                val catalog = service.catalogStore.catalog.value
                val existing = routineId?.let { id -> repo.routines().firstOrNull { it.id == id } }
                val draft = if (from != null && from.startsWith("draft:")) service.takeEditorDraft() else null
                val (name, notes, items) = when {
                    existing != null -> Triple(existing.name, existing.notes.orEmpty(), existing.items)
                    from != null && from.startsWith("builtin:") -> {
                        val builtin = BuiltinRoutines.byKey(from.removePrefix("builtin:"))
                        Triple(defaultName.orEmpty(), "", builtin?.items.orEmpty())
                    }
                    draft != null -> Triple(draft.name, draft.notes.orEmpty(), draft.items)
                    from != null && (from.startsWith("ex:") || from.startsWith("draft:")) -> {
                        // Also the fallback when the draft is gone (process restart): catalogue defaults.
                        val slugs = from.substringAfter(':').split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        Triple("", "", slugs.mapNotNull { slug -> catalog[slug]?.let { WorkoutPlanner.itemFor(it) } })
                    }
                    else -> Triple("", "", emptyList())
                }
                _state.value = RoutineEditorState(
                    loading = false,
                    routineId = existing?.id,
                    createdAt = existing?.createdAt ?: 0L,
                    name = name,
                    notes = notes,
                    items = items.map { LibraryEditorItem(nextKey++, it) },
                    catalog = catalog,
                    favorites = repo.favorites(),
                    units = repo.profile().units,
                    incrementKg = repo.profile().smallestIncrementKg,
                    weekly = runCatching { service.weeklyProgress() }.getOrDefault(emptyList()),
                )
                repo.observeFavorites().collect { fav -> _state.update { it.copy(favorites = fav) } }
            }
        }
    }

    fun setName(v: String) = _state.update { it.copy(name = v.take(80), dirty = true) }
    fun setNotes(v: String) = _state.update { it.copy(notes = v.take(500), dirty = true) }

    fun updateItem(key: Long, transform: (RoutineItem) -> RoutineItem) = _state.update { s ->
        s.copy(items = s.items.map { if (it.key == key) it.copy(item = transform(it.item)) else it }, dirty = true)
    }

    fun move(key: Long, delta: Int) = _state.update { s ->
        val index = s.items.indexOfFirst { it.key == key }
        val target = index + delta
        if (index < 0 || target !in s.items.indices) return@update s
        val list = s.items.toMutableList()
        val moved = list.removeAt(index)
        list.add(target, moved)
        s.copy(items = circuits(list, s), dirty = true)
    }

    fun remove(key: Long) = _state.update { s -> s.copy(items = circuits(s.items.filterNot { it.key == key }, s), dirty = true) }

    // ── Circuits (owner 2026-10-17: one set of each in turn, round after round) ──

    /** Applies a circuit change to the routine items, keeping each row's key. */
    private fun circuits(
        rows: List<LibraryEditorItem>,
        s: RoutineEditorState,
        change: (List<RoutineItem>) -> List<RoutineItem> = { Circuits.normalize(it, s.catalog) },
    ): List<LibraryEditorItem> {
        val items = change(rows.map { it.item })
        return if (items.size == rows.size) rows.mapIndexed { i, row -> row.copy(item = items[i]) } else rows
    }

    /** Joins the row with the next one (or the next one's circuit). */
    fun link(key: Long) = _state.update { s ->
        val index = s.items.indexOfFirst { it.key == key }
        if (index < 0 || index >= s.items.lastIndex) return@update s
        s.copy(items = circuits(s.items, s) { Circuits.link(it, index, s.catalog) }, dirty = true)
    }

    /** Splits the circuit after the row. */
    fun unlink(key: Long) = _state.update { s ->
        val index = s.items.indexOfFirst { it.key == key }
        if (index < 0) return@update s
        s.copy(items = circuits(s.items, s) { Circuits.unlink(it, index, s.catalog) }, dirty = true)
    }

    fun dissolve(circuit: Int) = _state.update { s ->
        s.copy(items = circuits(s.items, s) { Circuits.dissolve(it, circuit, s.catalog) }, dirty = true)
    }

    fun setCircuitRounds(circuit: Int, rounds: Int) = _state.update { s ->
        s.copy(items = circuits(s.items, s) { Circuits.setRounds(it, circuit, rounds) }, dirty = true)
    }

    fun setCircuitRests(circuit: Int, circuitRestS: Int?, roundRestS: Int?) = _state.update { s ->
        s.copy(items = circuits(s.items, s) { Circuits.setRests(it, circuit, circuitRestS, roundRestS) }, dirty = true)
    }

    fun addSlugs(slugs: List<String>) = _state.update { s ->
        val added = slugs.mapNotNull { slug -> s.catalog[slug]?.let { LibraryEditorItem(nextKey++, WorkoutPlanner.itemFor(it)) } }
        s.copy(items = s.items + added, dirty = s.dirty || added.isNotEmpty())
    }

    // ── The picker: favourites first, search over German and English names and aliases ──

    fun setPickerQuery(q: String) = _state.update { it.copy(pickerQuery = q) }
    fun setPickerCategory(c: ExerciseCategoryV2?) = _state.update { it.copy(pickerCategory = c) }
    fun togglePicked(slug: String) = _state.update { s ->
        s.copy(pickerSelected = if (slug in s.pickerSelected) s.pickerSelected - slug else s.pickerSelected + slug)
    }
    fun resetPicker() = _state.update { it.copy(pickerQuery = "", pickerCategory = null, pickerSelected = emptyList()) }

    fun confirmPicker() {
        val slugs = _state.value.pickerSelected
        addSlugs(slugs)
        resetPicker()
    }

    // ── Typed workouts ("Klimmzüge 5x5 +20 kg, Plank 3x60s") ──

    /**
     * The field's text; it is read off the main thread, at once the first time and then a moment after the last
     * keystroke (Android `WorkoutTextSheet`), so [RoutineEditorState.typed] follows [RoutineEditorState.typedText].
     */
    fun setTypedText(text: String) {
        val t = text.take(WorkoutTextParser.MAX_CHARS)
        _state.update { it.copy(typedText = t) }
        typedReader.read(t)
    }

    fun clearTyped() {
        typedReader.reset()
        _state.update { it.copy(typedText = "", typed = WorkoutTextParser.Result(null, emptyList()), typedRead = null) }
    }

    /** Applies the typed text; [joiner] is the localized " & " between the first two names that name a nameless workout. */
    fun applyTyped(lang: String, joiner: String) {
        // Typed a moment ago and not read yet: read it now, so "Add" takes exactly what is in the field.
        val result = typedReader.resultFor(_state.value.typedText)
        val fallback = result.lines.map { it.match?.name(lang) ?: it.name }.distinct().take(2).joinToString(joiner)
        clearTyped()
        addFromText(result, fallback)
    }

    /**
     * Adds what was typed: catalogue exercises as they are, unknown names as
     * new exercises of their own (created once per name). A title from the
     * text names a workout that has no name yet.
     */
    fun addFromText(result: WorkoutTextParser.Result, fallbackName: String) {
        if (result.lines.isEmpty()) return
        scope.launchGuarded(fail) {
            withContext(io) {
                service.ensureReady()
                val typed = service.resolveTyped(result.lines)
                val catalog = service.catalogStore.catalog.value
                _state.update { s ->
                    // Typed circuits get keys of their own, so they never merge with a circuit already in the editor.
                    val offset = s.items.mapNotNull { it.item.circuit }.maxOrNull() ?: 0
                    val all = Circuits.normalize(
                        s.items.map { it.item } + typed.map { it.copy(circuit = it.circuit?.plus(offset)) }, catalog)
                    val kept = s.items.mapIndexed { i, e -> e.copy(item = all[i]) }
                    s.copy(
                        items = kept + all.drop(s.items.size).map { LibraryEditorItem(nextKey++, it) },
                        catalog = catalog,
                        name = s.name.ifBlank { result.title ?: fallbackName },
                        dirty = true,
                    )
                }
            }
        }
    }

    fun toggleFavorite(slug: String) {
        val fav = slug !in _state.value.favorites
        scope.launchGuarded(fail) { withContext(io) { service.ensureReady(); service.repo.setFavorite(slug, fav) } }
    }

    fun save() {
        val s = _state.value
        if (s.name.isBlank() || s.items.isEmpty() || s.saving) return
        _state.update { it.copy(saving = true) }
        scope.launchGuarded(fail) {
            withContext(io) {
                service.ensureReady()
                val now = nowMs()
                service.repo.saveRoutine(Routine(
                    id = s.routineId ?: service.repo.newId(),
                    name = s.name.trim(),
                    notes = s.notes.trim().ifBlank { null },
                    items = s.items.map { it.item },
                    createdAt = if (s.routineId != null && s.createdAt > 0) s.createdAt else now,
                    updatedAt = now,
                ))
                _state.update { it.copy(saving = false, dirty = false, saved = true) }
            }
        }
    }

    fun close() = scope.cancel()

    internal companion object {
        /** Favourites (when not searching) above the search results, as the sheet lists them. */
        fun pickerRows(s: RoutineEditorState, lang: String): Pair<List<ExerciseDefinition>, List<ExerciseDefinition>> {
            val filter = CatalogFilter(categories = setOfNotNull(s.pickerCategory))
            val results = s.catalog.search(s.pickerQuery, lang, filter)
            val favDefs = if (s.pickerQuery.isNotBlank()) emptyList()
            else s.favorites.mapNotNull { s.catalog[it] }.filter { filter.matches(it) }.sortedBy { it.name(lang) }
            return favDefs to results
        }

        /**
         * Per area this workout trains: where the week stands and what the workout adds ("Pulling this week:
         * 4/6 sets · this workout +6"), counted like the weekly targets – work sets per side, a finger session as one.
         */
        fun weeklyAdds(state: RoutineEditorState): List<Pair<AreaProgress, Int?>> {
            if (state.weekly.isEmpty()) return emptyList()
            val adds = state.items.map { it.item }.filter { !it.warmup && !it.test }.mapNotNull { item ->
                val def = state.catalog.fallbackFor(item.slug)
                WeeklyVolume.areaOf(def)?.let { it to item.sets * (if (def.unilateral && item.sides == SideMode.BOTH) 2 else 1) }
            }.groupBy({ it.first }, { it.second }).mapValues { (area, sets) -> if (area == VolumeArea.FINGER) 1 else sets.sum() }
            return state.weekly.filter { p -> p.area in adds && !p.target.isOff && p.goal > 0 }.map { p -> p to adds[p.area] }
        }
    }
}
