package com.cruxcoach.android.ui.training

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.logic.LoadBasis
import com.cruxcoach.athlete.logic.LoadRules
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.UnitSystem

// ── Weight fields (issue #58) ────────────────────────────────────────
// One wording per case, the same on every screen: the label says what the number refers to
// ([LoadRules]), the value reads the way it was entered.

/** "Weight per dumbbell", "Added weight", "Total incl. bar", …; null for exercises without a weight. */
@StringRes
fun weightLabelRes(def: ExerciseDefinition): Int? = when (LoadRules.basis(def)) {
    LoadBasis.NONE -> null
    LoadBasis.ADDED -> R.string.tr_weight_added
    LoadBasis.PER_DUMBBELL -> R.string.tr_weight_per_dumbbell
    LoadBasis.PER_KETTLEBELL -> R.string.tr_weight_per_kettlebell
    LoadBasis.DUMBBELL -> R.string.tr_weight_dumbbell
    LoadBasis.KETTLEBELL -> R.string.tr_weight_kettlebell
    LoadBasis.BARBELL -> R.string.tr_weight_barbell
    LoadBasis.PICKUP -> R.string.tr_weight_pickup
    LoadBasis.PICKUP_BOTH_HANDS -> R.string.tr_weight_pickup_both
    LoadBasis.STACK -> R.string.tr_weight_stack
    LoadBasis.FORCE -> R.string.tr_weight_force
    LoadBasis.WEIGHT -> R.string.tr_weight
}

@Composable
fun weightLabel(def: ExerciseDefinition): String = stringResource(weightLabelRes(def) ?: R.string.tr_weight)

/** Title of the dialog that types a weight: "Weight per dumbbell in kg". */
@Composable
fun weightInputTitle(def: ExerciseDefinition, units: UnitSystem): String =
    stringResource(R.string.tr_weight_input, weightLabel(def), Units.massUnit(units))

private fun mass(resources: Resources, kg: Double, units: UnitSystem, signed: Boolean = false): String {
    val v = Units.massToDisplay(kg, units)
    val text = (if (signed && v > 0) "+" else "") + formatNumber(v, massDecimals(v))
    return resources.getString(if (units == UnitSystem.IMPERIAL) R.string.tr_format_lb else R.string.tr_format_kg, text)
}

/**
 * A logged or planned weight the way it is entered: "2 × 16 kg" for a pair of dumbbells, "+12 kg",
 * "15 kg assisted" and "Bodyweight" for body-weight work, "60 kg" otherwise.
 */
fun weightText(resources: Resources, def: ExerciseDefinition, loadKg: Double, units: UnitSystem): String =
    when (LoadRules.basis(def)) {
        LoadBasis.ADDED -> when {
            loadKg == 0.0 -> resources.getString(R.string.tr_bodyweight)
            loadKg < 0 -> resources.getString(R.string.trw_load_assisted, mass(resources, -loadKg, units))
            else -> mass(resources, loadKg, units, signed = true)
        }
        LoadBasis.PER_DUMBBELL, LoadBasis.PER_KETTLEBELL ->
            resources.getString(R.string.tr_format_pair, LoadRules.implements(def), mass(resources, loadKg, units))
        else -> mass(resources, loadKg, units)
    }

@Composable
fun weightText(def: ExerciseDefinition, loadKg: Double, units: UnitSystem): String =
    weightText(LocalResources.current, def, loadKg, units)

/** A total lifted weight (a one-rep max, a record) in the form it is entered: per dumbbell for a pair. */
fun weightTextOfTotal(resources: Resources, def: ExerciseDefinition, totalKg: Double, units: UnitSystem): String =
    weightText(resources, def, LoadRules.logged(def, totalKg), units)

@Composable
fun weightTextOfTotal(def: ExerciseDefinition, totalKg: Double, units: UnitSystem): String =
    weightTextOfTotal(LocalResources.current, def, totalKg, units)

/** A change of the logged weight: "+2.5 kg", for a pair "+1 kg per dumbbell". */
@Composable
fun weightDeltaText(def: ExerciseDefinition, deltaKg: Double, units: UnitSystem): String {
    val change = formatMass(deltaKg, units, signed = true)
    return when (LoadRules.basis(def)) {
        LoadBasis.PER_DUMBBELL -> stringResource(R.string.tr_format_per_dumbbell, change)
        LoadBasis.PER_KETTLEBELL -> stringResource(R.string.tr_format_per_kettlebell, change)
        else -> change
    }
}
