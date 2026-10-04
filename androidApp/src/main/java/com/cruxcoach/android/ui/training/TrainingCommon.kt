package com.cruxcoach.android.ui.training

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.logic.DayLoad
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.UnitSystem
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Navigation targets of the training, body and fueling area. */
object TrainingRoutes {
    const val TODAY = "today"
    const val EXERCISES = "exercises?pickFor={pickFor}"
    fun exercises(pickForWorkout: String? = null) = if (pickForWorkout == null) "exercises" else "exercises?pickFor=$pickForWorkout"
    const val EXERCISE_DETAIL = "exercise/{slug}"
    fun exerciseDetail(slug: String) = "exercise/$slug"
    const val WORKOUT = "workout"
    const val WORKOUT_SUMMARY = "workout_summary/{workoutId}"
    fun workoutSummary(id: String) = "workout_summary/$id"
    const val ROUTINES = "routines"
    const val TRAINING_HISTORY = "training_history"
    const val BODY = "body"
    const val FUEL = "fuel"
    const val INJURIES = "injuries"
    const val WEEKLY_REVIEW = "weekly_review"
    const val ATHLETE_SETTINGS = "athlete_settings"
    const val CUSTOM_EXERCISE = "custom_exercise"
}

/** Language for catalogue texts: German UI → German texts, everything else English. */
@Composable
fun catalogLanguage(): String {
    val locale = LocalConfiguration.current.locales.get(0) ?: Locale.getDefault()
    return if (locale.language == "de") "de" else "en"
}

// ── Labels ───────────────────────────────────────────────────────────

@Composable
fun categoryLabel(c: ExerciseCategoryV2): String = stringResource(when (c) {
    ExerciseCategoryV2.FINGER -> R.string.tr_cat_finger
    ExerciseCategoryV2.PULL -> R.string.tr_cat_pull
    ExerciseCategoryV2.PUSH -> R.string.tr_cat_push
    ExerciseCategoryV2.ANTAGONIST -> R.string.tr_cat_antagonist
    ExerciseCategoryV2.CORE -> R.string.tr_cat_core
    ExerciseCategoryV2.LEGS -> R.string.tr_cat_legs
    ExerciseCategoryV2.MOBILITY -> R.string.tr_cat_mobility
    ExerciseCategoryV2.WARMUP -> R.string.tr_cat_warmup
    ExerciseCategoryV2.POWER -> R.string.tr_cat_power
    ExerciseCategoryV2.ENDURANCE -> R.string.tr_cat_endurance
    ExerciseCategoryV2.TECHNIQUE -> R.string.tr_cat_technique
})

@Composable
fun equipmentLabel(e: EquipmentV2): String = stringResource(when (e) {
    EquipmentV2.NONE -> R.string.tr_eq_none
    EquipmentV2.MAT -> R.string.tr_eq_mat
    EquipmentV2.HANGBOARD -> R.string.tr_eq_hangboard
    EquipmentV2.PICKUP_BLOCK -> R.string.tr_eq_pickup_block
    EquipmentV2.PULL_UP_BAR -> R.string.tr_eq_pull_up_bar
    EquipmentV2.RINGS -> R.string.tr_eq_rings
    EquipmentV2.DUMBBELL -> R.string.tr_eq_dumbbell
    EquipmentV2.KETTLEBELL -> R.string.tr_eq_kettlebell
    EquipmentV2.BARBELL -> R.string.tr_eq_barbell
    EquipmentV2.PLATES -> R.string.tr_eq_plates
    EquipmentV2.BANDS -> R.string.tr_eq_bands
    EquipmentV2.BENCH -> R.string.tr_eq_bench
    EquipmentV2.BOX -> R.string.tr_eq_box
    EquipmentV2.CABLE -> R.string.tr_eq_cable
    EquipmentV2.WALL -> R.string.tr_eq_wall
    EquipmentV2.BOARD -> R.string.tr_eq_board
    EquipmentV2.CAMPUS_BOARD -> R.string.tr_eq_campus_board
    EquipmentV2.FOAM_ROLLER -> R.string.tr_eq_foam_roller
    EquipmentV2.PULLEY -> R.string.tr_eq_pulley
    EquipmentV2.DIP_BARS -> R.string.tr_eq_dip_bars
})

@Composable
fun domainLabel(d: LoadDomain): String = stringResource(when (d) {
    LoadDomain.FINGER -> R.string.tr_dom_finger
    LoadDomain.WRIST -> R.string.tr_dom_wrist
    LoadDomain.ELBOW -> R.string.tr_dom_elbow
    LoadDomain.SHOULDER -> R.string.tr_dom_shoulder
    LoadDomain.SKIN -> R.string.tr_dom_skin
    LoadDomain.LOWER_BODY -> R.string.tr_dom_lower_body
    LoadDomain.BACK -> R.string.tr_dom_back
    LoadDomain.SYSTEMIC -> R.string.tr_dom_systemic
})

@Composable
fun gripLabel(g: Grip): String = stringResource(when (g) {
    Grip.HALF_CRIMP -> R.string.tr_grip_half_crimp
    Grip.OPEN_HAND -> R.string.tr_grip_open_hand
    Grip.FULL_CRIMP -> R.string.tr_grip_full_crimp
    Grip.THREE_FINGER_DRAG -> R.string.tr_grip_three_finger_drag
    Grip.FRONT_TWO -> R.string.tr_grip_front_two
    Grip.MIDDLE_TWO -> R.string.tr_grip_middle_two
    Grip.PINCH -> R.string.tr_grip_pinch
    Grip.SLOPER -> R.string.tr_grip_sloper
    Grip.JUG -> R.string.tr_grip_jug
})

@Composable
fun regionLabel(r: InjuryRegion): String = stringResource(when (r) {
    InjuryRegion.FINGER -> R.string.tr_region_finger
    InjuryRegion.WRIST -> R.string.tr_region_wrist
    InjuryRegion.ELBOW -> R.string.tr_region_elbow
    InjuryRegion.SHOULDER -> R.string.tr_region_shoulder
    InjuryRegion.BACK -> R.string.tr_region_back
    InjuryRegion.HIP -> R.string.tr_region_hip
    InjuryRegion.KNEE -> R.string.tr_region_knee
    InjuryRegion.ANKLE -> R.string.tr_region_ankle
    InjuryRegion.SKIN -> R.string.tr_region_skin
    InjuryRegion.OTHER -> R.string.tr_region_other
})

@Composable
fun sideLabel(s: Side): String = stringResource(if (s == Side.LEFT) R.string.tr_side_left else R.string.tr_side_right)

@Composable
fun injurySideLabel(s: InjurySide): String = stringResource(when (s) {
    InjurySide.LEFT -> R.string.tr_side_left
    InjurySide.RIGHT -> R.string.tr_side_right
    InjurySide.BOTH -> R.string.tr_side_both
})

@Composable
fun metricLabel(m: BodyMetric): String = stringResource(when (m) {
    BodyMetric.WEIGHT -> R.string.tr_metric_weight
    BodyMetric.BODY_FAT -> R.string.tr_metric_body_fat
    BodyMetric.HEIGHT -> R.string.tr_metric_height
    BodyMetric.ARM_SPAN -> R.string.tr_metric_arm_span
    BodyMetric.FOREARM -> R.string.tr_metric_forearm
    BodyMetric.UPPER_ARM -> R.string.tr_metric_upper_arm
    BodyMetric.CHEST -> R.string.tr_metric_chest
    BodyMetric.WAIST -> R.string.tr_metric_waist
    BodyMetric.HIPS -> R.string.tr_metric_hips
    BodyMetric.THIGH -> R.string.tr_metric_thigh
    BodyMetric.CALF -> R.string.tr_metric_calf
    BodyMetric.NECK -> R.string.tr_metric_neck
})

@Composable
fun mealLabel(m: Meal): String = stringResource(when (m) {
    Meal.BREAKFAST -> R.string.tr_meal_breakfast
    Meal.LUNCH -> R.string.tr_meal_lunch
    Meal.DINNER -> R.string.tr_meal_dinner
    Meal.SNACK -> R.string.tr_meal_snack
    Meal.PRE_TRAINING -> R.string.tr_meal_pre_training
    Meal.POST_TRAINING -> R.string.tr_meal_post_training
})

@Composable
fun dayLoadLabel(d: DayLoad): String = stringResource(when (d) {
    DayLoad.REST -> R.string.tr_dayload_rest
    DayLoad.LIGHT -> R.string.tr_dayload_light
    DayLoad.MODERATE -> R.string.tr_dayload_moderate
    DayLoad.HARD -> R.string.tr_dayload_hard
    DayLoad.VERY_HARD -> R.string.tr_dayload_very_hard
})

@Composable
fun setTypeLabel(t: SetType): String = stringResource(when (t) {
    SetType.WARMUP -> R.string.tr_settype_warmup
    SetType.WORK -> R.string.tr_settype_work
    SetType.TEST -> R.string.tr_settype_test
})

/** Built-in routines carry a key; their names live in resources. */
@Composable
fun routineName(r: Routine): String = builtinRoutineText(r.builtinKey)?.first?.let { stringResource(it) } ?: r.name

@Composable
fun routineDescription(r: Routine): String? = builtinRoutineText(r.builtinKey)?.second?.let { stringResource(it) } ?: r.notes

private fun builtinRoutineText(key: String?): Pair<Int, Int>? = when (key) {
    "warmup_board" -> R.string.tr_routine_warmup_board to R.string.tr_routine_warmup_board_desc
    "finger_basics" -> R.string.tr_routine_finger_basics to R.string.tr_routine_finger_basics_desc
    "injury_one_arm" -> R.string.tr_routine_injury_one_arm to R.string.tr_routine_injury_one_arm_desc
    "pull_antagonist" -> R.string.tr_routine_pull_antagonist to R.string.tr_routine_pull_antagonist_desc
    "antagonist_15" -> R.string.tr_routine_antagonist_15 to R.string.tr_routine_antagonist_15_desc
    "legs_basics" -> R.string.tr_routine_legs_basics to R.string.tr_routine_legs_basics_desc
    "core_climber" -> R.string.tr_routine_core_climber to R.string.tr_routine_core_climber_desc
    "mobility_10" -> R.string.tr_routine_mobility_10 to R.string.tr_routine_mobility_10_desc
    "baseline_tests" -> R.string.tr_routine_baseline_tests to R.string.tr_routine_baseline_tests_desc
    else -> null
}

// ── Formatting ───────────────────────────────────────────────────────

/** "12.5", "-10", "7" — trims trailing zeros, one decimal max. */
fun formatNumber(value: Double, decimals: Int = 1): String {
    val factor = if (decimals <= 0) 1.0 else Math.pow(10.0, decimals.toDouble())
    val rounded = (value * factor).roundToInt() / factor
    return if (abs(rounded - rounded.roundToInt()) < 1e-9) rounded.roundToInt().toString()
    else String.format(Locale.getDefault(), "%.${decimals}f", rounded)
}

@Composable
fun formatMass(kg: Double, units: UnitSystem, signed: Boolean = false): String {
    val v = Units.massToDisplay(kg, units)
    val text = (if (signed && v > 0) "+" else "") + formatNumber(v)
    return stringResource(if (units == UnitSystem.IMPERIAL) R.string.tr_format_lb else R.string.tr_format_kg, text)
}

@Composable
fun formatLength(cm: Double, units: UnitSystem): String {
    val v = Units.lengthToDisplay(cm, units)
    return stringResource(if (units == UnitSystem.IMPERIAL) R.string.tr_format_in else R.string.tr_format_cm, formatNumber(v))
}

fun formatClock(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

/** Parses user input with comma or dot decimals; null when empty/invalid. */
fun parseDecimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

// ── Building blocks ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title, maxLines = 1) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack, modifier = Modifier.testTag("training_back")) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        }
                    },
                    actions = actions,
                )
                RestTimerBannerSlot()
            }
        },
        floatingActionButton = floatingActionButton,
        snackbarHost = snackbarHost,
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, supporting: String? = null) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(24.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
