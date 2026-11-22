package com.cruxcoach.android.ui.training.today

/**
 * The bar of Today's food tile belongs to its big number (device walk 2026-11-22: the number was the energy, the bar
 * the protein): energy of its goal where that is shown, protein of its target where calories are hidden, and none
 * where the number has nothing to be measured against.
 */
internal fun foodTileProgress(showCalories: Boolean, kcal: Int, energyGoal: Int?, protein: Int, proteinTarget: Int?): Float? = when {
    showCalories && energyGoal != null -> if (energyGoal > 0) kcal.toFloat() / energyGoal else 0f
    showCalories -> null
    proteinTarget != null -> if (proteinTarget > 0) protein.toFloat() / proteinTarget else 0f
    else -> null
}
