package com.cruxcoach.android.ui.training.fuel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.training.EmptyHint
import com.cruxcoach.android.ui.training.formatNumber
import com.cruxcoach.android.ui.training.mealLabel
import com.cruxcoach.android.ui.training.parseDecimal
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.Meal

/** Meal choice as a wrapping row of chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MealPicker(selected: Meal, onSelect: (Meal) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Meal.entries.forEach { meal ->
            FilterChip(
                selected = selected == meal,
                onClick = { onSelect(meal) },
                label = { Text(mealLabel(meal)) },
                modifier = Modifier.testTag("fuel_meal_${meal.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, tag: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val invalid = value.isNotBlank() && (parseDecimal(value)?.let { it < 0 } ?: true)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.testTag(tag),
    )
}

private fun optionalAmount(text: String): Double? = parseDecimal(text)?.takeIf { it >= 0 }
private fun fieldValid(text: String) = text.isBlank() || optionalAmount(text) != null

/** Quick entry: a name and any subset of nutrients. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickAddSheet(
    initialMeal: Meal,
    onDismiss: () -> Unit,
    onSave: (name: String, meal: Meal, nutrients: Nutrients, remember: Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var meal by rememberSaveable { mutableStateOf(initialMeal) }
    var protein by rememberSaveable { mutableStateOf("") }
    var carbs by rememberSaveable { mutableStateOf("") }
    var fat by rememberSaveable { mutableStateOf("") }
    var kcal by rememberSaveable { mutableStateOf("") }
    var remember by rememberSaveable { mutableStateOf(false) }
    val defaultName = stringResource(R.string.trf_qa_default_name)
    val valid = listOf(protein, carbs, fat, kcal).all(::fieldValid) && (!remember || name.isNotBlank())

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("fuel_quick_add_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.trf_qa_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(120) },
                label = { Text(stringResource(R.string.trf_qa_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("fuel_qa_name"),
            )
            Text(stringResource(R.string.trf_qa_meal), style = MaterialTheme.typography.labelLarge)
            MealPicker(meal) { meal = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.trf_qa_protein), protein, "fuel_qa_protein", Modifier.weight(1f)) { protein = it }
                NumberField(stringResource(R.string.trf_qa_carbs), carbs, "fuel_qa_carbs", Modifier.weight(1f)) { carbs = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.trf_qa_fat), fat, "fuel_qa_fat", Modifier.weight(1f)) { fat = it }
                NumberField(stringResource(R.string.trf_qa_kcal), kcal, "fuel_qa_kcal", Modifier.weight(1f)) { kcal = it }
            }
            Text(stringResource(R.string.trf_qa_optional), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { remember = !remember }) {
                Checkbox(checked = remember, onCheckedChange = { remember = it }, modifier = Modifier.testTag("fuel_qa_remember"))
                Text(stringResource(R.string.trf_qa_remember))
            }
            Button(
                onClick = {
                    onSave(name.trim().ifBlank { defaultName }, meal,
                        Nutrients(optionalAmount(kcal), optionalAmount(protein), optionalAmount(carbs), optionalAmount(fat)), remember)
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().testTag("fuel_qa_save"),
            ) { Text(stringResource(R.string.tr_action_save)) }
        }
    }
}

/** Saved foods: favourites first, then most used; search; star; create; tap to log. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodsSheet(
    foods: List<FoodItem>,
    onDismiss: () -> Unit,
    onPick: (FoodItem) -> Unit,
    onToggleFavorite: (FoodItem) -> Unit,
    onDelete: (FoodItem) -> Unit,
    onCreate: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    // The repository already orders favourites first, then by use count.
    val visible = foods.filter { q.isEmpty() || it.name.lowercase().contains(q) || (it.brand?.lowercase()?.contains(q) == true) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("fuel_foods_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trf_my_foods), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onCreate, modifier = Modifier.testTag("fuel_food_new")) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.trf_foods_new))
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                label = { Text(stringResource(R.string.trf_foods_search)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("fuel_foods_search"),
            )
            if (visible.isEmpty()) {
                EmptyHint(stringResource(R.string.trf_foods_empty))
            } else {
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    items(visible, key = { it.id }) { item ->
                        ListItem(
                            headlineContent = { Text(item.name) },
                            supportingContent = {
                                val basis = stringResource(if (FuelViewModel.isPerPortion(item)) R.string.trf_food_per_portion else R.string.trf_food_per_100)
                                val macros = stringResource(R.string.trf_entry_macros,
                                    item.proteinPer100?.let { formatNumber(it) } ?: "–",
                                    item.carbsPer100?.let { formatNumber(it) } ?: "–",
                                    item.fatPer100?.let { formatNumber(it) } ?: "–")
                                Text(listOfNotNull(item.brand, "$macros ($basis)").joinToString(" · "))
                            },
                            trailingContent = {
                                Row {
                                    IconButton(onClick = { onToggleFavorite(item) }, modifier = Modifier.testTag("fuel_food_star_${item.id}")) {
                                        Icon(
                                            if (item.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                                            contentDescription = stringResource(if (item.favorite) R.string.trf_food_unfavorite else R.string.trf_food_favorite),
                                        )
                                    }
                                    IconButton(onClick = { onDelete(item) }) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.trf_food_delete))
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onPick(item) }.testTag("fuel_food_${item.id}"),
                        )
                    }
                }
            }
        }
    }
}

/** Amount for a saved food: portions always; grams when the food has per-100 g values. */
@Composable
fun AmountDialog(
    item: FoodItem,
    initialMeal: Meal,
    onDismiss: () -> Unit,
    onConfirm: (meal: Meal, portions: Double?, grams: Double?) -> Unit,
) {
    val perPortion = FuelViewModel.isPerPortion(item)
    val gramsPossible = !perPortion
    var useGrams by rememberSaveable { mutableStateOf(gramsPossible && item.servingG == null) }
    var portions by rememberSaveable { mutableStateOf("1") }
    var grams by rememberSaveable { mutableStateOf(item.servingG?.let { formatNumber(it) } ?: "100") }
    var meal by rememberSaveable { mutableStateOf(initialMeal) }
    val p = parseDecimal(portions)?.takeIf { it > 0 && it <= 50 }
    val g = parseDecimal(grams)?.takeIf { it > 0 && it <= 5000 }
    val canPortion = perPortion || item.servingG != null
    val valid = if (useGrams) g != null else (p != null && canPortion)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("fuel_amount_dialog"),
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.trf_amount_title), style = MaterialTheme.typography.labelLarge)
                if (gramsPossible && canPortion) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !useGrams, onClick = { useGrams = false },
                            label = { Text(stringResource(R.string.trf_amount_portions)) })
                        FilterChip(selected = useGrams, onClick = { useGrams = true },
                            label = { Text(stringResource(R.string.trf_amount_grams)) })
                    }
                }
                if (useGrams) {
                    NumberField(stringResource(R.string.trf_amount_grams), grams, "fuel_amount_grams", Modifier.fillMaxWidth()) { grams = it }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.5, 1.0, 2.0).forEach { preset ->
                            val label = formatNumber(preset)
                            AssistChip(onClick = { portions = label }, label = { Text(label) },
                                modifier = Modifier.testTag("fuel_amount_preset_$label"))
                        }
                    }
                    NumberField(stringResource(R.string.trf_amount_portions), portions, "fuel_amount_portions", Modifier.fillMaxWidth()) { portions = it }
                    item.servingG?.let { Text(stringResource(R.string.trf_amount_serving, formatNumber(it)), style = MaterialTheme.typography.bodySmall) }
                }
                Text(stringResource(R.string.trf_qa_meal), style = MaterialTheme.typography.labelLarge)
                MealPicker(meal) { meal = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (useGrams) onConfirm(meal, null, g) else onConfirm(meal, p, null) },
                enabled = valid,
                modifier = Modifier.testTag("fuel_amount_confirm"),
            ) { Text(stringResource(R.string.trf_amount_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) } },
    )
}

/** A food with per-100 g values and an optional portion size. */
@Composable
fun CreateFoodDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, brand: String?, per100: Nutrients, servingG: Double?, servingLabel: String?) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var brand by rememberSaveable { mutableStateOf("") }
    var protein by rememberSaveable { mutableStateOf("") }
    var carbs by rememberSaveable { mutableStateOf("") }
    var fat by rememberSaveable { mutableStateOf("") }
    var kcal by rememberSaveable { mutableStateOf("") }
    var servingG by rememberSaveable { mutableStateOf("") }
    var servingLabel by rememberSaveable { mutableStateOf("") }
    val valid = name.isNotBlank() && listOf(protein, carbs, fat, kcal, servingG).all(::fieldValid)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("fuel_create_dialog"),
        title = { Text(stringResource(R.string.trf_create_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(120) }, singleLine = true,
                    label = { Text(stringResource(R.string.trf_create_name)) }, modifier = Modifier.fillMaxWidth().testTag("fuel_create_name"))
                OutlinedTextField(value = brand, onValueChange = { brand = it.take(80) }, singleLine = true,
                    label = { Text(stringResource(R.string.trf_create_brand)) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.trf_create_per100), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(stringResource(R.string.trf_qa_protein), protein, "fuel_create_protein", Modifier.weight(1f)) { protein = it }
                    NumberField(stringResource(R.string.trf_qa_carbs), carbs, "fuel_create_carbs", Modifier.weight(1f)) { carbs = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(stringResource(R.string.trf_qa_fat), fat, "fuel_create_fat", Modifier.weight(1f)) { fat = it }
                    NumberField(stringResource(R.string.trf_qa_kcal), kcal, "fuel_create_kcal", Modifier.weight(1f)) { kcal = it }
                }
                NumberField(stringResource(R.string.trf_create_serving_g), servingG, "fuel_create_serving", Modifier.fillMaxWidth()) { servingG = it }
                OutlinedTextField(value = servingLabel, onValueChange = { servingLabel = it.take(40) }, singleLine = true,
                    label = { Text(stringResource(R.string.trf_create_serving_label)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(name.trim(), brand.trim().ifBlank { null },
                        Nutrients(optionalAmount(kcal), optionalAmount(protein), optionalAmount(carbs), optionalAmount(fat)),
                        optionalAmount(servingG)?.takeIf { it > 0 }, servingLabel.trim().ifBlank { null })
                },
                enabled = valid,
                modifier = Modifier.testTag("fuel_create_save"),
            ) { Text(stringResource(R.string.tr_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) } },
    )
}
