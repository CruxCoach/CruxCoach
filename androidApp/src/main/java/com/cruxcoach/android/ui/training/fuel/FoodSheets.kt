package com.cruxcoach.android.ui.training.fuel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.training.EmptyHint
import com.cruxcoach.android.ui.training.formatNumber
import com.cruxcoach.android.ui.training.mealLabel
import com.cruxcoach.android.ui.training.parseDecimal
import com.cruxcoach.athlete.logic.BlsFood
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.OffProduct
import com.cruxcoach.athlete.logic.Recipe
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.UnitSystem

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
    /** A logged quick entry to change instead of a new one. */
    editing: FoodLogEntry? = null,
) {
    fun text(v: Double?) = v?.let { formatNumber(it) }.orEmpty()
    var name by rememberSaveable { mutableStateOf(editing?.name.orEmpty()) }
    var meal by rememberSaveable { mutableStateOf(editing?.meal ?: initialMeal) }
    var protein by rememberSaveable { mutableStateOf(text(editing?.proteinG)) }
    var carbs by rememberSaveable { mutableStateOf(text(editing?.carbsG)) }
    var fat by rememberSaveable { mutableStateOf(text(editing?.fatG)) }
    var kcal by rememberSaveable { mutableStateOf(text(editing?.kcal)) }
    var remember by rememberSaveable { mutableStateOf(false) }
    val defaultName = stringResource(R.string.trf_qa_default_name)
    val valid = listOf(protein, carbs, fat, kcal).all(::fieldValid) && (!remember || name.isNotBlank())

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("fuel_quick_add_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(if (editing != null) R.string.trf_edit_title else R.string.trf_qa_title), style = MaterialTheme.typography.titleLarge)
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
            if (editing == null) {
                // One toggle for TalkBack: the label is read with the checkbox state.
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(value = remember, role = Role.Checkbox, onValueChange = { remember = it })
                        .testTag("fuel_qa_remember")) {
                    Checkbox(checked = remember, onCheckedChange = null)
                    Text(stringResource(R.string.trf_qa_remember))
                }
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

/**
 * Saved foods (favourites first, then most used) plus, while searching, the
 * 7,140 foods of the bundled BLS 4.0 – offline on every phone (FEAT-069).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodsSheet(
    foods: List<FoodItem>,
    onDismiss: () -> Unit,
    onPick: (FoodItem) -> Unit,
    onToggleFavorite: (FoodItem) -> Unit,
    /** Null hides the trash icon (picking a recipe ingredient). */
    onDelete: ((FoodItem) -> Unit)?,
    onCreate: (() -> Unit)?,
    searchBls: suspend (String) -> List<BlsFood> = { emptyList() },
    onPickBls: (BlsFood) -> Unit = {},
    searchProducts: suspend (String) -> List<OffProduct> = { emptyList() },
    onPickProduct: (OffProduct) -> Unit = {},
    onScan: (() -> Unit)? = null,
    searchUsda: suspend (String) -> List<BlsFood> = { emptyList() },
    onPickUsda: (BlsFood) -> Unit = {},
    onCreateRecipe: (() -> Unit)? = null,
    onEditRecipe: ((FoodItem) -> Unit)? = null,
    /** Shown instead of "My foods" when the sheet picks a recipe ingredient. */
    title: String? = null,
    /** The product database is still being unpacked (first use after install or update). */
    productsPreparing: Boolean = false,
    /** The meal the food goes to, chosen on top; null hides the choice (recipe ingredients). */
    meal: Meal? = null,
    onMealChange: (Meal) -> Unit = {},
    /** Scan, describe, photo and plain values as tiles under the search field (adding to the log). */
    addActions: (@Composable () -> Unit)? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    /** A food about to be deleted: one tap on the trash icon used to delete it outright. */
    var confirmDelete by remember { mutableStateOf<FoodItem?>(null) }
    val q = query.trim().lowercase()
    // The repository already orders favourites first, then by use count.
    val visible = foods.filter { q.isEmpty() || it.name.lowercase().contains(q) || (it.brand?.lowercase()?.contains(q) == true) }
    var blsResults by remember { mutableStateOf<List<BlsFood>>(emptyList()) }
    var productResults by remember { mutableStateOf<List<OffProduct>>(emptyList()) }
    var usdaResults by remember { mutableStateOf<List<BlsFood>>(emptyList()) }
    // "No match" only once a source has answered; until then "Searching…".
    var searching by remember { mutableStateOf(false) }
    var searchingBls by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        if (q.length < 2) {
            blsResults = emptyList(); productResults = emptyList(); usdaResults = emptyList()
            searching = false; searchingBls = false
            return@LaunchedEffect
        }
        delay(250)
        val own = foods.map { it.id }.toSet()
        searching = true
        searchingBls = true
        // The three sources answer independently; a slow one never holds up the others.
        coroutineScope {
            launch { blsResults = searchBls(q).filter { "bls:${it.code}" !in own }; searchingBls = false }
            launch { usdaResults = searchUsda(q).filter { "usda:${it.code}" !in own } }
            launch { productResults = searchProducts(q).filter { "off:${it.code}" !in own }; searching = false }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("fuel_foods_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Text(title ?: stringResource(R.string.trf_my_foods), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                // The meal as one small choice next to the title, not six chips above the search.
                if (meal != null) {
                    var mealMenu by remember { mutableStateOf(false) }
                    Box {
                        AssistChip(onClick = { mealMenu = true }, label = { Text(mealLabel(meal)) },
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) }, modifier = Modifier.testTag("fuel_add_meal"))
                        DropdownMenu(expanded = mealMenu, onDismissRequest = { mealMenu = false }) {
                            Meal.entries.forEach { m ->
                                DropdownMenuItem(text = { Text(mealLabel(m)) }, onClick = { onMealChange(m); mealMenu = false },
                                    modifier = Modifier.testTag("fuel_add_meal_${m.name.lowercase()}"))
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = onScan?.let { scan ->
                    {
                        IconButton(onClick = scan, modifier = Modifier.testTag("fuel_scan")) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.trf_scan))
                        }
                    }
                },
                placeholder = { Text(stringResource(R.string.trf_find_food)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("fuel_foods_search"),
            )
            // The other ways in, while nothing is typed: the list below belongs to the search.
            if (q.isEmpty()) addActions?.invoke()
            if (onCreate != null || onCreateRecipe != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(stringResource(R.string.trf_my_foods), style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f))
                    onCreateRecipe?.let { create ->
                        TextButton(onClick = create, modifier = Modifier.testTag("fuel_recipe_new")) {
                            Icon(Icons.Default.MenuBook, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.trf_recipe_new))
                        }
                    }
                    onCreate?.let { create ->
                        TextButton(onClick = create, modifier = Modifier.testTag("fuel_food_new")) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.tru_new_food))
                        }
                    }
                }
            }
            if (visible.isEmpty() && q.isEmpty()) {
                EmptyHint(stringResource(R.string.trf_foods_empty))
            }
            LazyColumn(Modifier.heightIn(max = 460.dp).testTag("fuel_foods_list")) {
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
                                    if (item.source == Recipe.SOURCE && onEditRecipe != null) {
                                        IconButton(onClick = { onEditRecipe(item) }, modifier = Modifier.testTag("fuel_recipe_edit_${item.id}")) {
                                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.trf_recipe_edit))
                                        }
                                    }
                                    IconButton(onClick = { onToggleFavorite(item) }, modifier = Modifier.testTag("fuel_food_star_${item.id}")) {
                                        Icon(
                                            if (item.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                                            contentDescription = stringResource(if (item.favorite) R.string.trf_food_unfavorite else R.string.trf_food_favorite),
                                        )
                                    }
                                    if (onDelete != null) {
                                        IconButton(onClick = { confirmDelete = item }, modifier = Modifier.testTag("fuel_food_delete_${item.id}")) {
                                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.trf_food_delete))
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onPick(item) }.testTag("fuel_food_${item.id}"),
                        )
                    }
                    item(key = "bls_header") {
                        Column(Modifier.padding(top = 12.dp)) {
                            Text(stringResource(R.string.trf_foods_bls_title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(when {
                                    q.length < 2 -> R.string.trf_foods_bls_hint
                                    searchingBls && blsResults.isEmpty() -> R.string.trf_foods_searching
                                    blsResults.isEmpty() -> R.string.trf_foods_bls_none
                                    else -> R.string.fvp_attribution
                                }),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("fuel_foods_bls_hint"),
                            )
                        }
                    }
                    items(blsResults, key = { "bls_${it.code}" }) { food ->
                        ListItem(
                            headlineContent = { Text(FoodPhotoViewModel.blsName(food)) },
                            supportingContent = {
                                Text(stringResource(R.string.fvp_per_100, formatNumber(food.kcal), formatNumber(food.protein),
                                    formatNumber(food.carbs), formatNumber(food.fat)))
                            },
                            modifier = Modifier.clickable { onPickBls(food) }.testTag("fuel_bls_${food.code}"),
                        )
                    }
                    // US generic foods with household measures (cups, slices); English names only.
                    if (usdaResults.isNotEmpty()) {
                        item(key = "usda_header") {
                            Column(Modifier.padding(top = 12.dp)) {
                                Text(stringResource(R.string.trf_foods_usda_title), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.trf_usda_attribution), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(usdaResults, key = { "usda_${it.code}" }) { food ->
                            ListItem(
                                headlineContent = { Text(food.nameEn) },
                                supportingContent = {
                                    Text(stringResource(R.string.fvp_per_100, formatNumber(food.kcal), formatNumber(food.protein),
                                        formatNumber(food.carbs), formatNumber(food.fat)))
                                },
                                modifier = Modifier.clickable { onPickUsda(food) }.testTag("fuel_usda_${food.code}"),
                            )
                        }
                    }
                    if (q.length >= 2) {
                        item(key = "off_header") {
                            Column(Modifier.padding(top = 12.dp)) {
                                Text(stringResource(R.string.trf_foods_products_title), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(when {
                                        productsPreparing && productResults.isEmpty() -> R.string.trf_products_preparing
                                        searching && productResults.isEmpty() -> R.string.trf_foods_searching
                                        productResults.isEmpty() -> R.string.trf_foods_bls_none
                                        else -> R.string.trf_off_attribution
                                    }),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("fuel_products_hint"),
                                )
                            }
                        }
                        items(productResults, key = { "off_${it.code}" }) { product ->
                            ListItem(
                                headlineContent = { Text(product.displayName(FoodPhotoViewModel.isGerman())) },
                                supportingContent = {
                                    Text(listOfNotNull(product.brand.ifEmpty { null },
                                        stringResource(R.string.fvp_per_100, formatNumber(product.kcal), formatNumber(product.protein),
                                            formatNumber(product.carbs), formatNumber(product.fat))).joinToString(" · "))
                                },
                                modifier = Modifier.clickable { onPickProduct(product) }.testTag("fuel_off_${product.code}"),
                            )
                        }
                    }
            }
        }
    }
    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            modifier = Modifier.testTag("fuel_food_delete_dialog"),
            title = { Text(stringResource(R.string.trf_food_delete_confirm_title, item.name)) },
            text = { Text(stringResource(R.string.trf_food_delete_confirm_text)) },
            confirmButton = {
                TextButton(onClick = { onDelete?.invoke(item); confirmDelete = null }, modifier = Modifier.testTag("fuel_food_delete_confirm")) {
                    Text(stringResource(R.string.tr_action_delete))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

/** Amount for a saved food: portions always; grams when the food has per-100 g values. */
@Composable
fun AmountDialog(
    item: FoodItem,
    initialMeal: Meal,
    onDismiss: () -> Unit,
    onConfirm: (meal: Meal, portions: Double?, grams: Double?) -> Unit,
    units: UnitSystem = UnitSystem.METRIC,
    /** A logged entry to change: its amount and meal are the starting values. */
    editing: FoodLogEntry? = null,
    /** Picking a recipe ingredient: no meal, "Add" instead of "Log". */
    forRecipe: Boolean = false,
) {
    val perPortion = FuelViewModel.isPerPortion(item)
    val gramsPossible = !perPortion
    // Drinks are measured by volume (ml, or fl oz and cups in US units); 1 ml counts as 1 g.
    val drink = FuelUnits.isDrink(item.id, item.name, item.servingLabel)
    val baseUnit = FuelUnits.unitFor(units, drink)
    val amountUnits = if (units == UnitSystem.IMPERIAL && drink) listOf(baseUnit, FuelUnits.Amount.CUP) else listOf(baseUnit)
    val editedByGrams = editing != null && editing.portions == null && editing.amountG != null
    var useGrams by rememberSaveable { mutableStateOf(gramsPossible && (item.servingG == null || editedByGrams)) }
    var unit by rememberSaveable { mutableStateOf(baseUnit) }
    var portions by rememberSaveable { mutableStateOf(editing?.portions?.let { formatNumber(it, 2) } ?: "1") }
    var grams by rememberSaveable {
        mutableStateOf(inputText(editing?.amountG?.takeIf { editedByGrams } ?: item.servingG ?: 100.0, baseUnit))
    }
    var meal by rememberSaveable { mutableStateOf(editing?.meal ?: initialMeal) }
    val p = parseDecimal(portions)?.takeIf { it > 0 && it <= 50 }
    val g = parseDecimal(grams)?.let { FuelUnits.toBase(it, unit) }?.takeIf { it > 0 && it <= 5000 }

    fun switchTo(next: FuelUnits.Amount) {
        // Keep the amount, not the number: 240 ml becomes 8.1 fl oz or 1 cup.
        val base = parseDecimal(grams)?.let { FuelUnits.toBase(it, unit) }
        if (base != null) grams = inputText(base, next)
        unit = next
        useGrams = true
    }
    val canPortion = perPortion || item.servingG != null
    val valid = if (useGrams) g != null else (p != null && canPortion)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("fuel_amount_dialog"),
        title = { Text(item.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.trf_amount_title), style = MaterialTheme.typography.labelLarge)
                if (gramsPossible && (canPortion || amountUnits.size > 1)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canPortion) {
                            FilterChip(selected = !useGrams, onClick = { useGrams = false },
                                label = { Text(stringResource(R.string.trf_amount_portions)) })
                        }
                        amountUnits.forEach { u ->
                            FilterChip(selected = useGrams && unit == u, onClick = { switchTo(u) },
                                label = { Text(if (u == FuelUnits.Amount.G) stringResource(R.string.trf_amount_grams) else unitLabel(u)) },
                                modifier = Modifier.testTag("fuel_amount_unit_${u.name.lowercase()}"))
                        }
                    }
                }
                if (useGrams) {
                    NumberField(if (unit == FuelUnits.Amount.G) stringResource(R.string.trf_amount_grams) else unitLabel(unit),
                        grams, "fuel_amount_grams", Modifier.fillMaxWidth()) { grams = it }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0.5, 1.0, 2.0).forEach { preset ->
                            val label = formatNumber(preset)
                            AssistChip(onClick = { portions = label }, label = { Text(label) },
                                modifier = Modifier.testTag("fuel_amount_preset_$label"))
                        }
                    }
                    NumberField(stringResource(R.string.trf_amount_portions), portions, "fuel_amount_portions", Modifier.fillMaxWidth()) { portions = it }
                    item.servingG?.let { g ->
                        // The pack's own words where known ("1 cup (30 g)"), with the amount in US units.
                        val amount = amountText(g, baseUnit)
                        val text = when {
                            item.servingLabel == null -> amount
                            units == UnitSystem.IMPERIAL -> "${item.servingLabel} · $amount"
                            else -> item.servingLabel!!
                        }
                        Text(stringResource(R.string.trf_amount_serving, text), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!forRecipe) {
                    Text(stringResource(R.string.trf_qa_meal), style = MaterialTheme.typography.labelLarge)
                    MealPicker(meal) { meal = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (useGrams) onConfirm(meal, null, g) else onConfirm(meal, p, null) },
                enabled = valid,
                modifier = Modifier.testTag("fuel_amount_confirm"),
            ) {
                Text(stringResource(when {
                    forRecipe -> R.string.trf_recipe_add_confirm
                    editing != null -> R.string.tr_action_save
                    else -> R.string.trf_amount_add
                }))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) } },
    )
}

/** A food with per-100 g values and an optional portion size. */
@Composable
fun CreateFoodDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, brand: String?, per100: Nutrients, servingG: Double?, servingLabel: String?) -> Unit,
    /** Shown when the food is created for a scanned code that was not in the database. */
    barcode: String? = null,
    units: UnitSystem = UnitSystem.METRIC,
) {
    // US labels state nutrition per serving, European ones per 100 g.
    var perServing by rememberSaveable { mutableStateOf(units == UnitSystem.IMPERIAL) }
    var name by rememberSaveable { mutableStateOf("") }
    var brand by rememberSaveable { mutableStateOf("") }
    var protein by rememberSaveable { mutableStateOf("") }
    var carbs by rememberSaveable { mutableStateOf("") }
    var fat by rememberSaveable { mutableStateOf("") }
    var kcal by rememberSaveable { mutableStateOf("") }
    var servingG by rememberSaveable { mutableStateOf("") }
    var servingLabel by rememberSaveable { mutableStateOf("") }
    val serving = optionalAmount(servingG)?.takeIf { it > 0 }
    val anyNutrient = listOf(protein, carbs, fat, kcal).any { it.isNotBlank() }
    val valid = name.isNotBlank() && listOf(protein, carbs, fat, kcal, servingG).all(::fieldValid) &&
        (!perServing || !anyNutrient || serving != null)
    /** Per 100 g, whichever way the label gave them. */
    fun per100(text: String): Double? = optionalAmount(text)?.let { v -> if (perServing) serving?.let { v * 100.0 / it } else v }

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
                barcode?.let { Text(stringResource(R.string.trf_create_barcode, it), style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !perServing, onClick = { perServing = false },
                        label = { Text(stringResource(R.string.trf_create_values_100g)) }, modifier = Modifier.testTag("fuel_create_per100"))
                    FilterChip(selected = perServing, onClick = { perServing = true },
                        label = { Text(stringResource(R.string.trf_create_values_serving)) }, modifier = Modifier.testTag("fuel_create_per_serving"))
                }
                Text(stringResource(if (perServing) R.string.trf_create_per_serving else R.string.trf_create_per100),
                    style = MaterialTheme.typography.labelLarge)
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
                        Nutrients(per100(kcal), per100(protein), per100(carbs), per100(fat)),
                        serving, servingLabel.trim().ifBlank { null })
                },
                enabled = valid,
                modifier = Modifier.testTag("fuel_create_save"),
            ) { Text(stringResource(R.string.tr_action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) } },
    )
}
