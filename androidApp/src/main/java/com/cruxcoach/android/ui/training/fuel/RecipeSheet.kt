package com.cruxcoach.android.ui.training.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.training.formatNumber
import com.cruxcoach.android.ui.training.parseDecimal
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.Recipe
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.roundToInt

/** A recipe being written or changed; [id] is null until it was saved once. */
data class RecipeDraft(
    val id: String?,
    val name: String = "",
    val portions: String = "2",
    /** Weight of the finished dish in the athlete's weight unit, as typed. */
    val cooked: String = "",
    val ingredients: List<Recipe.Ingredient> = emptyList(),
) {
    fun recipe(units: UnitSystem): Recipe? {
        val count = portions.trim().toIntOrNull()?.takeIf { it in 1..50 } ?: return null
        if (name.isBlank() || ingredients.isEmpty()) return null
        val cookedG = parseDecimal(cooked)?.takeIf { it > 0 }?.let { FuelUnits.toBase(it, FuelUnits.unitFor(units, drink = false)) }
        return Recipe(name.trim(), count, ingredients, cookedG)
    }

    companion object {
        fun of(id: String, recipe: Recipe, units: UnitSystem) = RecipeDraft(
            id = id, name = recipe.name, portions = recipe.portions.toString(),
            cooked = recipe.cookedWeightG?.let { inputText(it, FuelUnits.unitFor(units, drink = false)) }.orEmpty(),
            ingredients = recipe.ingredients,
        )
    }
}

/**
 * Writes a recipe: name, ingredients with their raw weight, portions and, if
 * weighed, the finished dish. It is saved as one of "my foods" and logged by
 * the portion (FEAT-069).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeSheet(
    draft: RecipeDraft,
    units: UnitSystem,
    showCalories: Boolean,
    onChange: (RecipeDraft) -> Unit,
    onAddIngredient: () -> Unit,
    onSave: (Recipe) -> Unit,
    onDismiss: () -> Unit,
) {
    val weightUnit = FuelUnits.unitFor(units, drink = false)
    val recipe = draft.recipe(units)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("fuel_recipe_sheet")) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.trf_recipe_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = draft.name, onValueChange = { onChange(draft.copy(name = it.take(120))) }, singleLine = true,
                label = { Text(stringResource(R.string.trf_recipe_name)) },
                modifier = Modifier.fillMaxWidth().testTag("fuel_recipe_name"),
            )
            Text(stringResource(R.string.trf_recipe_ingredients), style = MaterialTheme.typography.labelLarge)
            draft.ingredients.forEachIndexed { i, ingredient ->
                ListItem(
                    headlineContent = { Text(ingredient.name) },
                    supportingContent = { Text(amountText(ingredient.grams, weightUnit)) },
                    trailingContent = {
                        IconButton(
                            onClick = { onChange(draft.copy(ingredients = draft.ingredients.filterIndexed { j, _ -> j != i })) },
                            modifier = Modifier.testTag("fuel_recipe_remove_$i"),
                        ) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.tr_action_delete)) }
                    },
                    modifier = Modifier.testTag("fuel_recipe_ingredient_$i"),
                )
            }
            OutlinedButton(onClick = onAddIngredient, modifier = Modifier.testTag("fuel_recipe_add")) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.trf_recipe_add))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft.portions, onValueChange = { onChange(draft.copy(portions = it.take(2))) }, singleLine = true,
                    label = { Text(stringResource(R.string.trf_recipe_portions)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("fuel_recipe_portions"),
                )
                OutlinedTextField(
                    value = draft.cooked, onValueChange = { onChange(draft.copy(cooked = it.take(6))) }, singleLine = true,
                    label = { Text(stringResource(R.string.trf_recipe_cooked, unitLabel(weightUnit))) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1.4f).testTag("fuel_recipe_cooked"),
                )
            }
            Text(stringResource(R.string.trf_recipe_cooked_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            recipe?.let { r ->
                val item = r.toFoodItem("draft", 0)
                val serving = item.servingG
                if (serving != null) {
                    val f = serving / 100.0
                    fun g(v: Double?) = v?.let { formatNumber(it * f, 0) } ?: "–"
                    val kcal = item.kcalPer100?.let { (it * f).roundToInt().toString() }
                    Text(
                        stringResource(R.string.trf_recipe_per_portion, amountText(serving, weightUnit),
                            g(item.proteinPer100), g(item.carbsPer100), g(item.fatPer100)) +
                            (if (showCalories && kcal != null) " · $kcal kcal" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("fuel_recipe_summary"),
                    )
                }
            }
            Button(
                onClick = { recipe?.let(onSave) },
                enabled = recipe != null,
                modifier = Modifier.fillMaxWidth().testTag("fuel_recipe_save"),
            ) { Text(stringResource(R.string.tr_action_save)) }
        }
    }
}
