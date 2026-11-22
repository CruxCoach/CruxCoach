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
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.EmptyHint
import com.cruxcoach.android.ui.training.LabelBesideOrAbove
import com.cruxcoach.android.ui.training.formatNumber
import com.cruxcoach.android.ui.training.mealLabel
import com.cruxcoach.android.ui.training.parseDecimal
import com.cruxcoach.athlete.logic.BlsFood
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.MacroTotals
import com.cruxcoach.athlete.logic.OffProduct
import com.cruxcoach.athlete.logic.Recipe
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.UnitSystem

/**
 * The meal as one small choice ("Abendessen ▾") instead of six chips: the
 * time of day already picked the likely one, changing it is the exception.
 */
@Composable
fun MealPicker(selected: Meal, onSelect: (Meal) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(mealLabel(selected)) },
            leadingIcon = { Icon(Icons.Default.Restaurant, null, Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("fuel_meal_choice"),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Meal.entries.forEach { meal ->
                DropdownMenuItem(
                    text = { Text(mealLabel(meal)) },
                    onClick = { onSelect(meal); open = false },
                    leadingIcon = if (meal == selected) ({ Icon(Icons.Default.Check, null) }) else null,
                    modifier = Modifier.testTag("fuel_pick_meal_${meal.name.lowercase()}"),
                )
            }
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

/** Dishes shown on top before "show all": more would push "log again" off a small screen. */
private const val DISH_PREVIEW = 4

/** Taps on a one-tap row this soon after the last one are the same tap. */
private const val ONE_TAP_GAP_MS = 400L

/** The energy of scaled nutrients: as stated, else estimated from the macros; null without any value. */
private fun energyOf(n: Nutrients): MacroTotals.Energy? =
    n.kcal?.let { MacroTotals.Energy(it, estimated = false) }
        ?: if (n.protein == null && n.carbs == null && n.fat == null) null
        else MacroTotals.Energy(4 * (n.protein ?: 0.0) + 4 * (n.carbs ?: 0.0) + 9 * (n.fat ?: 0.0), estimated = true)

/** "80 g", "2 × portion", or nothing for one portion of a food without a weight. */
@Composable
private fun entryAmount(entry: FoodLogEntry, units: UnitSystem): String? = when {
    entry.amountG != null -> amountText(entry.amountG!!, FuelUnits.unitFor(units, FuelUnits.isDrink(entry.foodItemId, entry.name)))
    entry.portions != null && entry.portions != 1.0 -> stringResource(R.string.trf_entry_portions, formatNumber(entry.portions!!))
    else -> null
}

/** A saved food's name; a favourite carries its star, now that the star button sits in the row's menu. */
@Composable
private fun FoodName(item: FoodItem) {
    if (!item.favorite) {
        Text(item.name)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(item.name, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Default.Star, contentDescription = stringResource(R.string.trf_food_is_favorite),
            tint = CruxCoachDesign.colors.brandAccent, modifier = Modifier.padding(start = 6.dp).size(16.dp))
    }
}

/**
 * A saved food's actions behind one "⋮". Edit, star and delete as three icons left the name of a dish a third of a
 * narrow phone's width (flow review 2026-11-22). [onAmount] is for rows that log at once: the way to another amount.
 */
@Composable
private fun FoodMenu(
    item: FoodItem,
    onAmount: (() -> Unit)?,
    onEditRecipe: (() -> Unit)?,
    onToggleFavorite: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("fuel_food_menu_${item.id}")) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.trf_entry_menu, item.name))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onAmount != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.trf_food_choose_amount)) },
                    leadingIcon = { Icon(Icons.Default.Scale, null) },
                    onClick = { open = false; onAmount() },
                    modifier = Modifier.testTag("fuel_food_amount_${item.id}"),
                )
            }
            if (onEditRecipe != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.trf_recipe_edit)) },
                    leadingIcon = { Icon(Icons.Default.Edit, null) },
                    onClick = { open = false; onEditRecipe() },
                    modifier = Modifier.testTag("fuel_recipe_edit_${item.id}"),
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(if (item.favorite) R.string.trf_food_unfavorite else R.string.trf_food_favorite)) },
                leadingIcon = { Icon(if (item.favorite) Icons.Default.Star else Icons.Default.StarBorder, null) },
                onClick = { open = false; onToggleFavorite() },
                modifier = Modifier.testTag("fuel_food_star_${item.id}"),
            )
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tr_action_delete)) },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = { open = false; onDelete() },
                    modifier = Modifier.testTag("fuel_food_delete_${item.id}"),
                )
            }
        }
    }
}

/**
 * What was just logged, on top of the add sheet. The sheet stays open for the next food (Nokia walk 2026-11-22: four
 * foods for one meal meant opening it four times), so this is where the athlete sees that the tap arrived: the food,
 * what its meal holds now, "undo" and "done".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoggedStrip(
    last: FoodLogEntry,
    dayEntries: List<FoodLogEntry>,
    showCalories: Boolean,
    units: UnitSystem,
    onUndo: () -> Unit,
    onDone: () -> Unit,
) {
    // The day's list may still be on its way from the database: the new entry counts either way.
    val meal = (dayEntries.filter { it.meal == last.meal && it.id != last.id } + last)
    val totals = MacroTotals.of(meal)
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("fuel_logged")) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(end = 8.dp)) {
                Icon(Icons.Default.CheckCircle, null, tint = CruxCoachDesign.colors.positive, modifier = Modifier.padding(top = 1.dp).size(20.dp))
                Spacer(Modifier.width(8.dp))
                // TalkBack reads the confirmation when it appears or changes.
                Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(listOfNotNull(stringResource(R.string.trf_recent_logged, last.name), entryAmount(last, units)).joinToString(" · "),
                        style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("fuel_logged_text"))
                    Text(pluralStringResource(R.plurals.trf_logged_meal, meal.size, meal.size, mealLabel(last.meal)) + " · " +
                        macrosText(totals.protein, totals.carbs, totals.fat, MacroTotals.Energy(totals.kcal, totals.kcalEstimated), showCalories),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("fuel_logged_meal"))
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onUndo, modifier = Modifier.testTag("fuel_logged_undo")) { Text(stringResource(R.string.trf_undo)) }
                TextButton(onClick = onDone, modifier = Modifier.testTag("fuel_logged_done")) {
                    Text(stringResource(R.string.tr_action_done), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Saved foods (favourites first, then most used) plus, while searching, the
 * 7,140 foods of the bundled BLS 4.0 – offline on every phone (FEAT-069).
 *
 * When it logs food, the sheet stays open after each food: what was [logged] is confirmed on top with "undo", and
 * "done" (or leaving the sheet) ends it. Dishes stand first and are logged with one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodsSheet(
    foods: List<FoodItem>,
    onDismiss: () -> Unit,
    onPick: (FoodItem) -> Unit,
    onToggleFavorite: (FoodItem) -> Unit,
    /** Null leaves "delete" out of a food's menu (picking a recipe ingredient). */
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
    /** Foods eaten at the chosen meal again and again, logged once more with one tap (same amount). */
    recent: List<FoodLogEntry> = emptyList(),
    onRelog: (FoodLogEntry) -> Unit = {},
    showCalories: Boolean = false,
    units: UnitSystem = UnitSystem.METRIC,
    /**
     * One tap on a dish logs its usual amount. Dishes then stand first, under "My dishes"; null lists them among the
     * foods (picking a recipe ingredient).
     */
    onLogDish: ((FoodItem) -> Unit)? = null,
    /** Newest entry per saved food: the amount one tap on a dish logs. */
    lastByFood: Map<String, FoodLogEntry> = emptyMap(),
    /** What was logged since the sheet opened, newest last: confirmed on top, with "undo" for the newest. */
    logged: List<FoodLogEntry> = emptyList(),
    /** The shown day's entries: the confirmation says what the meal holds now. */
    dayEntries: List<FoodLogEntry> = emptyList(),
    onUndoLogged: (FoodLogEntry) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    /** A food about to be deleted: one tap on the trash icon used to delete it outright. */
    var confirmDelete by remember { mutableStateOf<FoodItem?>(null) }
    var allDishes by rememberSaveable { mutableStateOf(false) }
    val q = query.trim().lowercase()
    // After a food was logged the search starts over: the next food is another one.
    LaunchedEffect(logged.size) { if (logged.isNotEmpty()) query = "" }
    // A one-tap row stays under the finger now that the sheet stays open, and when a "log again" row leaves, the next
    // one slides up into its place: a second tap within a moment is the same tap, not another food.
    var oneTaps by remember { mutableStateOf(0) }
    var oneTapReady by remember { mutableStateOf(true) }
    LaunchedEffect(oneTaps) {
        if (oneTaps > 0) {
            delay(ONE_TAP_GAP_MS)
            oneTapReady = true
        }
    }
    fun oneTap(log: () -> Unit) {
        if (!oneTapReady) return
        oneTapReady = false
        oneTaps++
        log()
    }
    // Dishes have their own one-tap section while nothing is typed; a search lists them among the foods.
    val dishSection = onLogDish != null && q.isEmpty()
    // The dish saved or logged last comes first, so a meal saved a moment ago is one tap away; favourites before all.
    val dishes = if (dishSection) foods.filter { it.source == Recipe.SOURCE }
        .sortedWith(compareByDescending<FoodItem> { it.favorite }.thenByDescending { maxOf(it.lastUsedAt ?: 0L, it.updatedAt) })
        else emptyList()
    val shownDishes = if (allDishes) dishes else dishes.take(DISH_PREVIEW)
    // A dish in the section above is not offered a second time under "log again".
    val shownRecent = recent.filter { r -> shownDishes.none { it.id == r.foodItemId } }
    // The repository already orders favourites first, then by use count.
    // Dishes first (Nokia walk 2026-11-22: a meal saved from four foods stood below the four foods): a dish is what
    // saves the typing. The sort is stable, so favourites and use count keep their order within both groups.
    val visible = foods.filter { q.isEmpty() || it.name.lowercase().contains(q) || (it.brand?.lowercase()?.contains(q) == true) }
        .filter { !dishSection || it.source != Recipe.SOURCE }
        .sortedBy { if (it.source == Recipe.SOURCE) 0 else 1 }
    // A row's menu holds what can be done with a saved food; picking an ingredient needs none of it.
    val menus = onDelete != null || onEditRecipe != null
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
        // The sheet stays under the amount dialog, so "cancel" there comes back to the search and its results. The
        // keyboard goes: it would cover the dialog's own field. The sheet is a window of its own, so its focus is asked
        // for in here.
        val focus = LocalFocusManager.current
        fun picked(pick: () -> Unit) { focus.clearFocus(); pick() }
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            // The meal as one small choice next to the title, not six chips above the search; below it when both do
            // not fit in a row ("Ess/en/eintr/age/n" at the largest font).
            LabelBesideOrAbove(modifier = Modifier.padding(bottom = 8.dp), label = {
                Text(title ?: stringResource(R.string.trf_my_foods), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 8.dp))
            }) {
                if (meal != null) MealPicker(meal, onMealChange)
            }
            logged.lastOrNull()?.let { last ->
                LoggedStrip(last, dayEntries, showCalories, units, onUndo = { onUndoLogged(last) }, onDone = onDismiss)
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
            // What this meal usually holds, the own foods and the databases scroll together: on a small phone
            // the "log again" rows above a fixed list squeezed "my foods" away (render review 2026-10-16).
            LazyColumn(Modifier.heightIn(max = 460.dp).testTag("fuel_foods_list")) {
                    if (onLogDish != null && shownDishes.isNotEmpty()) {
                        item(key = "dishes_title") {
                            Text(stringResource(R.string.trf_dishes_title), style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        }
                        // One tap each, like "log again" below: the usual amount, into the chosen meal.
                        items(shownDishes, key = { "dish_${it.id}" }) { item ->
                            val (portions, grams) = FuelViewModel.usualAmount(item, lastByFood[item.id])
                            val count = portions ?: 1.0
                            val amount = when {
                                grams != null -> amountText(grams, FuelUnits.unitFor(units, FuelUnits.isDrink(item.id, item.name, item.servingLabel)))
                                count == 1.0 -> stringResource(R.string.trf_one_portion)
                                else -> stringResource(R.string.trf_entry_portions, formatNumber(count))
                            }
                            val values = FuelViewModel.scale(item, portions, grams)
                                ?.let { macrosText(it.protein, it.carbs, it.fat, energyOf(it), showCalories) }
                            ListItem(
                                headlineContent = { FoodName(item) },
                                supportingContent = {
                                    Text(listOfNotNull(amount, values).joinToString(" · "), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.AddCircle, contentDescription = stringResource(R.string.trf_dish_log, item.name))
                                        FoodMenu(
                                            item,
                                            onAmount = { picked { onPick(item) } },
                                            onEditRecipe = onEditRecipe?.let { edit -> { edit(item) } },
                                            onToggleFavorite = { onToggleFavorite(item) },
                                            onDelete = onDelete?.let { { confirmDelete = item } },
                                        )
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                modifier = Modifier.clickable { oneTap { onLogDish(item) } }.testTag("fuel_dish_${item.id}"),
                            )
                        }
                        if (dishes.size > shownDishes.size) {
                            item(key = "dishes_all") {
                                TextButton(onClick = { allDishes = true }, modifier = Modifier.testTag("fuel_dishes_all")) {
                                    Text(stringResource(R.string.trf_dishes_all, dishes.size))
                                }
                            }
                        }
                    }
                    if (q.isEmpty() && shownRecent.isNotEmpty()) {
                        item(key = "recent_title") {
                            Text(stringResource(R.string.trf_recent_title), style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        }
                        // One tap each: the same food with the same amount (UX review 2026-10-16).
                        items(shownRecent, key = { "recent_${it.id}" }) { entry ->
                            val amount = entryAmount(entry, units)
                            ListItem(
                                headlineContent = { Text(entry.name, maxLines = 1) },
                                supportingContent = {
                                    // Two lines with "…": one line lost the energy at the end at a large font.
                                    Text(listOfNotNull(amount, macrosText(entry.proteinG, entry.carbsG, entry.fatG,
                                        com.cruxcoach.athlete.logic.MacroTotals.energy(entry), showCalories)).joinToString(" · "), maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                },
                                trailingContent = { Icon(Icons.Default.AddCircle, contentDescription = stringResource(R.string.trf_recent_log, entry.name)) },
                                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                modifier = Modifier.clickable { oneTap { onRelog(entry) } }.testTag("fuel_recent_${entry.id}"),
                            )
                        }
                    }
                    if (onCreate != null || onCreateRecipe != null) {
                        item(key = "own_title") {
                            // The two actions below the title when they leave it no room ("Mein" at the largest font).
                            LabelBesideOrAbove(modifier = Modifier.padding(top = 4.dp), label = {
                                Text(stringResource(R.string.trf_my_foods), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 8.dp))
                            }) {
                                // "+ Dish" and "+ Food": each says what it makes ("Recipe" and "New" did not).
                                onCreateRecipe?.let { create ->
                                    TextButton(onClick = create, modifier = Modifier.testTag("fuel_recipe_new")) {
                                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.trf_recipe_new))
                                    }
                                }
                                onCreate?.let { create ->
                                    TextButton(onClick = create, modifier = Modifier.testTag("fuel_food_new")) {
                                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.trf_food_new))
                                    }
                                }
                            }
                        }
                    }
                    if (visible.isEmpty() && q.isEmpty() && dishes.isEmpty()) {
                        item(key = "own_empty") { EmptyHint(stringResource(R.string.trf_foods_empty)) }
                    }
                    items(visible, key = { it.id }) { item ->
                        ListItem(
                            headlineContent = { FoodName(item) },
                            supportingContent = {
                                // A dish is eaten by the portion: its line says what one portion holds, not 100 g of it.
                                val portion = item.servingG?.takeIf { item.source == Recipe.SOURCE && it > 0 }
                                val perPortion = portion != null || FuelViewModel.isPerPortion(item)
                                val f = if (portion != null) portion / 100.0 else 1.0
                                val n = Nutrients(item.kcalPer100?.let { it * f }, item.proteinPer100?.let { it * f },
                                    item.carbsPer100?.let { it * f }, item.fatPer100?.let { it * f })
                                val basis = stringResource(if (perPortion) R.string.trf_food_per_portion else R.string.trf_food_per_100)
                                // Energy with the three macros, as everywhere (owner 2026-11-22: always all of them).
                                val values = macrosText(n.protein, n.carbs, n.fat, energyOf(n), showCalories)
                                Text(listOfNotNull(item.brand, "$values ($basis)").joinToString(" · "))
                            },
                            trailingContent = if (menus) ({
                                FoodMenu(
                                    item,
                                    onAmount = null,
                                    onEditRecipe = onEditRecipe?.takeIf { item.source == Recipe.SOURCE }?.let { edit -> { edit(item) } },
                                    onToggleFavorite = { onToggleFavorite(item) },
                                    onDelete = onDelete?.let { { confirmDelete = item } },
                                )
                            }) else null,
                            modifier = Modifier.clickable { picked { onPick(item) } }.testTag("fuel_food_${item.id}"),
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
                            modifier = Modifier.clickable { picked { onPickBls(food) } }.testTag("fuel_bls_${food.code}"),
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
                                modifier = Modifier.clickable { picked { onPickUsda(food) } }.testTag("fuel_usda_${food.code}"),
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
                                modifier = Modifier.clickable { picked { onPickProduct(product) } }.testTag("fuel_off_${product.code}"),
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
    /** A logged entry (or a dish's ingredient) to change: its amount and meal are the starting values. */
    editing: FoodLogEntry? = null,
    /** Picking a recipe ingredient: no meal, "Add" instead of "Log". */
    forRecipe: Boolean = false,
    /** The athlete's calorie setting: the live preview names kcal only when calories are shown. */
    showCalories: Boolean = false,
    /** The last time this food was logged: a new entry starts with that amount (UX review 2026-10-16). */
    last: FoodLogEntry? = null,
) {
    val perPortion = FuelViewModel.isPerPortion(item)
    val gramsPossible = !perPortion
    // Drinks are measured by volume (ml, or fl oz and cups in US units); 1 ml counts as 1 g.
    val drink = FuelUnits.isDrink(item.id, item.name, item.servingLabel)
    val baseUnit = FuelUnits.unitFor(units, drink)
    val amountUnits = if (units == UnitSystem.IMPERIAL && drink) listOf(baseUnit, FuelUnits.Amount.CUP) else listOf(baseUnit)
    // A change starts from the entry; a new entry from the amount eaten last time.
    val start = editing ?: last
    val editedByGrams = start != null && start.portions == null && start.amountG != null
    var useGrams by rememberSaveable { mutableStateOf(gramsPossible && (item.servingG == null || editedByGrams)) }
    var unit by rememberSaveable { mutableStateOf(baseUnit) }
    var portions by rememberSaveable { mutableStateOf(start?.portions?.let { formatNumber(it, 2) } ?: "1") }
    var grams by rememberSaveable {
        mutableStateOf(inputText(start?.amountG?.takeIf { editedByGrams } ?: item.servingG ?: 100.0, baseUnit))
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
                // What this amount brings, while typing (device test 2026-10-10: the dialog showed only the number).
                FuelViewModel.scale(item, p.takeUnless { useGrams }, g.takeIf { useGrams })?.let { n ->
                    Text(macrosText(n.protein, n.carbs, n.fat, energyOf(n), showCalories),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("fuel_amount_preview"))
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
                    // A logged entry or a dish's ingredient with a new amount is saved, a new one added or logged.
                    editing != null -> R.string.tr_action_save
                    forRecipe -> R.string.trf_recipe_add_confirm
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
