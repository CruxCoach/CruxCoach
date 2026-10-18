package com.cruxcoach.app.ui

import com.cruxcoach.app.progress.AthleteSettingsPresenter
import com.cruxcoach.app.progress.AthleteSettingsRunState
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.logic.TrainingPlaces
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.PlaceKind
import com.cruxcoach.athlete.model.Sex
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.abs
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** One row of the settings overview with its current value. */
class SettingsRowUi(
    /** profile | about | equipment | nutrition | reminders | timer | display | connections | climbingDays | excluded */
    val code: String,
    val title: TrText,
    val summary: TrText,
    /** False: the page is not on iPhone yet (Swift says so instead of opening it). */
    val available: Boolean,
)

/** One thing the coach learned, with what "Forget" needs. */
class LearnedRowUi(
    /** weekday | level | kept | liked | less | ownLoad */
    val kind: String,
    /** The ISO weekday, the focus name, the event id, the exercise slug or the value id. */
    val key: String,
    val title: TrText,
    /** Empty without. */
    val detail: TrText,
)

class SettingsExcludedUi(val slug: String, val name: String)

class SettingsChoiceUi(val code: String, val label: TrText, val selected: Boolean)

class SettingsPlaceUi(
    val id: String,
    val label: TrText,
    /** The athlete's own name; empty shows the kind. */
    val name: String,
    val kindCode: String,
    val isDefault: Boolean,
    val isToday: Boolean,
    /** EquipmentV2 names. */
    val equipmentCodes: List<String>,
)

class AthleteSettingsScreenState(
    val loaded: Boolean,
    val unitsCode: String,
    val rows: List<SettingsRowUi>,
    // ── Goal and week ──
    val coachGoal: TrText,
    val weeklyGoal: Int,
    val weeklyGoalText: TrText,
    // ── About you ──
    val weightText: TrText,
    val hasHeight: Boolean,
    /** In the athlete's units (cm or in). */
    val heightDisplay: Double,
    val heightUnit: String,
    /** 0 = none. */
    val birthYear: Int,
    val sexes: List<SettingsChoiceUi>,
    // ── Places and equipment ──
    val places: List<SettingsPlaceUi>,
    val placeKinds: List<SettingsChoiceUi>,
    val increments: List<SettingsChoiceUi>,
    // ── Timer ──
    val autoRest: Boolean,
    val timerSound: Boolean,
    val timerVibration: Boolean,
    val timerVoice: Boolean,
    // ── Display ──
    val checkinEnabled: Boolean,
    val bodyEnabled: Boolean,
    val hideNumbers: Boolean,
    // ── What the coach learned ──
    val learnedEmpty: Boolean,
    /** The real training length when it differs from the set one; 0 = none. */
    val learnedMinutes: Int,
    val learnedMinutesText: TrText,
    val learned: List<LearnedRowUi>,
    val excluded: List<SettingsExcludedUi>,
)

/**
 * Training settings (Android `AthleteSettingsScreen`): the overview with the current value per
 * topic and its pages — goal and week, about you, places and equipment, timer, display and units,
 * what the coach learned with "Forget" and "Forget everything", never-suggested exercises.
 * Nutrition targets and reminders are not on iPhone in this port (their rows say so); Health
 * Connect has no iPhone counterpart here.
 */
class AthleteSettingsScreenModel internal constructor(
    private val presenter: AthleteSettingsPresenter,
    private val language: String,
) {
    val currentState: AthleteSettingsScreenState get() = map(presenter.state.value)

    fun watch(onState: (AthleteSettingsScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    fun close() = presenter.close()

    fun setWeeklyGoal(days: Int) = presenter.setWeeklyGoal(days)

    /** [display] in the athlete's units; implausible values are refused (false). */
    fun logWeight(display: Double): Boolean {
        val kg = Units.massFromDisplay(display, presenter.state.value.profile.units)
        if (!kg.isFinite() || kg < ProgressScreenModel.PLAUSIBLE_MIN_KG || kg > ProgressScreenModel.PLAUSIBLE_MAX_KG) return false
        presenter.logWeight(kg)
        return true
    }

    /** Height in the athlete's units, within the body page's plausible range. */
    fun setHeight(display: Double): Boolean {
        val cm = Units.lengthFromDisplay(display, presenter.state.value.profile.units)
        if (!cm.isFinite() || !com.cruxcoach.app.progress.BodyPresenter.plausible("height", cm)) return false
        presenter.setHeight(cm)
        return true
    }

    /**
     * Android `BirthYearField`: empty removes the year; otherwise 100 to 8 years before
     * [currentYear] (Swift passes the device's year). False = refused.
     */
    fun setBirthYear(text: String, currentYear: Int): Boolean {
        val t = text.trim()
        if (t.isEmpty()) { presenter.setBirthYear(null); return true }
        val year = t.toIntOrNull() ?: return false
        if (year !in (currentYear - 100)..(currentYear - 8)) return false
        presenter.setBirthYear(year)
        return true
    }

    /** Tapping the chosen sex again clears it (Android's chips). */
    fun chooseSex(code: String) {
        val s = Sex.entries.firstOrNull { it.name == code } ?: return
        presenter.update { it.copy(sex = if (it.sex == s) null else s) }
    }

    /** autoRest | sound | vibration | voice | checkin | body | hideNumbers */
    fun setSwitch(code: String, on: Boolean) {
        when (code) {
            "autoRest" -> presenter.update { it.copy(autoRestTimer = on) }
            "sound" -> presenter.update { it.copy(timerSound = on) }
            "vibration" -> presenter.update { it.copy(timerVibration = on) }
            "voice" -> presenter.update { it.copy(timerVoice = on) }
            "checkin" -> presenter.update { it.copy(checkinEnabled = on) }
            "body" -> presenter.update { it.copy(bodyEnabled = on) }
            "hideNumbers" -> presenter.update { it.copy(hideBodyNumbers = on) }
        }
    }

    /** metric | imperial */
    fun setUnits(code: String) {
        val units = when (code) {
            "metric" -> UnitSystem.METRIC
            "imperial" -> UnitSystem.IMPERIAL
            else -> return
        }
        presenter.update { Units.withUnits(it, units) }
    }

    /** The smallest weight step, by the code of [AthleteSettingsScreenState.increments]. */
    fun setIncrement(code: String) {
        val inc = increments(presenter.state.value.profile.units).firstOrNull { incrementCode(it) == code } ?: return
        presenter.update { it.copy(smallestIncrementKg = inc) }
    }

    /** "Forget" on one learned row ([LearnedRowUi.kind] and [LearnedRowUi.key]). */
    fun forget(kind: String, key: String) {
        val learned = presenter.state.value.learned
        when (kind) {
            "weekday" -> key.toIntOrNull()?.takeIf { d -> learned.weekdays.any { it.isoDay == d } }?.let { presenter.forgetWeekday(it) }
            "level" -> SuggestionFocus.entries.firstOrNull { it.name == key }?.let { presenter.forgetLevel(it) }
            "kept" -> if (learned.kept.any { it.eventId == key }) presenter.forgetKept(key)
            "liked", "less" -> if (key.isNotEmpty()) presenter.forgetExercise(key)
            "ownLoad" -> learned.ownLoads.firstOrNull { it.first.id == key }?.let { presenter.forgetOwnLoad(it.first) }
        }
    }

    fun forgetAll() = presenter.forgetAll()

    /** The real training length becomes the preferred one. */
    fun applyLearnedMinutes() {
        val minutes = presenter.state.value.learned.minutes ?: return
        presenter.update { it.copy(sessionMinutes = minutes) }
    }

    fun allow(slug: String) = presenter.allow(slug)

    // ── Places ──

    /** [placeId] empty: today's place, created as the first place when there is none yet. */
    fun savePlaceEquipment(placeId: String, codes: List<String>) =
        presenter.savePlaceEquipment(placeId.ifEmpty { null },
            codes.mapNotNull { c -> EquipmentV2.entries.firstOrNull { it.name == c } }.toSet() + TodayScreenModel.ALWAYS_THERE)

    fun renamePlace(placeId: String, name: String) = presenter.renamePlace(placeId, name.trim().take(40).ifBlank { null })
    fun makeDefaultPlace(placeId: String) = presenter.makeDefaultPlace(placeId)
    fun removePlace(placeId: String) = presenter.removePlace(placeId)

    /** A new place of a kind starts with that kind's template (Android `placeTemplate`). */
    fun addPlace(kindCode: String) {
        val kind = PlaceKind.entries.firstOrNull { it.name == kindCode } ?: return
        presenter.addPlace(kind, placeTemplate(kind))
    }

    private var lastGood: AthleteSettingsScreenState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: AthleteSettingsRunState): AthleteSettingsScreenState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(AthleteSettingsRunState())
    }

    private fun mapUnsafe(s: AthleteSettingsRunState): AthleteSettingsScreenState {
        val p = s.profile
        val units = p.units
        val learned = s.learned
        val day = s.today?.toString() ?: ""
        val places = TrainingPlaces.of(p)
        val defaultPlace = TrainingPlaces.default(p)
        val todayPlace = if (day.isEmpty()) null else TrainingPlaces.current(p, day)
        val weight = s.weightKg
        val rows = buildList {
            add(SettingsRowUi("profile", Tx.t("tru_set_profile"),
                Tx.join(listOf(p.coach.goal?.let { Tx.t(Tx.coachGoalKey(it)) } ?: Tx.t("trc_settings_no_goal"),
                    Tx.p("tru_set_days", p.weeklyGoal, Tx.i(p.weeklyGoal)))), true))
            val aboutParts = listOfNotNull(
                weight?.let { if (p.hideBodyNumbers) Tx.t("trt_numbers_hidden_short") else Tx.mass(it, units) },
                s.heightCm?.let { BodyScreenModel.lengthText(it, units) },
                p.birthYear?.let { Tx.plain(it.toString()) },
            )
            add(SettingsRowUi("about", Tx.t("tru_set_about"),
                if (aboutParts.isEmpty()) Tx.t("tru_set_about_empty") else Tx.join(aboutParts), true))
            val placeNames = places.map { place ->
                val name = Tx.placeLabel(place)
                if (places.size > 1 && place.id == defaultPlace?.id) Tx.t("tro_summary_default", Tx.x(name)) else name
            }
            val count = ((defaultPlace?.equipment ?: p.equipment) - TodayScreenModel.ALWAYS_THERE).size
            add(SettingsRowUi("equipment", Tx.t("tru_set_equipment"), when {
                places.isEmpty() -> Tx.t("tru_set_not_set_up")
                places.size == 1 -> Tx.join(listOf(placeNames.single(), Tx.p("tru_set_equipment_count", count, Tx.i(count))))
                else -> Tx.join(placeNames)
            }, true))
            add(SettingsRowUi("nutrition", Tx.t("tra_module_fuel"),
                Tx.t("tru_set_nutrition_summary", Tx.n(p.proteinPerKg, 1), Tx.k(if (p.showCalories) "tru_set_on" else "tru_set_off")), false))
            val reminders = listOf(p.weighReminderEnabled, p.measureReminderEnabled, p.trainingReminderEnabled).count { it }
            add(SettingsRowUi("reminders", Tx.t("trr_title"),
                if (reminders == 0) Tx.t("tru_set_all_off") else Tx.p("tru_set_reminders", reminders, Tx.i(reminders)), false))
            add(SettingsRowUi("timer", Tx.t("tra_timer_title"),
                Tx.join(listOfNotNull(
                    Tx.t("tru_set_timer_auto").takeIf { p.autoRestTimer },
                    Tx.t("tru_set_timer_sound").takeIf { p.timerSound },
                    Tx.t("tru_set_timer_vibration").takeIf { p.timerVibration },
                    Tx.t("tru_set_timer_voice").takeIf { p.timerVoice },
                ).ifEmpty { listOf(Tx.t("tru_set_all_off")) }), true))
            add(SettingsRowUi("display", Tx.t("tru_set_display"),
                Tx.join(listOfNotNull(Tx.t(if (units == UnitSystem.IMPERIAL) "settings_units_us" else "settings_units_metric"),
                    Tx.t("tru_set_numbers_hidden").takeIf { p.hideBodyNumbers })), true))
            add(SettingsRowUi("connections", Tx.t("tru_set_connections"), Tx.t("tru_set_connections_summary"), true))
            add(SettingsRowUi("climbingDays", Tx.t("trl_days_title"), Tx.t("tru_set_days_summary"), true))
            add(SettingsRowUi("excluded", Tx.t("trlr_title"),
                Tx.join(listOfNotNull(
                    learned.count.takeIf { it > 0 }?.let { Tx.p("trlr_summary_learned", it, Tx.i(it)) },
                    s.excluded.size.takeIf { it > 0 }?.let { Tx.p("trlr_summary_never", it, Tx.i(it)) },
                ).ifEmpty { listOf(Tx.t("trlr_summary_nothing")) }), true))
        }
        val learnedRows = buildList {
            learned.weekdays.forEach { u ->
                add(LearnedRowUi("weekday", u.isoDay.toString(), TrText("", listOf(Tx.weekday(u.isoDay)), -1, ""),
                    Tx.join(listOfNotNull(u.focus?.let { Tx.t(focusChoiceKey(it)) }, u.minutes?.let { Tx.t("trt_duration_value", Tx.i(it)) }))))
            }
            learned.levels.forEach { (focus, step) ->
                add(LearnedRowUi("level", focus.name, Tx.t(Tx.levelKindKey(focus)),
                    Tx.t(if (step < 0) "trlr_level_easier" else "trlr_level_harder")))
            }
            learned.kept.forEach { k ->
                add(LearnedRowUi("kept", k.eventId, Tx.keptChangeText(k.replaced, k.chosen, s.catalog, language),
                    Tx.t("trlr_kept_until", Tx.date(k.until.toString()))))
            }
            learned.liked.forEach { def -> add(LearnedRowUi("liked", def.slug, Tx.plain(def.name(language)), Tx.EMPTY)) }
            learned.less.forEach { def ->
                add(LearnedRowUi("less", def.slug, Tx.plain(def.name(language)),
                    learned.resting[def.slug]?.let { Tx.t("trlr_resting_until", Tx.date(it.toString())) } ?: Tx.EMPTY))
            }
            learned.ownLoads.forEach { (b, def) ->
                val load = b.loadKg?.let { Tx.mass(it, units, signed = def.load == LoadMode.BODYWEIGHT_PLUS) }
                add(LearnedRowUi("ownLoad", b.id,
                    Tx.join(listOfNotNull(Tx.plain(def.name(language)), b.side?.let { Tx.t(Tx.sideKey(it)) })),
                    Tx.join(listOfNotNull(b.sets?.let { n -> b.reps?.let { Tx.plain("$n × $it") } }, load))))
            }
        }
        return AthleteSettingsScreenState(
            loaded = s.loaded,
            unitsCode = BodyScreenModel.unitsCode(units),
            rows = rows,
            coachGoal = p.coach.goal?.let { Tx.t(Tx.coachGoalKey(it)) } ?: Tx.t("trc_settings_no_goal"),
            weeklyGoal = p.weeklyGoal,
            weeklyGoalText = Tx.t("tra_weekly_goal", Tx.i(p.weeklyGoal)),
            weightText = when {
                weight == null -> Tx.t("tru_set_none")
                p.hideBodyNumbers -> Tx.t("trt_numbers_hidden_short")
                else -> Tx.mass(weight, units)
            },
            hasHeight = s.heightCm != null,
            heightDisplay = s.heightCm?.let { Units.lengthToDisplay(it, units) } ?: 0.0,
            heightUnit = Units.lengthUnit(units),
            birthYear = p.birthYear ?: 0,
            sexes = Sex.entries.map { SettingsChoiceUi(it.name, Tx.t(sexKey(it)), p.sex == it) },
            places = places.map { place ->
                SettingsPlaceUi(place.id, Tx.placeLabel(place), place.name ?: "", place.kind.name,
                    place.id == defaultPlace?.id, place.id == todayPlace?.id,
                    (place.equipment - TodayScreenModel.ALWAYS_THERE).map { it.name }.sorted())
            },
            placeKinds = PlaceKind.entries.map { SettingsChoiceUi(it.name, Tx.t(Tx.placeKindKey(it)), false) },
            increments = increments(units).map { inc ->
                SettingsChoiceUi(incrementCode(inc), Tx.mass(inc, units), abs(p.smallestIncrementKg - inc) < 1e-6)
            },
            autoRest = p.autoRestTimer,
            timerSound = p.timerSound,
            timerVibration = p.timerVibration,
            timerVoice = p.timerVoice,
            checkinEnabled = p.checkinEnabled,
            bodyEnabled = p.bodyEnabled,
            hideNumbers = p.hideBodyNumbers,
            learnedEmpty = learned.isEmpty,
            learnedMinutes = learned.minutes ?: 0,
            learnedMinutesText = learned.minutes?.let { Tx.t("trlr_minutes_text", Tx.i(it), Tx.i(p.sessionMinutes)) } ?: Tx.EMPTY,
            learned = learnedRows,
            excluded = s.excluded.map { SettingsExcludedUi(it.slug, it.name(language)) },
        )
    }

    internal companion object {
        fun sexKey(s: Sex) = when (s) {
            Sex.FEMALE -> "tra_sex_female"
            Sex.MALE -> "tra_sex_male"
            Sex.OTHER -> "tra_sex_other"
        }

        /** Android `focusChoiceLabel`. */
        fun focusChoiceKey(focus: SuggestionFocus) = when (focus) {
            SuggestionFocus.FINGER_STRENGTH -> "trt_kind_finger"
            SuggestionFocus.PULL_PUSH -> "trt_kind_pull_push"
            SuggestionFocus.LEGS_CORE -> "trt_kind_legs_core"
            SuggestionFocus.BOARD_DAY -> "trt_kind_board"
            else -> "trt_kind_mobility"
        }

        /** Steps in the athlete's own plates: lb plates for imperial, stored in kg. */
        fun increments(units: UnitSystem): List<Double> =
            if (units == UnitSystem.IMPERIAL) listOf(1.0, 2.5, 5.0).map { it / Units.LB_PER_KG } else listOf(0.5, 1.0, 1.25, 2.5)

        fun incrementCode(kg: Double): String = ((kg * 1000).toLong()).toString()

        private val PRESET_HOME = setOf(EquipmentV2.HANGBOARD, EquipmentV2.PULL_UP_BAR, EquipmentV2.BANDS, EquipmentV2.DUMBBELL)
        private val PRESET_GYM = setOf(EquipmentV2.HANGBOARD, EquipmentV2.PULL_UP_BAR, EquipmentV2.RINGS, EquipmentV2.DUMBBELL,
            EquipmentV2.KETTLEBELL, EquipmentV2.BARBELL, EquipmentV2.PLATES, EquipmentV2.BANDS, EquipmentV2.BENCH, EquipmentV2.BOX,
            EquipmentV2.CABLE, EquipmentV2.WALL, EquipmentV2.BOARD, EquipmentV2.CAMPUS_BOARD, EquipmentV2.FOAM_ROLLER, EquipmentV2.DIP_BARS)
        private val PRESET_TRAVEL = setOf(EquipmentV2.BANDS)

        /** What a new place of a kind starts with (Android `placeTemplate`). */
        fun placeTemplate(kind: PlaceKind): Set<EquipmentV2> = when (kind) {
            PlaceKind.HOME -> PRESET_HOME
            PlaceKind.GYM -> PRESET_GYM
            PlaceKind.TRAVEL -> PRESET_TRAVEL
            PlaceKind.OTHER -> emptySet()
        } + TodayScreenModel.ALWAYS_THERE
    }
}
