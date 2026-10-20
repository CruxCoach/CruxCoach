package com.cruxcoach.app.training

import com.cruxcoach.app.ui.TrArg
import com.cruxcoach.app.ui.TrText
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import com.cruxcoach.domain.playlist.GeneratorType
import com.cruxcoach.util.GradeConverter
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Builders for [TrText]: the choice of Android string resource and its
 * arguments, ported from the Android Compose helpers named in each function.
 * Only keys of Android's resources (generated into the iOS `Localizable.strings`)
 * appear here; Swift renders them.
 */
internal object Tx {
    fun t(key: String, vararg args: TrArg) = TrText(key, args.toList(), -1, "")
    /** A plural resource: [count] picks `.one`/`.other`; [args] fill it (Android passes the count again where the text shows it). */
    fun p(key: String, count: Int, vararg args: TrArg) = TrText(key, args.toList(), count, "")
    fun join(parts: List<TrText>, with: String = " · ") = TrText("", parts.map { x(it) }, -1, with)
    fun plain(text: String) = TrText("", listOf(s(text)), -1, "")
    val EMPTY = TrText("", emptyList(), -1, "")

    fun s(text: String) = TrArg("text", text, 0.0, 0, false, null)
    fun k(key: String) = TrArg("key", key, 0.0, 0, false, null)
    fun i(value: Int) = TrArg("int", "", value.toDouble(), 0, false, null)
    fun n(value: Double, decimals: Int = 1, signed: Boolean = false) = TrArg("num", "", value, decimals, signed, null)
    fun x(text: TrText) = TrArg("nested", "", 0.0, 0, false, text)
    /** An ISO date (yyyy-mm-dd), shown in the device's medium date style. */
    fun date(iso: String) = TrArg("date", iso, 0.0, 0, false, null)
    /** An ISO weekday (1 = Monday), shown as its full name. */
    fun weekday(isoDay: Int) = TrArg("weekday", "", isoDay.toDouble(), 0, false, null)

    // ── Formatting (TrainingCommon.kt) ─────────────────────────────────

    /** Plate steps like 1.25 kg need two decimals; body weights and loads read best with one. */
    fun massDecimals(v: Double): Int {
        val quarter = abs(v * 4 - round(v * 4)) < 1e-6
        val tenth = abs(v * 10 - round(v * 10)) < 1e-6
        return if (quarter && !tenth) 2 else 1
    }

    fun mass(kg: Double, units: UnitSystem, signed: Boolean = false): TrText {
        val v = Units.massToDisplay(kg, units)
        return t(if (units == UnitSystem.IMPERIAL) "tr_format_lb" else "tr_format_kg", n(v, massDecimals(v), signed))
    }

    fun seconds(s: Int) = t("tr_format_seconds", i(s))
    fun mm(value: Double) = t("tr_format_mm", n(value, 1))
    fun minutes(m: Int) = p("trt_minutes", m, i(m))

    // ── Labels (TrainingCommon.kt) ─────────────────────────────────────

    fun categoryKey(c: ExerciseCategoryV2) = when (c) {
        ExerciseCategoryV2.FINGER -> "tr_cat_finger"
        ExerciseCategoryV2.PULL -> "tr_cat_pull"
        ExerciseCategoryV2.PUSH -> "tr_cat_push"
        ExerciseCategoryV2.ANTAGONIST -> "tr_cat_antagonist"
        ExerciseCategoryV2.CORE -> "tr_cat_core"
        ExerciseCategoryV2.LEGS -> "tr_cat_legs"
        ExerciseCategoryV2.MOBILITY -> "tr_cat_mobility"
        ExerciseCategoryV2.WARMUP -> "tr_cat_warmup"
        ExerciseCategoryV2.POWER -> "tr_cat_power"
        ExerciseCategoryV2.ENDURANCE -> "tr_cat_endurance"
        ExerciseCategoryV2.TECHNIQUE -> "tr_cat_technique"
    }

    fun equipmentKey(e: EquipmentV2) = when (e) {
        EquipmentV2.NONE -> "tr_eq_none"
        EquipmentV2.MAT -> "tr_eq_mat"
        EquipmentV2.HANGBOARD -> "tr_eq_hangboard"
        EquipmentV2.PICKUP_BLOCK -> "tr_eq_pickup_block"
        EquipmentV2.PICKUP_BLOCK_PAIR -> "tr_eq_pickup_block_pair"
        EquipmentV2.PULL_UP_BAR -> "tr_eq_pull_up_bar"
        EquipmentV2.RINGS -> "tr_eq_rings"
        EquipmentV2.DUMBBELL -> "tr_eq_dumbbell"
        EquipmentV2.KETTLEBELL -> "tr_eq_kettlebell"
        EquipmentV2.BARBELL -> "tr_eq_barbell"
        EquipmentV2.PLATES -> "tr_eq_plates"
        EquipmentV2.BANDS -> "tr_eq_bands"
        EquipmentV2.BENCH -> "tr_eq_bench"
        EquipmentV2.BOX -> "tr_eq_box"
        EquipmentV2.CABLE -> "tr_eq_cable"
        EquipmentV2.WALL -> "tr_eq_wall"
        EquipmentV2.BOARD -> "tr_eq_board"
        EquipmentV2.CAMPUS_BOARD -> "tr_eq_campus_board"
        EquipmentV2.FOAM_ROLLER -> "tr_eq_foam_roller"
        EquipmentV2.PULLEY -> "tr_eq_pulley"
        EquipmentV2.DIP_BARS -> "tr_eq_dip_bars"
    }

    fun domainKey(d: LoadDomain) = when (d) {
        LoadDomain.FINGER -> "tr_dom_finger"
        LoadDomain.WRIST -> "tr_dom_wrist"
        LoadDomain.ELBOW -> "tr_dom_elbow"
        LoadDomain.SHOULDER -> "tr_dom_shoulder"
        LoadDomain.SKIN -> "tr_dom_skin"
        LoadDomain.LOWER_BODY -> "tr_dom_lower_body"
        LoadDomain.BACK -> "tr_dom_back"
        LoadDomain.SYSTEMIC -> "tr_dom_systemic"
    }

    fun gripKey(g: Grip) = when (g) {
        Grip.HALF_CRIMP -> "tr_grip_half_crimp"
        Grip.OPEN_HAND -> "tr_grip_open_hand"
        Grip.FULL_CRIMP -> "tr_grip_full_crimp"
        Grip.THREE_FINGER_DRAG -> "tr_grip_three_finger_drag"
        Grip.FRONT_TWO -> "tr_grip_front_two"
        Grip.MIDDLE_TWO -> "tr_grip_middle_two"
        Grip.PINCH -> "tr_grip_pinch"
        Grip.SLOPER -> "tr_grip_sloper"
        Grip.JUG -> "tr_grip_jug"
    }

    fun regionKey(r: InjuryRegion) = when (r) {
        InjuryRegion.FINGER -> "tr_region_finger"
        InjuryRegion.WRIST -> "tr_region_wrist"
        InjuryRegion.ELBOW -> "tr_region_elbow"
        InjuryRegion.SHOULDER -> "tr_region_shoulder"
        InjuryRegion.BACK -> "tr_region_back"
        InjuryRegion.HIP -> "tr_region_hip"
        InjuryRegion.KNEE -> "tr_region_knee"
        InjuryRegion.ANKLE -> "tr_region_ankle"
        InjuryRegion.SKIN -> "tr_region_skin"
        InjuryRegion.OTHER -> "tr_region_other"
    }

    fun sideKey(s: Side) = if (s == Side.LEFT) "tr_side_left" else "tr_side_right"

    fun injurySideKey(s: InjurySide) = when (s) {
        InjurySide.LEFT -> "tr_side_left"
        InjurySide.RIGHT -> "tr_side_right"
        InjurySide.BOTH -> "tr_side_both"
    }

    fun dayLoadKey(d: DayLoad) = when (d) {
        DayLoad.REST -> "tr_dayload_rest"
        DayLoad.LIGHT -> "tr_dayload_light"
        DayLoad.MODERATE -> "tr_dayload_moderate"
        DayLoad.HARD -> "tr_dayload_hard"
        DayLoad.VERY_HARD -> "tr_dayload_very_hard"
    }

    fun setTypeKey(t: SetType) = when (t) {
        SetType.WARMUP -> "tr_settype_warmup"
        SetType.WORK -> "tr_settype_work"
        SetType.TEST -> "tr_settype_test"
    }

    fun placeKindKey(kind: PlaceKind) = when (kind) {
        PlaceKind.HOME -> "tro_kind_home"
        PlaceKind.GYM -> "tro_kind_gym"
        PlaceKind.TRAVEL -> "tro_kind_travel"
        PlaceKind.OTHER -> "tro_kind_other"
    }

    /** "No equipment today", the athlete's own name, else the kind (Places.kt `placeLabel`). */
    fun placeLabel(place: TrainingPlace): TrText = when {
        place.id == TrainingPlaces.NO_GEAR_ID -> t("tro_no_gear")
        place.name != null -> plain(place.name!!)
        else -> t(placeKindKey(place.kind))
    }

    /** Built-in routines carry a key; their names live in resources (`routineName`). */
    fun builtinRoutineNameKey(key: String?): String? = when (key) {
        "warmup_board" -> "tr_routine_warmup_board"
        "finger_basics" -> "tr_routine_finger_basics"
        "injury_one_arm" -> "tr_routine_injury_one_arm"
        "pull_antagonist" -> "tr_routine_pull_antagonist"
        "antagonist_15" -> "tr_routine_antagonist_15"
        "legs_basics" -> "tr_routine_legs_basics"
        "core_climber" -> "tr_routine_core_climber"
        "mobility_10" -> "tr_routine_mobility_10"
        "baseline_tests" -> "tr_routine_baseline_tests"
        else -> null
    }

    fun routineName(r: Routine): TrText = builtinRoutineNameKey(r.builtinKey)?.let { t(it) } ?: plain(r.name)

    /** Built-in routines are stored by key; their localized name, else the title, else "Training" (`workoutTitle`). */
    fun workoutTitle(w: Workout): TrText {
        val builtin = w.routineId?.takeIf { it.startsWith("builtin:") }?.removePrefix("builtin:")?.let { BuiltinRoutines.byKey(it) }
        return when {
            builtin != null -> routineName(builtin)
            !w.title.isNullOrBlank() -> plain(w.title!!)
            else -> t("trw_workout_default")
        }
    }

    /** "7a (V6)" for a Font grade (`gradeWithV`). */
    fun gradeWithV(font: String): String {
        val index = GradeConverter.gradeToIndex(font.lowercase())
        val v = if (index >= 0) GradeConverter.GRADES.getOrNull(index)?.vScale else null
        return if (v != null) "$font ($v)" else font
    }

    // ── Today (TodayScreen.kt) ─────────────────────────────────────────

    fun readinessReasonKey(reason: ReadinessReason) = when (reason) {
        ReadinessReason.SICK -> "trt_reason_sick"
        ReadinessReason.INJURY_CLIMBING_PAUSED -> "trt_reason_injury_paused"
        ReadinessReason.INJURY_ACTIVE -> "trt_reason_injury"
        ReadinessReason.FINGERS_TIRED -> "trt_reason_fingers"
        ReadinessReason.SKIN_LOW -> "trt_reason_skin"
        ReadinessReason.LOW_SLEEP -> "trt_reason_sleep"
        ReadinessReason.LOW_ENERGY -> "trt_reason_energy"
        ReadinessReason.LOW_MOTIVATION -> "trt_reason_motivation"
        ReadinessReason.ALL_GOOD -> "trt_reason_all_good"
    }

    fun readinessReasonShortKey(reason: ReadinessReason) = when (reason) {
        ReadinessReason.SICK -> "trt_short_sick"
        ReadinessReason.INJURY_CLIMBING_PAUSED, ReadinessReason.INJURY_ACTIVE -> "trt_short_injury"
        ReadinessReason.FINGERS_TIRED -> "trt_q_fingers"
        ReadinessReason.SKIN_LOW -> "trt_q_skin"
        ReadinessReason.LOW_SLEEP -> "trt_q_sleep"
        ReadinessReason.LOW_ENERGY -> "trt_q_energy"
        ReadinessReason.LOW_MOTIVATION -> "trt_short_motivation"
        ReadinessReason.ALL_GOOD -> "trt_reason_all_good"
    }

    fun suggestionReasonKey(reason: SuggestionReason) = when (reason) {
        SuggestionReason.COMEBACK_PLAN -> "cb_reason_plan"
        SuggestionReason.COMEBACK_CLIMBING -> "cb_reason_climbing_generic"
        SuggestionReason.COMEBACK_NO_BOARD -> "cb_reason_no_board"
        SuggestionReason.SICK -> "trsg_reason_sick"
        SuggestionReason.REST_READINESS -> "trsg_reason_rest_readiness"
        SuggestionReason.WEEK_PLAN -> "trsg_reason_week_plan"
        SuggestionReason.WEEK_PLAN_BOARD -> "trsg_reason_week_plan_board"
        SuggestionReason.WEEK_PLAN_REST -> "trsg_reason_week_plan_rest"
        SuggestionReason.PLAN_FILTERED_FOR_INJURY -> "trsg_reason_plan_filtered"
        SuggestionReason.PLAN_REPLACED_FOR_INJURY -> "trsg_reason_plan_replaced"
        SuggestionReason.BOARD_SKIPPED_TODAY -> "trsg_reason_board_skipped"
        SuggestionReason.BOARD_LIGHT -> "trsg_reason_board_light"
        SuggestionReason.HARD_DAY_TOMORROW -> "trsg_reason_hard_tomorrow"
        SuggestionReason.INJURY_CLIMBING_PAUSED -> "trsg_reason_injury"
        SuggestionReason.FINGERS_LOADED_RECENTLY -> "trsg_reason_fingers_loaded"
        SuggestionReason.FINGERS_TIRED -> "trsg_reason_fingers_tired"
        SuggestionReason.SKIN_LOW -> "trsg_reason_skin"
        SuggestionReason.FINGERS_RESTED -> "trsg_reason_fingers_rested"
        SuggestionReason.NO_FINGER_EQUIPMENT -> "trsg_reason_no_finger_equipment"
        SuggestionReason.PULL_LONGER_AGO -> "trsg_reason_pull_longer_ago"
        SuggestionReason.LEGS_LONGER_AGO -> "trsg_reason_legs_longer_ago"
        SuggestionReason.LOW_ENERGY -> "trsg_reason_low_energy"
        SuggestionReason.GOAL_STRENGTH -> "trsg_reason_goal_strength"
        SuggestionReason.FAVORITES_USED -> "trsg_reason_favorites"
        SuggestionReason.SHORTENED_TO_TIME -> "trsg_reason_shortened"
        SuggestionReason.RECOVERY_AFTER_LIMIT -> "tre_reason_recovery_limit"
        SuggestionReason.RECOVERY_AFTER_HARD -> "tre_reason_recovery_hard"
        SuggestionReason.RECOVERY_AFTER_VOLUME -> "tre_reason_recovery_volume"
        SuggestionReason.FINGER_LOAD_RISING -> "tre_reason_finger_rising"
        SuggestionReason.SKIN_LOAD_RISING -> "tre_reason_skin_rising"
        SuggestionReason.GUARDRAIL_YOUTH -> "tre_reason_guard_youth"
        SuggestionReason.GUARDRAIL_NOVICE -> "tre_reason_guard_novice"
        SuggestionReason.GUARDRAIL_MASTERS -> "tre_reason_guard_masters"
        SuggestionReason.FINGER_PREFERENCE_NONE -> "tre_reason_finger_none"
        SuggestionReason.FOCUS_AREAS -> "tre_reason_focus"
        SuggestionReason.GOAL_PROJECT -> "tre_reason_goal_project"
        SuggestionReason.GOAL_HEALTHY -> "tre_reason_goal_healthy"
        SuggestionReason.GOAL_COMEBACK -> "tre_reason_goal_comeback"
        SuggestionReason.CLIMBING_DAY -> "tre_reason_climbing_day"
        SuggestionReason.CLIMBING_DAY_ADDON -> "tre_reason_addon"
        SuggestionReason.BLOCK_INTRO -> "tre_reason_block_intro"
        SuggestionReason.BLOCK_DELOAD -> "tre_reason_block_deload"
        SuggestionReason.BLOCK_TAPER -> "tre_reason_block_taper"
        SuggestionReason.BLOCK_EVENT -> "tre_reason_block_event"
        SuggestionReason.PREFERENCES_LEARNED -> "tre_reason_learned"
        SuggestionReason.NO_TIME_TODAY -> "tre_reason_no_time"
        SuggestionReason.LEVEL_EASIER -> "tre_reason_level_easier"
        SuggestionReason.LEVEL_HARDER -> "tre_reason_level_harder"
        SuggestionReason.RETURN_AFTER_BREAK -> "tre_reason_return_break"
        SuggestionReason.RETURN_FINGER -> "tre_reason_return_finger"
        SuggestionReason.WEEKLY_TARGET -> "trv_reason_weekly"
        SuggestionReason.CHOSEN_TODAY -> "trt_reason_chosen"
        SuggestionReason.SPARED_TODAY -> "trt_reason_spared_generic"
        SuggestionReason.NO_WALL_HERE -> "trt_reason_no_wall"
        SuggestionReason.MORE_TIME_TODAY -> "trt_reason_more_time"
        SuggestionReason.EXTENDED_TO_TIME -> "trt_reason_extended"
        SuggestionReason.KEPT_SHORT_THIS_WEEK -> "trt_reason_kept_short"
        SuggestionReason.LESS_THAN_CHOSEN -> "trt_reason_less_than_chosen"
        SuggestionReason.USUAL_KIND -> "trt_reason_usual_kind_generic"
        SuggestionReason.USUAL_MINUTES -> "trt_reason_usual_minutes_generic"
        SuggestionReason.KEPT_CHOICE -> "tre_reason_kept"
        SuggestionReason.LEVEL_EASIER_KEPT -> "tre_reason_level_easier_kept"
        SuggestionReason.LEVEL_HARDER_KEPT -> "tre_reason_level_harder_kept"
        SuggestionReason.PLANNED_CLIMB -> "trdp_reason_planned_climb"
        SuggestionReason.CLIMB_LIGHTER_ADVISED -> "trdp_reason_lighter"
        SuggestionReason.LIMIT_TOMORROW -> "trdp_reason_limit_tomorrow"
        SuggestionReason.PLANNED_CLIMB_NOT_LOGGED -> "trdp_reason_not_logged"
        SuggestionReason.FINGERS_BEFORE_HARD_DAY -> "trdp_reason_fingers_before"
        SuggestionReason.USUAL_ROUTINE_WEEKDAY, SuggestionReason.USUAL_ROUTINE -> "trhb_reason_generic"
    }

    /** The kinds of training the athlete can choose, in their words (`kindLabel`). */
    fun kindKey(focus: SuggestionFocus) = when (focus) {
        SuggestionFocus.FINGER_STRENGTH -> "trt_kind_finger"
        SuggestionFocus.PULL_PUSH -> "trt_kind_pull_push"
        SuggestionFocus.LEGS_CORE -> "trt_kind_legs_core"
        SuggestionFocus.BOARD_DAY -> "trt_kind_board"
        else -> "trt_kind_mobility"
    }

    /** The kind of training a level step is kept for (`levelKindLabel`). */
    fun levelKindKey(focus: SuggestionFocus) = when (focus) {
        SuggestionFocus.PLANNED -> "trsg_focus_planned"
        SuggestionFocus.INJURY_SAFE -> "trsg_focus_injury"
        else -> kindKey(focus)
    }

    fun focusTitleKey(focus: SuggestionFocus) = when (focus) {
        SuggestionFocus.PLANNED -> "trsg_focus_planned"
        SuggestionFocus.BOARD_DAY -> "trsg_focus_board"
        SuggestionFocus.FINGER_STRENGTH -> "trsg_focus_finger"
        SuggestionFocus.PULL_PUSH -> "trsg_focus_pull_push"
        SuggestionFocus.LEGS_CORE -> "trsg_focus_legs_core"
        SuggestionFocus.MOBILITY_RECOVERY -> "trsg_focus_mobility"
        SuggestionFocus.INJURY_SAFE -> "trsg_focus_injury"
        SuggestionFocus.REST -> "trsg_focus_rest"
    }

    fun blockLabel(b: BlockState): TrText = when (b.phase) {
        BlockPhase.INTRO -> t("tre_block_intro")
        BlockPhase.BUILD -> t("tre_block_build", i(b.weekInBlock), i(b.weeksInBlock))
        BlockPhase.DELOAD -> t("tre_block_deload")
        BlockPhase.TAPER -> t("tre_block_taper", i(b.daysToEvent ?: 0))
        BlockPhase.EVENT -> t("tre_block_event")
    }

    fun confidenceKey(c: Confidence) = when (c) {
        Confidence.LOW -> "tre_conf_low"
        Confidence.MEDIUM -> "tre_conf_medium"
        Confidence.HIGH -> "tre_conf_high"
    }

    fun generatorKey(type: GeneratorType) = when (type) {
        GeneratorType.PYRAMID -> "tre_gen_pyramid"
        GeneratorType.POWER_ENDURANCE -> "tre_gen_pe"
        GeneratorType.VOLUME -> "tre_gen_volume"
        GeneratorType.LIMIT -> "tre_gen_limit"
        GeneratorType.PROJECTING -> "tre_gen_projecting"
        GeneratorType.MANUAL -> "tre_gen_manual"
    }

    fun intensityKey(i: ClimbIntensity?) = when (i) {
        ClimbIntensity.LIGHT -> "tre_int_light"
        ClimbIntensity.VOLUME -> "tre_int_volume"
        ClimbIntensity.HARD -> "tre_int_hard"
        ClimbIntensity.LIMIT -> "tre_int_limit"
        null -> "tre_int_unknown"
    }

    /** Where a climbing day is (Android `climbingDayKindLabel`). */
    fun climbKindKey(k: ClimbingDayKind) = when (k) {
        ClimbingDayKind.GYM_BOULDER -> "trl_kind_gym_boulder"
        ClimbingDayKind.GYM_ROPE -> "trl_kind_gym_rope"
        ClimbingDayKind.OUTDOOR -> "trl_kind_outdoor"
        ClimbingDayKind.OTHER_BOARD -> "trl_kind_other_board"
        ClimbingDayKind.OTHER -> "trl_kind_other"
    }

    /** How hard a climbing day is (Android `climbIntensityLabel`; "Volume" since 2026-10-19). */
    fun climbLevelKey(i: ClimbIntensity) = when (i) {
        ClimbIntensity.LIGHT -> "trl_intensity_light"
        ClimbIntensity.VOLUME -> "trl_intensity_volume"
        ClimbIntensity.HARD -> "trl_intensity_hard"
        ClimbIntensity.LIMIT -> "trl_intensity_limit"
    }

    /** A planned climbing day as the plan shows it: "Gym bouldering · Limit" (Android `climbPlanLabel`). */
    fun climbPlanLabel(c: DayPlans.Climb): TrText = join(listOf(t(climbKindKey(c.kind)), t(climbLevelKey(c.intensity))))

    fun structureKey(s: LoadStructure) = when (s) {
        LoadStructure.FINGER -> "tre_struct_finger"
        LoadStructure.SKIN -> "tre_struct_skin"
        LoadStructure.SHOULDER -> "tre_struct_shoulder"
    }

    fun trendKey(t: LoadTrend) = when (t) {
        LoadTrend.LOW -> "tre_trend_low"
        LoadTrend.NORMAL -> "tre_trend_normal"
        LoadTrend.RISING -> "tre_trend_rising"
        LoadTrend.SPIKE -> "tre_trend_spike"
    }

    fun coachGoalKey(g: CoachGoal) = when (g) {
        CoachGoal.CLIMB_HARDER -> "tre_goal_climb_harder"
        CoachGoal.PROJECT -> "tre_goal_project"
        CoachGoal.BUILD_STRENGTH -> "tre_goal_strength"
        CoachGoal.STAY_HEALTHY -> "tre_goal_healthy"
        CoachGoal.COMEBACK -> "tre_goal_comeback"
        CoachGoal.EVENT -> "tre_goal_event"
    }

    fun focusAreaKey(f: FocusArea) = when (f) {
        FocusArea.FINGER_STRENGTH -> "tre_focus_finger"
        FocusArea.PULL_STRENGTH -> "tre_focus_pull"
        FocusArea.POWER -> "tre_focus_power"
        FocusArea.POWER_ENDURANCE -> "tre_focus_pe"
        FocusArea.CORE -> "tre_focus_core"
        FocusArea.MOBILITY -> "tre_focus_mobility"
        FocusArea.PREVENTION -> "tre_focus_prevention"
    }

    fun volumeAreaKey(area: VolumeArea) = when (area) {
        VolumeArea.FINGER -> "trv_area_finger"
        VolumeArea.PULL -> "trv_area_pull"
        VolumeArea.PUSH -> "trv_area_push"
        VolumeArea.LEGS -> "trv_area_legs"
        VolumeArea.CORE -> "trv_area_core"
        VolumeArea.ANTAGONIST -> "trv_area_antagonist"
        VolumeArea.MOBILITY -> "trv_area_mobility"
    }

    /** "Pulling this week: 4/6 sets" (`weeklyHintText`). */
    fun weeklyHint(p: AreaProgress, adds: Int? = null): TrText {
        val base = t(if (p.area == VolumeArea.FINGER) "trv_hint_sessions" else "trv_hint_sets",
            k(volumeAreaKey(p.area)), n(p.effective, 1), i(p.goal))
        return if (adds != null && adds > 0) t("trv_hint_plus", x(base), i(adds)) else base
    }

    /** "3 × 8", "3 × 10 s" … (`routineItemSummary`). */
    fun routineItemSummary(item: RoutineItem, kind: ExerciseKind, load: LoadMode, units: UnitSystem, short: Boolean = false): TrText {
        val amount = when (kind) {
            ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> {
                val min = item.repsMin
                val max = item.repsMax ?: min
                val reps = if (min != null && max != null && min != max) "$min–$max" else max?.toString()
                plain(reps?.let { "${item.sets} × $it" } ?: "${item.sets} ×")
            }
            ExerciseKind.TIME, ExerciseKind.HANG -> join(listOf(plain("${item.sets} × "), seconds(item.durationS ?: 0)), "")
            ExerciseKind.INTERVAL -> join(listOf(plain("${item.sets} × "),
                t("trw_interval_format", i(item.workS ?: 7), i(item.restBetweenS ?: 3), i(item.repsPerSet ?: 6))), "")
            ExerciseKind.CLIMB -> item.durationS?.let { t("trwo_minutes", i(it / 60)) }
                ?: p("trp_rounds", item.repsPerSet ?: 4, i(item.repsPerSet ?: 4))
        }
        if (short) return amount
        val parts = buildList {
            add(amount)
            item.loadKg?.let { kg ->
                when {
                    load == LoadMode.BODYWEIGHT_PLUS && kg < 0 -> add(t("trw_load_assisted", x(mass(-kg, units))))
                    load == LoadMode.BODYWEIGHT_PLUS -> add(mass(kg, units, signed = true))
                    load == LoadMode.EXTERNAL -> add(mass(kg, units))
                    else -> Unit
                }
            }
            if (kind == ExerciseKind.HANG || kind == ExerciseKind.INTERVAL) item.edgeMm?.let { add(t("tr_format_mm", s(it.toString()))) }
            when (item.sides) {
                SideMode.LEFT_ONLY -> add(t(sideKey(Side.LEFT)))
                SideMode.RIGHT_ONLY -> add(t(sideKey(Side.RIGHT)))
                SideMode.BOTH -> Unit
            }
            if (item.warmup) add(t("trwo_text_warmup"))
        }
        return join(parts)
    }

    // ── Values of sets (WorkoutPlayerScreen.kt, RestBar.kt) ─────────────

    /** "8 reps", "10 s", "4 rounds" — the number the set is about (`mainValue`). */
    fun mainValue(set: ExerciseSet, def: ExerciseDefinition): TrText? = when (def.kind) {
        ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> (set.reps ?: set.targetReps)?.let { p("trp_reps_big", it, i(it)) }
        ExerciseKind.TIME, ExerciseKind.HANG ->
            (set.durationS ?: set.targetDurationS ?: def.defaults.durationS?.toDouble())?.roundToInt()?.let { seconds(it) }
        ExerciseKind.CLIMB -> set.reps?.let { p("trp_rounds", it, i(it)) }
        ExerciseKind.INTERVAL -> null
    }

    /** "+12 kg", "15 kg assisted", "Bodyweight", "32 kg"; null without a load (`loadText`). */
    fun loadText(set: ExerciseSet, def: ExerciseDefinition, units: UnitSystem): TrText? {
        val load = set.loadKg
        return when (def.load) {
            LoadMode.BODYWEIGHT_PLUS -> when {
                load == null || load == 0.0 -> t("tr_bodyweight")
                load < 0 -> t("trw_load_assisted", x(mass(-load, units)))
                else -> mass(load, units, signed = true)
            }
            // Always a line for loaded work, so the layout never jumps under the finger.
            LoadMode.EXTERNAL -> mass(load ?: 0.0, units)
            else -> null
        }
    }

    /** Two-arm pick-ups log the total of both hands; this says what goes on each block (`perBlockText`). */
    fun perBlockText(set: ExerciseSet, def: ExerciseDefinition, units: UnitSystem): TrText? {
        if (def.load != LoadMode.EXTERNAL || def.unilateral || EquipmentV2.PICKUP_BLOCK_PAIR !in def.equipment) return null
        val total = set.loadKg?.takeIf { it > 0 } ?: return null
        return t("trw_load_per_block", x(mass(total / 2, units)))
    }

    /** Effective load in % body weight for loaded hangs and lifts (`percentText`). */
    fun percentText(set: ExerciseSet, def: ExerciseDefinition, bodyweight: Double?): TrText? {
        if (def.load != LoadMode.BODYWEIGHT_PLUS && def.load != LoadMode.EXTERNAL) return null
        if (def.kind != ExerciseKind.HANG && def.kind != ExerciseKind.INTERVAL && def.kind != ExerciseKind.LOAD_REPS) return null
        val pct = StrengthMath.percentBodyweight(def.load, set.loadKg ?: 0.0, set.bodyweightKg ?: bodyweight) ?: return null
        return t("tr_format_percent_bw", i(pct.roundToInt()))
    }

    /** One line for previews: "8 reps · +12 kg · 20 mm" (`targetSummary`). */
    fun targetSummary(set: ExerciseSet, def: ExerciseDefinition, bodyweight: Double?, units: UnitSystem): TrText? {
        val parts = listOfNotNull(
            mainValue(set, def),
            loadText(set, def, units)?.takeIf { def.load != LoadMode.BODYWEIGHT_PLUS || (set.loadKg ?: 0.0) != 0.0 },
            percentText(set, def, bodyweight),
            set.edgeMm?.let { t("tr_format_mm", s(it.roundToInt().toString())) },
        )
        return parts.takeIf { it.isNotEmpty() }?.let { join(it) }
    }

    /** "max. 1 rep with +18 kg", "10 s with 74 kg (109 % BW)", "max. 14 reps", "max. 60 s" (`capacityText`). */
    fun capacityText(def: ExerciseDefinition, capacity: Capacity, units: UnitSystem): TrText {
        val bw = capacity.bodyweightKg
        return when (capacity.kind) {
            CapacityKind.E1RM_TOTAL -> {
                val shown = if (def.load == LoadMode.BODYWEIGHT_PLUS && bw != null) mass(capacity.value - bw, units, signed = true)
                    else mass(capacity.value, units)
                t("trp_capacity_e1rm", x(shown))
            }
            CapacityKind.TEN_SECOND_MAX -> {
                val massText = mass(capacity.value, units)
                // Rounded like the set's own "% BW" line.
                val pct = bw?.takeIf { it > 0 }?.let { (capacity.value / it * 100).roundToInt() }
                if (pct != null) t("trp_capacity_ten_pct", x(massText), x(t("tr_format_percent_bw", i(pct))))
                else t("trp_capacity_ten", x(massText))
            }
            CapacityKind.MAX_REPS -> p("trp_capacity_reps", capacity.value.toInt(), i(capacity.value.toInt()))
            CapacityKind.MAX_SECONDS -> t("trp_capacity_seconds", i(capacity.value.toInt()))
        }
    }

    /** "Keine Reserve → nächste Sätze −2,5 kg" (`adjustmentText`). */
    fun adjustmentText(adj: SetAdjustment, units: UnitSystem, side: Side? = null): TrText {
        val reason = when (adj.reason) {
            AdjustReason.MISSED_REPS -> "trp2_adj_missed"
            AdjustReason.NO_RESERVE -> "trp2_adj_no_reserve"
            AdjustReason.BIG_RESERVE -> "trp2_adj_big_reserve"
            AdjustReason.HOLD_FAILED -> "trp2_adj_hold_failed"
            AdjustReason.HOLD_EASY -> "trp2_adj_hold_easy"
        }
        val change = join(buildList {
            if (abs(adj.loadDeltaKg) > 1e-9) add(mass(adj.loadDeltaKg, units, signed = true))
            if (adj.repsDelta != 0) add(t("trp2_adj_reps", i(adj.repsDelta)))
            if (abs(adj.durationDeltaS) > 1e-9) add(t("trp2_adj_seconds", i(round(adj.durationDeltaS).toInt())))
        })
        return if (side != null) t("trp2_adj_line_side", k(reason),
            k(if (side == Side.LEFT) "trp2_side_left_lower" else "trp2_side_right_lower"), x(change))
        else t("trp2_adj_line", k(reason), x(change))
    }

    /** A record's value (`recordValueText`). */
    fun recordValueText(def: ExerciseDefinition, record: PersonalRecord, units: UnitSystem): TrText = when (record.kind) {
        RecordKind.LOAD ->
            if (def.load == LoadMode.BODYWEIGHT_PLUS) t("trw_record_total", x(mass(record.value, units)))
            else mass(record.value, units)
        RecordKind.ESTIMATED_MAX -> t("trw_record_e1rm", x(mass(record.value, units)))
        RecordKind.REPS -> record.value.roundToInt().let { p("trw_reps", it, i(it)) }
        RecordKind.DURATION -> seconds(record.value.roundToInt())
    }

    /** "Ring rows instead of pull-ups" / "Without plank" (`keptChangeText`). */
    fun keptChangeText(replaced: String, chosen: String?, catalog: com.cruxcoach.athlete.catalog.ExerciseCatalog, language: String): TrText =
        if (chosen != null) t("trk_instead", s(catalog.fallbackFor(chosen).name(language)), s(catalog.fallbackFor(replaced).name(language)))
        else t("trk_without", s(catalog.fallbackFor(replaced).name(language)))
}
