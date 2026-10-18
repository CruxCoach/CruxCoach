package com.cruxcoach.app.ui

import com.cruxcoach.app.library.LibraryEditorItem
import com.cruxcoach.app.library.LibraryKeys
import com.cruxcoach.app.library.RoutineEditorPresenter
import com.cruxcoach.app.library.RoutineEditorState
import com.cruxcoach.app.library.catalogLanguage
import com.cruxcoach.app.util.watch
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.Circuits
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.logic.VolumeArea
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.SideMode
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.roundToInt

/** "5 × 5 · +20 kg", "3 × 60 s", "6 × 7 s on / 3 s off × 6 · 20 mm", "warm-up" (Android `routineItemSummary`). */
internal object LibraryItemTexts {
    fun summary(item: RoutineItem, kind: ExerciseKind, load: LoadMode, units: UnitSystem, short: Boolean = false): LibraryText {
        val amount: LibraryText = when (kind) {
            ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> {
                val min = item.repsMin
                val max = item.repsMax ?: min
                val reps = if (min != null && max != null && min != max) "$min–$max" else max?.toString()
                LibraryTexts.literal(reps?.let { "${item.sets} × $it" } ?: "${item.sets} ×")
            }
            ExerciseKind.TIME, ExerciseKind.HANG -> LibraryTexts.join(listOf(
                LibraryTexts.literal("${item.sets} × "),
                LibraryTexts.key("tr_format_seconds", LibraryTexts.int(item.durationS ?: 0)),
            ), "")
            ExerciseKind.INTERVAL -> LibraryTexts.join(listOf(
                LibraryTexts.literal("${item.sets} × "),
                LibraryTexts.key("trw_interval_format", LibraryTexts.int(item.workS ?: 7),
                    LibraryTexts.int(item.restBetweenS ?: 3), LibraryTexts.int(item.repsPerSet ?: 6)),
            ), "")
            ExerciseKind.CLIMB -> item.durationS?.let { LibraryTexts.key("trwo_minutes", LibraryTexts.int(it / 60)) }
                ?: (item.repsPerSet ?: 4).let { LibraryTexts.plural("trp_rounds", it, LibraryTexts.int(it)) }
        }
        if (short) return amount
        val parts = buildList {
            add(amount)
            item.loadKg?.let { kg ->
                when {
                    load == LoadMode.BODYWEIGHT_PLUS && kg < 0 ->
                        add(LibraryTexts.key("trw_load_assisted", LibraryTexts.nested(LibraryTexts.mass(-kg, units))))
                    load == LoadMode.BODYWEIGHT_PLUS -> add(LibraryTexts.mass(kg, units, signed = true))
                    load == LoadMode.EXTERNAL -> add(LibraryTexts.mass(kg, units))
                    else -> Unit
                }
            }
            if (kind == ExerciseKind.HANG || kind == ExerciseKind.INTERVAL) item.edgeMm?.let {
                add(LibraryTexts.key("tr_format_mm", LibraryTexts.text(it.toString())))
            }
            when (item.sides) {
                SideMode.LEFT_ONLY -> add(LibraryTexts.key(LibraryKeys.side(Side.LEFT)))
                SideMode.RIGHT_ONLY -> add(LibraryTexts.key(LibraryKeys.side(Side.RIGHT)))
                SideMode.BOTH -> Unit
            }
            if (item.warmup) add(LibraryTexts.key("trwo_text_warmup"))
        }
        return LibraryTexts.join(parts)
    }
}

/** One ± stepper of an editor row. [field] names it for [RoutineEditorScreenModel.setField]. */
class EditorStepperUi(
    /** sets | repsMin | repsMax | seconds | work | restBetween | repsPerSet | rounds | rest | edge */
    val field: String,
    val labelKey: String,
    val value: Int,
    val step: Int,
    val min: Int,
    val max: Int,
    /** Shown as m:ss. */
    val asClock: Boolean,
)

class EditorItemUi(
    val key: Long,
    val slug: String,
    val name: String,
    val categoryKey: String,
    val isFirst: Boolean,
    val isLast: Boolean,
    /** Member of a circuit: sets and rest come from the circuit card above. */
    val inCircuit: Boolean,
    /** A circuit card stands above this row (its first member). */
    val startsCircuit: Boolean,
    val circuit: Int,
    val circuitRounds: Int,
    val circuitRestS: Int,
    val roundRestS: Int,
    /** Steppers above the load field, then those below it, in Android's order. */
    val steppers: List<EditorStepperUi>,
    val steppersAfterLoad: List<EditorStepperUi>,
    val showLoad: Boolean,
    val loadIsAuto: Boolean,
    val loadText: LibraryText,
    /** The load in display units for the input dialog; meaningful when ![loadIsAuto]. */
    val loadInput: Double,
    /** Body weight plus load: negative means assistance. */
    val loadAdded: Boolean,
    val unilateral: Boolean,
    /** both | left_only | right_only */
    val sidesCode: String,
    val warmup: Boolean,
    val linkedToNext: Boolean,
    val canLink: Boolean,
)

class PickerRowUi(val slug: String, val name: String, val categoryKey: String, val selected: Boolean, val favorite: Boolean)

class TypedLineUi(val name: String, val summary: LibraryText, val isNew: Boolean, val newHint: LibraryText)

class RoutineEditorScreenState(
    val isLoading: Boolean,
    val failed: Boolean,
    val isNew: Boolean,
    val name: String,
    val notes: String,
    val items: List<EditorItemUi>,
    val dirty: Boolean,
    val canSave: Boolean,
    /** One-shot: the workout was saved; close the editor. */
    val saved: Boolean,
    /** "Pulling this week: 4/6 sets · this workout +6". */
    val weeklyLines: List<LibraryText>,
    /** "kg" or "lb", for the load dialog's title. */
    val massUnit: String,
    // Picker
    val pickerQuery: String,
    /** "" = all categories. */
    val pickerCategory: String,
    val pickerFavorites: List<PickerRowUi>,
    val pickerResults: List<PickerRowUi>,
    val pickerSelectedCount: Int,
    // Typed workout
    val typedText: String,
    val typedHasTitle: Boolean,
    val typedTitle: String,
    val typedLines: List<TypedLineUi>,
    val typedApplyLabel: LibraryText,
)

/** The workout editor (Android `RoutineEditorScreen`, `ExercisePickerSheet`, `WorkoutTextSheet`). */
class RoutineEditorScreenModel internal constructor(
    private val presenter: RoutineEditorPresenter,
    language: String,
) {
    private val lang = catalogLanguage(language)

    /** All category codes in Android's order, for the picker's chips. */
    val categoryCodes: List<String> = ExerciseCategoryV2.entries.map { LibraryKeys.code(it.name) }

    val currentState: RoutineEditorScreenState get() = map(presenter.state.value)

    fun watch(onState: (RoutineEditorScreenState) -> Unit): Subscription {
        val watch = presenter.state.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun setName(value: String) = presenter.setName(value)
    fun setNotes(value: String) = presenter.setNotes(value)

    /** Sets one stepper; a typed value outside the stepper's range is ignored (Android's dialog disables Save). */
    fun setField(key: Long, field: String, value: Int) {
        val row = row(key) ?: return
        val stepper = steppersOf(row, presenter.state.value).firstOrNull { it.field == field } ?: return
        if (value !in stepper.min..stepper.max) return
        presenter.updateItem(key) { apply(it, field, value) }
    }

    /** One step down ([up] false) or up, clamped like Android's stepper. */
    fun stepField(key: Long, field: String, up: Boolean) {
        val row = row(key) ?: return
        val stepper = steppersOf(row, presenter.state.value).firstOrNull { it.field == field } ?: return
        val next = (stepper.value + if (up) stepper.step else -stepper.step).coerceIn(stepper.min, stepper.max)
        presenter.updateItem(key) { apply(it, field, next) }
    }

    fun stepLoad(key: Long, up: Boolean) {
        val s = presenter.state.value
        val row = row(key) ?: return
        val mode = s.catalog.fallbackFor(row.item.slug).load
        val step = s.incrementKg.coerceAtLeast(0.25)
        val min = if (mode == LoadMode.BODYWEIGHT_PLUS) -200.0 else 0.0
        val current = row.item.loadKg ?: 0.0
        val next = if (up) (current + step).coerceAtMost(500.0) else (current - step).coerceAtLeast(min)
        presenter.updateItem(key) { it.copy(loadKg = next) }
    }

    /** The load typed in display units ("12,5"); false when it is no valid load. */
    fun setLoadText(key: Long, text: String): Boolean {
        val s = presenter.state.value
        val row = row(key) ?: return false
        val mode = s.catalog.fallbackFor(row.item.slug).load
        val parsed = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
            ?.takeIf { mode == LoadMode.BODYWEIGHT_PLUS || it >= 0 } ?: return false
        val min = if (mode == LoadMode.BODYWEIGHT_PLUS) -200.0 else 0.0
        presenter.updateItem(key) { it.copy(loadKg = Units.massFromDisplay(parsed, s.units).coerceIn(min, 500.0)) }
        return true
    }

    /** "Automatic": the load is planned from the performance value and last time. */
    fun setLoadAuto(key: Long) = presenter.updateItem(key) { it.copy(loadKg = null) }

    fun setSides(key: Long, code: String) {
        val mode = SideMode.entries.firstOrNull { LibraryKeys.code(it.name) == code } ?: return
        presenter.updateItem(key) { it.copy(sides = mode) }
    }

    fun setWarmup(key: Long, warmup: Boolean) = presenter.updateItem(key) { it.copy(warmup = warmup) }
    fun moveUp(key: Long) = presenter.move(key, -1)
    fun moveDown(key: Long) = presenter.move(key, 1)
    fun remove(key: Long) = presenter.remove(key)
    fun link(key: Long) = presenter.link(key)
    fun unlink(key: Long) = presenter.unlink(key)
    fun dissolve(circuit: Int) = presenter.dissolve(circuit)

    fun setCircuitRounds(circuit: Int, rounds: Int) = presenter.setCircuitRounds(circuit, rounds.coerceIn(1, 20))

    fun setCircuitRest(circuit: Int, seconds: Int) {
        val first = presenter.state.value.items.firstOrNull { it.item.circuit == circuit }?.item ?: return
        presenter.setCircuitRests(circuit, seconds.coerceIn(0, 120), first.roundRestS)
    }

    fun setRoundRest(circuit: Int, seconds: Int) {
        val first = presenter.state.value.items.firstOrNull { it.item.circuit == circuit }?.item ?: return
        presenter.setCircuitRests(circuit, first.circuitRestS, seconds.coerceIn(0, 600))
    }

    // Picker
    fun setPickerQuery(query: String) = presenter.setPickerQuery(query)
    /** "" = all categories. */
    fun setPickerCategory(code: String) = presenter.setPickerCategory(LibraryKeys.categoryOf(code))
    fun togglePicked(slug: String) = presenter.togglePicked(slug)
    fun confirmPicker() = presenter.confirmPicker()
    fun resetPicker() = presenter.resetPicker()
    fun toggleFavorite(slug: String) = presenter.toggleFavorite(slug)

    // Typed workout
    fun setTypedText(text: String) = presenter.setTypedText(text)
    fun clearTyped() = presenter.clearTyped()
    /** [joiner]: the localized " & " that joins the first two names of a nameless workout. */
    fun applyTyped(joiner: String) = presenter.applyTyped(lang, joiner)

    fun save() = presenter.save()
    fun close() = presenter.close()

    private fun row(key: Long): LibraryEditorItem? = presenter.state.value.items.firstOrNull { it.key == key }

    private fun apply(item: RoutineItem, field: String, v: Int): RoutineItem = when (field) {
        "sets" -> item.copy(sets = v)
        "repsMin" -> item.copy(repsMin = v, repsMax = maxOf(v, item.repsMax ?: v))
        "repsMax" -> item.copy(repsMax = v, repsMin = minOf(v, item.repsMin ?: v))
        "seconds" -> item.copy(durationS = v)
        "work" -> item.copy(workS = v)
        "restBetween" -> item.copy(restBetweenS = v)
        "repsPerSet", "rounds" -> item.copy(repsPerSet = v)
        "rest" -> item.copy(restS = v)
        "edge" -> item.copy(edgeMm = v)
        else -> item
    }

    /** Android `ItemCard`'s steppers, in order; the load field sits between [before] and [after]. */
    private class Steppers(val before: List<EditorStepperUi>, val after: List<EditorStepperUi>)

    private fun steppersOf(row: LibraryEditorItem, s: RoutineEditorState): List<EditorStepperUi> =
        stepperGroups(row, s).let { it.before + it.after }

    private fun stepperGroups(row: LibraryEditorItem, s: RoutineEditorState): Steppers {
        val item = row.item
        val def = s.catalog.fallbackFor(item.slug)
        val inCircuit = item.circuit != null
        fun st(field: String, key: String, value: Int, step: Int, min: Int, max: Int, clock: Boolean = false) =
            EditorStepperUi(field, key, value, step, min, max, clock)
        val before = buildList {
            if (!inCircuit) add(st("sets", "trwo_field_sets", item.sets, 1, 1, 20))
            when (def.kind) {
                ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> {
                    add(st("repsMin", "trwo_field_reps_min", item.repsMin ?: item.repsMax ?: 5, 1, 1, 100))
                    add(st("repsMax", "trwo_field_reps_max", item.repsMax ?: item.repsMin ?: 8, 1, 1, 100))
                }
                ExerciseKind.TIME, ExerciseKind.HANG -> {
                    val step = if (def.kind == ExerciseKind.TIME) 5 else 1
                    add(st("seconds", "trwo_field_seconds", item.durationS ?: def.defaults.durationS ?: 10, step, 1, 3600))
                }
                ExerciseKind.INTERVAL -> {
                    add(st("work", "trwo_field_work", item.workS ?: def.defaults.workS ?: 7, 1, 1, 120))
                    add(st("restBetween", "trwo_field_rest_between", item.restBetweenS ?: def.defaults.restBetweenS ?: 3, 1, 1, 300))
                    add(st("repsPerSet", "trwo_field_reps_per_set", item.repsPerSet ?: def.defaults.repsPerSet ?: 6, 1, 1, 50))
                }
                ExerciseKind.CLIMB -> add(st("rounds", "trwo_field_rounds", item.repsPerSet ?: def.defaults.rounds ?: 4, 1, 1, 100))
            }
        }
        val after = buildList {
            if (!inCircuit) add(st("rest", "trwo_field_rest", item.restS ?: def.defaults.restS ?: 90, 15, 0, 1800, clock = true))
            if ((def.kind == ExerciseKind.HANG || def.kind == ExerciseKind.INTERVAL) && (item.edgeMm != null || def.defaults.edgeMm != null)) {
                add(st("edge", "trwo_field_edge", item.edgeMm ?: def.defaults.edgeMm ?: 20, 1, 3, 80))
            }
        }
        return Steppers(before, after)
    }

    private fun loadText(loadKg: Double?, mode: LoadMode, units: UnitSystem): LibraryText = when {
        loadKg == null -> LibraryTexts.key("trwo_load_auto_short")
        mode == LoadMode.BODYWEIGHT_PLUS && loadKg < 0 -> LibraryTexts.key("trw_load_assisted", LibraryTexts.nested(LibraryTexts.mass(-loadKg, units)))
        mode == LoadMode.BODYWEIGHT_PLUS && loadKg == 0.0 -> LibraryTexts.key("tr_bodyweight")
        else -> LibraryTexts.mass(loadKg, units, signed = mode == LoadMode.BODYWEIGHT_PLUS)
    }

    private fun itemUi(index: Int, row: LibraryEditorItem, s: RoutineEditorState): EditorItemUi {
        val item = row.item
        val def = s.catalog.fallbackFor(item.slug)
        val circuit = item.circuit
        val previous = s.items.getOrNull(index - 1)?.item
        val next = s.items.getOrNull(index + 1)
        val all = s.items.map { it.item }
        val first = circuit?.let { c -> all.firstOrNull { it.circuit == c } }
        val groups = stepperGroups(row, s)
        return EditorItemUi(
            key = row.key,
            slug = item.slug,
            name = def.name(lang),
            categoryKey = LibraryKeys.category(def.category),
            isFirst = index == 0,
            isLast = index == s.items.lastIndex,
            inCircuit = circuit != null,
            startsCircuit = circuit != null && previous?.circuit != circuit,
            circuit = circuit ?: 0,
            circuitRounds = circuit?.let { Circuits.rounds(all, it) } ?: 0,
            circuitRestS = first?.circuitRestS ?: Circuits.DEFAULT_CIRCUIT_REST_S,
            roundRestS = first?.roundRestS ?: 120,
            steppers = groups.before,
            steppersAfterLoad = groups.after,
            showLoad = (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL) && def.kind != ExerciseKind.CLIMB && !item.test,
            loadIsAuto = item.loadKg == null,
            loadText = loadText(item.loadKg, def.load, s.units),
            loadInput = item.loadKg?.let { Units.massToDisplay(it, s.units) } ?: 0.0,
            loadAdded = def.load == LoadMode.BODYWEIGHT_PLUS,
            unilateral = def.unilateral,
            sidesCode = LibraryKeys.code(item.sides.name),
            warmup = item.warmup,
            linkedToNext = circuit != null && next?.item?.circuit == circuit,
            canLink = next != null && Circuits.eligible(def, item) && Circuits.eligible(s.catalog[next.item.slug], next.item),
        )
    }

    private fun weeklyLine(p: com.cruxcoach.athlete.logic.AreaProgress, adds: Int?): LibraryText {
        val base = LibraryTexts.key(
            if (p.area == VolumeArea.FINGER) "trv_hint_sessions" else "trv_hint_sets",
            LibraryTexts.ref(LibraryKeys.volumeArea(p.area)), LibraryTexts.decimal(p.effective), LibraryTexts.int(p.goal),
        )
        return if (adds != null && adds > 0) LibraryTexts.key("trv_hint_plus", LibraryTexts.nested(base), LibraryTexts.int(adds)) else base
    }

    private fun pickerRow(def: ExerciseDefinition, s: RoutineEditorState) = PickerRowUi(
        slug = def.slug,
        name = def.name(lang),
        categoryKey = LibraryKeys.category(def.category),
        selected = def.slug in s.pickerSelected,
        favorite = def.slug in s.favorites,
    )

    private fun map(s: RoutineEditorState): RoutineEditorScreenState {
        val (favs, results) = RoutineEditorPresenter.pickerRows(s, lang)
        val lines = s.typed.lines
        return RoutineEditorScreenState(
            isLoading = s.loading,
            failed = s.failed,
            isNew = s.routineId == null,
            name = s.name,
            notes = s.notes,
            items = s.items.mapIndexed { i, row -> itemUi(i, row, s) },
            dirty = s.dirty,
            canSave = !s.loading && s.name.isNotBlank() && s.items.isNotEmpty() && !s.saving,
            saved = s.saved,
            weeklyLines = RoutineEditorPresenter.weeklyAdds(s).map { (p, adds) -> weeklyLine(p, adds) },
            massUnit = Units.massUnit(s.units),
            pickerQuery = s.pickerQuery,
            pickerCategory = s.pickerCategory?.let { LibraryKeys.code(it.name) } ?: "",
            pickerFavorites = favs.map { pickerRow(it, s) },
            pickerResults = results.map { pickerRow(it, s) },
            pickerSelectedCount = s.pickerSelected.size,
            typedText = s.typedText,
            typedHasTitle = s.typed.title != null,
            typedTitle = s.typed.title ?: "",
            typedLines = lines.map { line ->
                val kind = line.match?.kind ?: line.guess.kind
                val load = line.match?.load ?: line.guess.load
                TypedLineUi(
                    name = line.match?.name(lang) ?: line.name,
                    summary = LibraryItemTexts.summary(line.item, kind, load, s.units),
                    isNew = line.isNew,
                    newHint = LibraryTexts.key("trwo_text_new", LibraryTexts.ref(LibraryKeys.kind(kind))),
                )
            },
            typedApplyLabel = if (lines.isEmpty()) LibraryTexts.key("trwo_text_apply_none")
            else LibraryTexts.plural("trwo_text_apply", lines.size, LibraryTexts.int(lines.size)),
        )
    }
}
