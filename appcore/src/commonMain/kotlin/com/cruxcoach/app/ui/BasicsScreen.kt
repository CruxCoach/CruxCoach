package com.cruxcoach.app.ui

import com.cruxcoach.app.training.BasicsPresenter
import com.cruxcoach.app.training.BasicsRunState
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.LogbookSummaries
import com.cruxcoach.athlete.logic.TrainingPlaces
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.Sex
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.roundToInt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

class BasicsScreenState(
    val loading: Boolean,
    /** Saved or put off: close the screen. */
    val finished: Boolean,
    /** The gate says: open the basics now (once). */
    val gateOpen: Boolean,
    /** kg | lb and cm | in, for the field suffixes. */
    val massUnit: String,
    val lengthUnit: String,
    /** Prefills for the form; "" when unknown (or hidden numbers for the weight). */
    val weightText: String,
    val heightText: String,
    val birthYearText: String,
    /** FEMALE | MALE | OTHER | "" */
    val sexCode: String,
    /** Font grade ("7a") or "": the athlete's grade, else the logbook's suggestion. */
    val grade: String,
    val logbookGrade: String,
    /** The athlete has not set a grade yet (the logbook line shows while the suggestion stands). */
    val gradeUnset: Boolean,
    /** UNDER_1 | Y1_2 | Y3_5 | OVER_5 | "" */
    val experienceCode: String,
    /** Equipment of the default place (EquipmentV2 names). */
    val equipmentCodes: List<String>,
)

/** "Quick start" (Android `BasicsScreen` + `BasicsGateViewModel`). */
class BasicsScreenModel internal constructor(private val presenter: BasicsPresenter) {
    val currentState: BasicsScreenState get() = map(presenter.state.value)

    fun watch(onState: (BasicsScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    fun load() = presenter.load()
    /** Asks whether the basics should open; the answer arrives as [BasicsScreenState.gateOpen]. */
    fun checkGate() = presenter.checkGate()
    fun consumeGate() = presenter.consumeGate()
    fun close() = presenter.close()

    /** The parsed body weight in kg, or -1 when [text] is empty or implausible (comma or dot decimals). */
    fun weightKg(text: String): Double {
        val v = parse(text) ?: return -1.0
        return Units.massFromDisplay(v, units()).takeIf { it in TodayScreenModel.PLAUSIBLE_MIN_KG..TodayScreenModel.PLAUSIBLE_MAX_KG } ?: -1.0
    }

    fun heightCm(text: String): Double {
        val v = parse(text) ?: return -1.0
        return Units.lengthFromDisplay(v, units()).takeIf { it in PLAUSIBLE_MIN_CM..PLAUSIBLE_MAX_CM } ?: -1.0
    }

    /** -1 unless a year 8 to 100 years back. */
    fun birthYear(text: String): Int {
        val thisYear = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).year
        return text.trim().toIntOrNull()?.takeIf { it in (thisYear - 100)..(thisYear - 8) } ?: -1
    }

    /**
     * Saves; fields left empty or invalid keep what was there. [equipmentCodes] empty = not asked.
     * Codes are the enum names; "" = no answer.
     */
    fun save(weightText: String, heightText: String, birthYearText: String, sexCode: String, grade: String,
             experienceCode: String, equipmentCodes: List<String>) {
        val equipment = equipmentCodes.mapNotNull { c -> EquipmentV2.entries.firstOrNull { it.name == c } }.toSet()
        presenter.save(
            weightKg(weightText).takeIf { it > 0 }, heightCm(heightText).takeIf { it > 0 }, birthYear(birthYearText).takeIf { it > 0 },
            Sex.entries.firstOrNull { it.name == sexCode }, grade.ifBlank { null },
            ExperienceBand.entries.firstOrNull { it.name == experienceCode },
            equipment.takeIf { it.isNotEmpty() }?.plus(TodayScreenModel.ALWAYS_THERE),
        )
    }

    fun skip() = presenter.skip()

    // ── Grade (Android `GradeStepper`) ──
    val gradeScale: List<String> get() = LogbookSummaries.fontScale
    fun gradeLabel(grade: String): String = Tx.gradeWithV(grade)

    /** One step down (-1) or up (+1); from no grade the first step lands on the logbook's grade, else 6a. */
    fun stepGrade(current: String, direction: Int): String {
        val scale = LogbookSummaries.fontScale
        val index = current.takeIf { it.isNotBlank() }?.let { scale.indexOf(it.lowercase()) }?.takeIf { it >= 0 }
        val start = presenter.state.value.logbookDifficulty?.let { LogbookSummaries.fontOf(it) }?.let { scale.indexOf(it) }?.takeIf { it >= 0 }
            ?: scale.indexOf("6a").coerceAtLeast(0)
        val target = if (index == null) start else index + direction
        return scale[target.coerceIn(0, scale.lastIndex)]
    }

    private fun units(): UnitSystem = presenter.state.value.profile.units

    private fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private var lastGood: BasicsScreenState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: BasicsRunState): BasicsScreenState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(BasicsRunState())
    }

    private fun mapUnsafe(s: BasicsRunState): BasicsScreenState {
        val p = s.profile
        val units = p.units
        return BasicsScreenState(
            loading = s.loading,
            finished = s.finished,
            gateOpen = s.gateOpen,
            massUnit = Units.massUnit(units),
            lengthUnit = Units.lengthUnit(units),
            weightText = s.weightKg?.takeIf { !p.hideBodyNumbers }?.let { editable(Units.massToDisplay(it, units)) } ?: "",
            heightText = s.heightCm?.let { editable(Units.lengthToDisplay(it, units)) } ?: "",
            birthYearText = p.birthYear?.toString() ?: "",
            sexCode = p.sex?.name ?: "",
            grade = p.coach.currentGrade ?: s.logbookGrade ?: "",
            logbookGrade = s.logbookGrade ?: "",
            gradeUnset = p.coach.currentGrade == null,
            experienceCode = p.coach.experience?.name ?: "",
            equipmentCodes = (TrainingPlaces.default(p)?.equipment ?: emptySet()).map { it.name }.sorted(),
        )
    }

    /** One decimal at most, a dot as separator: a value the athlete edits in a text field. */
    private fun editable(v: Double): String {
        val rounded = (v * 10).roundToInt() / 10.0
        return if (rounded == rounded.roundToInt().toDouble()) rounded.roundToInt().toString() else rounded.toString()
    }

    companion object {
        /** Plausible heights (Android `PLAUSIBLE_HEIGHT_CM`). */
        const val PLAUSIBLE_MIN_CM = 100.0
        const val PLAUSIBLE_MAX_CM = 250.0
    }
}

/**
 * The equipment editor's choices (Android `EquipmentEditor`): presets for home, gym and travel, every
 * piece but the floor and mat as a chip, and the pick-up block pair coming with a single block.
 */
class BasicsEquipment {
    /** What can be chosen, in Android's order. */
    val codes: List<String> get() = EquipmentV2.entries.filter { it !in TodayScreenModel.ALWAYS_THERE }.map { it.name }

    fun label(code: String): TrText = EquipmentV2.entries.firstOrNull { it.name == code }?.let { Tx.t(Tx.equipmentKey(it)) } ?: Tx.EMPTY

    /** home | gym | travel */
    fun preset(code: String): List<String> = (when (code) {
        "home" -> PRESET_HOME
        "gym" -> PRESET_GYM
        "travel" -> PRESET_TRAVEL
        else -> emptySet()
    } + TodayScreenModel.ALWAYS_THERE).map { it.name }.sorted()

    /** The preset [selected] equals ("" when none): the chip that shows as chosen. */
    fun presetOf(selected: List<String>): String {
        val set = selected.mapNotNull { c -> EquipmentV2.entries.firstOrNull { it.name == c } }.toSet() - TodayScreenModel.ALWAYS_THERE
        return when (set) {
            PRESET_HOME -> "home"
            PRESET_GYM -> "gym"
            PRESET_TRAVEL -> "travel"
            else -> ""
        }
    }

    /** Toggles [code]; the second pick-up block comes with the first, and dropping the first drops both. */
    fun toggle(selected: List<String>, code: String): List<String> {
        val set = selected.mapNotNull { c -> EquipmentV2.entries.firstOrNull { it.name == c } }.toSet()
        val e = EquipmentV2.entries.firstOrNull { it.name == code } ?: return selected
        val next = when {
            e in set && e == EquipmentV2.PICKUP_BLOCK -> set - e - EquipmentV2.PICKUP_BLOCK_PAIR
            e in set -> set - e
            e == EquipmentV2.PICKUP_BLOCK_PAIR -> set + e + EquipmentV2.PICKUP_BLOCK + TodayScreenModel.ALWAYS_THERE
            else -> set + e + TodayScreenModel.ALWAYS_THERE
        }
        return next.map { it.name }.sorted()
    }

    private companion object {
        val PRESET_HOME = setOf(EquipmentV2.HANGBOARD, EquipmentV2.PULL_UP_BAR, EquipmentV2.BANDS, EquipmentV2.DUMBBELL)
        val PRESET_GYM = setOf(EquipmentV2.HANGBOARD, EquipmentV2.PULL_UP_BAR, EquipmentV2.RINGS, EquipmentV2.DUMBBELL,
            EquipmentV2.KETTLEBELL, EquipmentV2.BARBELL, EquipmentV2.PLATES, EquipmentV2.BANDS, EquipmentV2.BENCH, EquipmentV2.BOX,
            EquipmentV2.CABLE, EquipmentV2.WALL, EquipmentV2.BOARD, EquipmentV2.CAMPUS_BOARD, EquipmentV2.FOAM_ROLLER, EquipmentV2.DIP_BARS)
        val PRESET_TRAVEL = setOf(EquipmentV2.BANDS)
    }
}
