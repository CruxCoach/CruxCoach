package com.cruxcoach.android.ui.training.fuel

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.training.formatNumber
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.FuelUnits.Amount
import com.cruxcoach.athlete.logic.Recipe
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.UnitSystem

/** Short unit name: g, ml, oz, fl oz, cups. */
@Composable
fun unitLabel(unit: Amount): String = stringResource(
    when (unit) {
        Amount.G -> R.string.fvp_grams
        Amount.ML -> R.string.fvp_ml
        Amount.OZ -> R.string.trf_unit_oz
        Amount.FL_OZ -> R.string.trf_unit_floz
        Amount.CUP -> R.string.trf_unit_cups
    },
)

/** [base] grams or ml as the number to edit in [unit]: whole g/ml, tenths of an ounce, quarter cups. */
fun inputText(base: Double, unit: Amount): String {
    val value = FuelUnits.fromBase(base, unit)
    return when (unit) {
        Amount.G, Amount.ML -> formatNumber(value, 0)
        Amount.OZ, Amount.FL_OZ -> formatNumber(value, 1)
        Amount.CUP -> formatNumber(Math.round(value * 4) / 4.0, 2)
    }
}

/** An amount with its unit in the athlete's units: "120 g", "4.2 oz", "8 fl oz". */
@Composable
fun amountText(base: Double, unit: Amount): String {
    val value = FuelUnits.fromBase(base, unit)
    val number = when (unit) {
        Amount.G, Amount.ML -> grams(value)
        else -> formatNumber(value, 1)
    }
    return stringResource(R.string.trf_amount_with_unit, number, unitLabel(unit))
}

/**
 * How much of a food was logged, or will be with one tap, in the same words wherever it shows: a dish's row in the add
 * sheet, the confirmation after the tap and the day's list (Nokia check 2026-11-22: the row said "1 portion", the
 * confirmation and the list "200 g").
 *
 * A dish is eaten by the portion, so its portions lead and their weight follows: "1 portion (200 g)",
 * "1.5 × portion (300 g)"; typed in grams it is the weight alone. Any other food reads by its weight when one is known
 * ("80 g"), else by its portions ("2 × portion"); one portion of a food without a weight needs no words (null).
 */
@Composable
fun loggedAmountText(portions: Double?, grams: Double?, unit: Amount, dish: Boolean): String? {
    val weight = grams?.let { amountText(it, unit) }
    val counted = portions?.takeIf { it > 0 }?.let { count ->
        when {
            count != 1.0 -> stringResource(R.string.trf_entry_portions, formatNumber(count))
            dish -> stringResource(R.string.trf_one_portion)
            else -> null
        }
    }
    return when {
        dish && counted != null && weight != null -> stringResource(R.string.trf_amount_portions_weight, counted, weight)
        dish && counted != null -> counted
        weight != null -> weight
        else -> counted
    }
}

/**
 * Whether a saved food is measured by volume. A dish is not judged by its name: the name lists its ingredients
 * ("Eier + Banane + Milch"), and one drink among them made the whole dish read "1 portion (440 ml)" (device check
 * 2026-11-25). A dish is a drink only when its serving says so.
 */
fun isDrink(food: FoodItem): Boolean =
    if (food.source == Recipe.SOURCE) FuelUnits.isDrink(null, "", food.servingLabel)
    else FuelUnits.isDrink(food.id, food.name, food.servingLabel)

/**
 * [loggedAmountText] of a logged entry. [food] is its saved food while there is one: it says whether the entry is a
 * dish, and a drink by its label is measured in ml.
 */
@Composable
fun entryAmountText(entry: FoodLogEntry, food: FoodItem?, units: UnitSystem): String? {
    val drink = food?.let { isDrink(it) } ?: FuelUnits.isDrink(entry.foodItemId, entry.name)
    return loggedAmountText(entry.portions, entry.amountG, FuelUnits.unitFor(units, drink), dish = food?.source == Recipe.SOURCE)
}
