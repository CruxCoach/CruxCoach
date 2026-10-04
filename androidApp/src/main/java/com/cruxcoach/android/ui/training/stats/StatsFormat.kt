package com.cruxcoach.android.ui.training.stats

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.training.formatMass
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.CapacityKind
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.abs
import kotlin.math.roundToInt

/** Time windows of the stats screens. */
enum class StatsRange(val days: Int, val weeks: Int) {
    FOUR_WEEKS(28, 4), TWELVE_WEEKS(84, 12), SIX_MONTHS(182, 26), YEAR(365, 52)
}

@Composable
fun rangeLabel(r: StatsRange): String = stringResource(when (r) {
    StatsRange.FOUR_WEEKS -> R.string.trs_range_4w
    StatsRange.TWELVE_WEEKS -> R.string.trs_range_12w
    StatsRange.SIX_MONTHS -> R.string.trs_range_6m
    StatsRange.YEAR -> R.string.trs_range_1y
})

/** What the capacity number of an exercise means ("e1RM", "10-s-Max", …). */
@Composable
fun capacityLabel(kind: CapacityKind?): String = stringResource(when (kind) {
    CapacityKind.E1RM_TOTAL -> R.string.trs_kind_e1rm
    CapacityKind.TEN_SECOND_MAX -> R.string.trs_kind_ten_second
    CapacityKind.MAX_REPS -> R.string.trs_kind_reps
    CapacityKind.MAX_SECONDS -> R.string.trs_kind_seconds
    null -> R.string.trs_kind_volume
})

/** A display value (see ProgressStats.displayValue) in the athlete's units. */
@Composable
fun capacityText(def: ExerciseDefinition, kind: CapacityKind?, value: Double, units: UnitSystem): String = when (kind) {
    CapacityKind.E1RM_TOTAL, CapacityKind.TEN_SECOND_MAX ->
        if (def.load == LoadMode.BODYWEIGHT_PLUS) formatMass(value, units, signed = true) else formatMass(value, units)
    CapacityKind.MAX_REPS -> value.roundToInt().let { pluralStringResource(R.plurals.trs_reps, it, it) }
    CapacityKind.MAX_SECONDS -> stringResource(R.string.tr_format_seconds, value.roundToInt())
    null -> value.roundToInt().toString()
}

/** "+2.5 kg in 4 weeks" / "−1 rep" — change against the session four weeks earlier. */
@Composable
fun changeText(def: ExerciseDefinition, kind: CapacityKind?, change: Double, units: UnitSystem): String {
    val arrow = when {
        abs(change) < 1e-6 -> "→"
        change > 0 -> "↑"
        else -> "↓"
    }
    val amount = when (kind) {
        CapacityKind.E1RM_TOTAL, CapacityKind.TEN_SECOND_MAX -> formatMass(change, units, signed = true)
        CapacityKind.MAX_REPS -> (if (change > 0) "+" else "") + change.roundToInt()
        CapacityKind.MAX_SECONDS -> (if (change > 0) "+" else "") + stringResource(R.string.tr_format_seconds, change.roundToInt())
        null -> change.roundToInt().toString()
    }
    return "$arrow $amount"
}
