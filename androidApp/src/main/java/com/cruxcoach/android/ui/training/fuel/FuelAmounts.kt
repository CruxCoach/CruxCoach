package com.cruxcoach.android.ui.training.fuel

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.training.formatNumber
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.FuelUnits.Amount

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
