package com.cruxcoach.android.ui.training.fuel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.foodvision.OffRepository
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.android.ui.training.body.shortLabel
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.MacroTotals
import com.cruxcoach.athlete.logic.MicroWatch
import com.cruxcoach.athlete.logic.OffTable
import com.cruxcoach.athlete.logic.Recipe
import com.cruxcoach.athlete.logic.RedsSignal
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.HydrationEntry
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FuelScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: FuelViewModel = hiltViewModel(),
    photoViewModel: FoodPhotoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val photoState by photoViewModel.state.collectAsStateWithLifecycle()
    var photoOpen by remember { mutableStateOf(false) }
    var textOpen by remember { mutableStateOf(false) }
    var photoExplain by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var quickAdd by remember { mutableStateOf(false) }
    var foodsOpen by remember { mutableStateOf(false) }
    var amountFor by remember { mutableStateOf<FoodItem?>(null) }
    var createFood by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    /** A recipe being written; while [pickingIngredient] the food list chooses its next ingredient. */
    var recipeDraft by remember { mutableStateOf<RecipeDraft?>(null) }
    var pickingIngredient by remember { mutableStateOf(false) }
    var ingredientFor by remember { mutableStateOf<FoodItem?>(null) }
    val onlyWeighedText = stringResource(R.string.trf_recipe_only_weighed)
    /** A logged entry being changed: by amount when its food is known, else as a quick entry. */
    var editing by remember { mutableStateOf<FoodLogEntry?>(null) }
    /** A scanned code that is neither one of "my foods" nor in the product database. */
    var scanMiss by remember { mutableStateOf<String?>(null) }
    var createBarcode by remember { mutableStateOf<String?>(null) }
    var copyConfirm by remember { mutableStateOf(false) }
    val defaultMeal = remember { FuelViewModel.mealForHour(java.time.LocalTime.now().hour) }
    val units = state.profile.units
    // Review lists after a photo or a typed meal show amounts in the same units.
    LaunchedEffect(units) { photoViewModel.units = units }
    LaunchedEffect(Unit) { photoViewModel.prepareProducts() }
    val productsState by photoViewModel.productsState.collectAsState()
    // Changes of the shown day go to Health Connect once the export is on (training settings).
    LaunchedEffect(state.day, state.entries, state.water, state.profile.healthConnectExport) {
        val day = state.day ?: return@LaunchedEffect
        if (state.profile.healthConnectExport && !state.loading) photoViewModel.exportDay(day.toString(), state.entries, state.water)
    }
    // Iron, calcium and vitamin D over the week up to the shown day.
    val microDay = state.day ?: state.today
    val micros by produceState<MicroWatch.Summary?>(null, microDay, state.entries, state.profile.sex, state.profile.birthYear) {
        value = microDay?.let { photoViewModel.microWeek(it, state.profile.sex, state.profile.birthYear) }
    }
    val foodsById = remember(state.foods) { state.foods.associateBy { it.id } }
    val isDrink: (FoodLogEntry) -> Boolean = { e ->
        foodsById[e.foodItemId]?.let { FuelUnits.isDrink(it.id, it.name, it.servingLabel) } ?: FuelUnits.isDrink(e.foodItemId, e.name)
    }
    val deletedText = stringResource(R.string.trf_deleted)
    val waterRemovedText = stringResource(R.string.trf_water_removed)
    val undoText = stringResource(R.string.trf_undo)

    fun deleteEntry(entry: FoodLogEntry) {
        viewModel.deleteEntry(entry)
        scope.launch {
            if (snackbar.showSnackbar(deletedText, undoText, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                viewModel.restoreEntry(entry)
            }
        }
    }

    fun deleteWater(entry: HydrationEntry) {
        viewModel.deleteWater(entry)
        scope.launch {
            if (snackbar.showSnackbar(waterRemovedText, undoText, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                viewModel.addWater(entry.ml)
            }
        }
    }

    TrainingScaffold(
        title = stringResource(R.string.tr_nav_fuel),
        onBack = onBack,
        actions = {
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("fuel_settings")) {
                Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.tr_action_settings))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag("fuel_list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Nutrition is always on (owner 2026-10-05); the guard rails are
                // explained once instead of behind an opt-in.
                if (!state.profile.fuelIntroAccepted) item { FuelIntroCard(onGotIt = viewModel::acceptIntro) }
                item { DayHeader(state, viewModel::previousDay, viewModel::nextDay, viewModel::goToday) }
                if (state.redsSignals.isNotEmpty()) item { EnergyCareCard(state.redsSignals, "fuel_reds") }
                item { DaySummaryCard(state, onAddWater = viewModel::addWater, onRemoveWater = ::deleteWater) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { quickAdd = true }, modifier = Modifier.testTag("fuel_quick_add")) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.trf_quick_add))
                        }
                        OutlinedButton(onClick = { foodsOpen = true }, modifier = Modifier.testTag("fuel_my_foods")) {
                            Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.trf_find_food))
                        }
                        OutlinedButton(onClick = { textOpen = true }, modifier = Modifier.testTag("fuel_describe")) {
                            Icon(Icons.Default.EditNote, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.trf_describe))
                        }
                        FoodPhotoButton(photoState.support, photoState.tooSlow,
                            onOpen = { photoOpen = true }, onExplain = { photoExplain = true })
                    }
                }
                if (state.previousDayCount > 0) {
                    item {
                        TextButton(onClick = { copyConfirm = true }, modifier = Modifier.testTag("fuel_copy_previous")) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.trf_copy_previous))
                        }
                    }
                }
                if (state.entries.isEmpty()) {
                    item { EmptyHint(stringResource(R.string.trf_empty_day)) }
                }
                // One card per meal: its subtotal on top, every entry with its values.
                MacroTotals.byMeal(state.entries).forEach { (meal, totals) ->
                    item(key = "meal_${meal.name}") {
                        MealCard(meal, totals, state.entries.filter { it.meal == meal }, state.profile.showCalories, units, isDrink,
                            onEdit = { editing = it }, onDelete = ::deleteEntry)
                    }
                }
                // The week's micronutrients below the day: background, not the day's task.
                micros?.let { summary -> item(key = "micros") { MicroWeekCard(summary) } }
            }
        }
    }

    if (quickAdd) {
        QuickAddSheet(
            initialMeal = defaultMeal,
            onDismiss = { quickAdd = false },
            onSave = { name, meal, nutrients, remember -> viewModel.quickAdd(name, meal, nutrients, remember); quickAdd = false },
        )
    }
    if (foodsOpen) {
        FoodsSheet(
            foods = state.foods,
            onDismiss = { foodsOpen = false },
            onPick = { amountFor = it; foodsOpen = false },
            onToggleFavorite = viewModel::toggleFavorite,
            onDelete = viewModel::deleteFood,
            onCreate = { createFood = true; foodsOpen = false },
            searchBls = photoViewModel::search,
            onPickBls = { amountFor = FoodPhotoViewModel.blsFoodItem(it); foodsOpen = false },
            searchProducts = photoViewModel::searchProducts,
            onPickProduct = { amountFor = FoodPhotoViewModel.offFoodItem(it); foodsOpen = false },
            onScan = { scanning = true; foodsOpen = false },
            searchUsda = photoViewModel::searchUsda,
            onPickUsda = { amountFor = FoodPhotoViewModel.usdaFoodItem(it); foodsOpen = false },
            productsPreparing = productsState is OffRepository.State.Preparing,
            onCreateRecipe = { recipeDraft = RecipeDraft(id = null); foodsOpen = false },
            onEditRecipe = { item ->
                foodsOpen = false
                scope.launch { viewModel.loadRecipe(item.id)?.let { recipeDraft = RecipeDraft.of(item.id, it, units) } }
            },
        )
    }
    amountFor?.let { item ->
        AmountDialog(
            item = item,
            initialMeal = defaultMeal,
            onDismiss = { amountFor = null },
            onConfirm = { meal, portions, grams -> viewModel.addFood(item, meal, portions, grams); amountFor = null },
            units = units,
        )
    }
    if (createFood) {
        CreateFoodDialog(
            onDismiss = { createFood = false; createBarcode = null },
            onSave = { name, brand, per100, servingG, label ->
                viewModel.createFood(name, brand, per100, servingG, label, barcode = createBarcode)
                createFood = false
                createBarcode = null
                foodsOpen = true
            },
            barcode = createBarcode,
            units = units,
        )
    }
    recipeDraft?.let { draft ->
        if (pickingIngredient) {
            fun pick(item: FoodItem) {
                if (FuelViewModel.isPerPortion(item)) {
                    scope.launch { snackbar.showSnackbar(onlyWeighedText) }
                } else {
                    ingredientFor = item
                }
                pickingIngredient = false
            }
            FoodsSheet(
                foods = state.foods.filter { it.id != draft.id && !FuelViewModel.isPerPortion(it) },
                onDismiss = { pickingIngredient = false },
                onPick = ::pick,
                onToggleFavorite = viewModel::toggleFavorite,
                onDelete = viewModel::deleteFood,
                onCreate = null,
                searchBls = photoViewModel::search,
                onPickBls = { pick(FoodPhotoViewModel.blsFoodItem(it)) },
                searchProducts = photoViewModel::searchProducts,
                onPickProduct = { pick(FoodPhotoViewModel.offFoodItem(it)) },
                searchUsda = photoViewModel::searchUsda,
                onPickUsda = { pick(FoodPhotoViewModel.usdaFoodItem(it)) },
                title = stringResource(R.string.trf_recipe_pick),
            )
        } else if (ingredientFor == null) {
            RecipeSheet(
                draft = draft,
                units = units,
                showCalories = state.profile.showCalories,
                onChange = { recipeDraft = it },
                onAddIngredient = { pickingIngredient = true },
                onSave = { recipe -> viewModel.saveRecipe(draft.id, recipe); recipeDraft = null; foodsOpen = true },
                onDismiss = { recipeDraft = null },
            )
        }
    }
    ingredientFor?.let { item ->
        AmountDialog(
            item = item,
            initialMeal = defaultMeal,
            onDismiss = { ingredientFor = null },
            onConfirm = { _, portions, grams ->
                val g = grams ?: portions?.let { p -> item.servingG?.let { it * p } }
                if (g != null) {
                    recipeDraft = recipeDraft?.let { it.copy(ingredients = it.ingredients + Recipe.ingredient(item, g)) }
                }
                ingredientFor = null
            },
            units = units,
            forRecipe = true,
        )
    }
    editing?.let { entry ->
        val item = entry.foodItemId?.let { foodsById[it] }
        if (item != null) {
            AmountDialog(
                item = item,
                initialMeal = entry.meal,
                onDismiss = { editing = null },
                onConfirm = { meal, portions, grams -> viewModel.updateEntry(entry, item, meal, portions, grams); editing = null },
                units = units,
                editing = entry,
            )
        } else {
            QuickAddSheet(
                initialMeal = entry.meal,
                onDismiss = { editing = null },
                onSave = { name, meal, nutrients, _ -> viewModel.updateQuickEntry(entry, name, meal, nutrients); editing = null },
                editing = entry,
            )
        }
    }
    if (scanning) {
        BarcodeScannerDialog(
            onResult = { code ->
                scanning = false
                scope.launch {
                    // Own foods first (they may carry a barcode), then the product database – both on the device.
                    val variants = OffTable.barcodeVariants(code)
                    val own = state.foods.firstOrNull { it.barcode != null && it.barcode in variants }
                    val item = own ?: photoViewModel.productByBarcode(code)?.let(FoodPhotoViewModel::offFoodItem)
                    if (item != null) amountFor = item else scanMiss = code
                }
            },
            onDismiss = { scanning = false },
        )
    }
    scanMiss?.let { code ->
        AlertDialog(
            onDismissRequest = { scanMiss = null },
            modifier = Modifier.testTag("fuel_scan_miss"),
            title = { Text(stringResource(R.string.trf_scan_not_found_title)) },
            text = { Text(stringResource(R.string.trf_scan_not_found, code)) },
            confirmButton = {
                TextButton(onClick = { createBarcode = code; createFood = true; scanMiss = null }, modifier = Modifier.testTag("fuel_scan_create")) {
                    Text(stringResource(R.string.trf_scan_create))
                }
            },
            dismissButton = { TextButton(onClick = { scanMiss = null }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
    if (photoOpen) {
        FoodPhotoSheet(
            day = (state.day ?: state.today)?.toString().orEmpty(),
            initialMeal = defaultMeal,
            onDismiss = { photoOpen = false },
            onSaved = { photoOpen = false },
            viewModel = photoViewModel,
        )
    }
    if (textOpen) {
        FoodTextSheet(
            day = (state.day ?: state.today)?.toString().orEmpty(),
            initialMeal = defaultMeal,
            onDismiss = { textOpen = false },
            onSaved = { textOpen = false },
            viewModel = photoViewModel,
        )
    }
    if (photoExplain) {
        FoodPhotoUnavailableDialog(photoState, onRemoveModel = photoViewModel::removeModel, onDismiss = { photoExplain = false })
    }
    if (copyConfirm) {
        AlertDialog(
            onDismissRequest = { copyConfirm = false },
            modifier = Modifier.testTag("fuel_copy_dialog"),
            title = { Text(stringResource(R.string.trf_copy_confirm_title)) },
            text = { Text(pluralStringResource(R.plurals.trf_copy_confirm_text, state.previousDayCount, state.previousDayCount)) },
            confirmButton = {
                TextButton(onClick = { viewModel.copyPreviousDay(); copyConfirm = false }, modifier = Modifier.testTag("fuel_copy_confirm")) {
                    Text(stringResource(R.string.trf_copy_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { copyConfirm = false }) { Text(stringResource(R.string.tr_action_cancel)) } },
        )
    }
}

// ── Intro (shown once) ───────────────────────────────────────────────

@Composable
private fun FuelIntroCard(onGotIt: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("fuel_intro")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Restaurant, null, Modifier.size(28.dp), tint = CruxCoachDesign.colors.brandAccent)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.trf_intro_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(stringResource(R.string.trf_intro_p1), style = MaterialTheme.typography.bodyMedium)
            IntroPoint(Icons.Default.FitnessCenter, stringResource(R.string.trf_intro_protein))
            IntroPoint(Icons.Default.Terrain, stringResource(R.string.trf_intro_carbs))
            IntroPoint(Icons.Default.Info, stringResource(R.string.trf_intro_no_budget))
            IntroPoint(Icons.Default.VisibilityOff, stringResource(R.string.trf_intro_hidden))
            IntroPoint(Icons.Default.Lock, stringResource(R.string.trf_intro_private))
            TextButton(onClick = onGotIt, modifier = Modifier.align(Alignment.End).testTag("fuel_intro_ok")) {
                Text(stringResource(R.string.trf_intro_got_it))
            }
        }
    }
}

@Composable
private fun IntroPoint(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(20.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ── Day view ─────────────────────────────────────────────────────────

@Composable
private fun DayHeader(state: FuelState, onPrevious: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onPrevious, modifier = Modifier.testTag("fuel_prev_day")) {
            Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.trf_prev_day))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (state.isToday) stringResource(R.string.trf_today) else state.day?.shortLabel().orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("fuel_day_label"),
            )
            Text(dayLoadLabel(state.dayLoad), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onNext, enabled = !state.isToday, modifier = Modifier.testTag("fuel_next_day")) {
            Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.trf_next_day))
        }
        if (!state.isToday) {
            TextButton(onClick = onToday) { Text(stringResource(R.string.trf_today)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DaySummaryCard(state: FuelState, onAddWater: (Int) -> Unit, onRemoveWater: (HydrationEntry) -> Unit) {
    val t = state.targets
    val totals = MacroTotals.of(state.entries)
    Card(Modifier.fillMaxWidth().testTag("fuel_targets")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trf_day_total), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (state.profile.showCalories && totals.entries > 0) {
                    Text(kcalText(totals.kcal, totals.kcalEstimated), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("fuel_kcal"))
                }
            }
            if (t == null) {
                Text(stringResource(R.string.trf_needs_weight), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp).testTag("fuel_needs_weight"))
            }
            ProgressRow(
                label = stringResource(R.string.trf_protein),
                value = totals.protein, target = t?.proteinG?.toDouble() ?: 0.0,
                valueText = if (t != null) stringResource(R.string.trf_progress_g, totals.protein.roundToInt(), t.proteinG)
                    else stringResource(R.string.trf_value_g, totals.protein.roundToInt()),
                supporting = t?.let { remainingText(totals.protein, it.proteinG) },
                info = t?.let { stringResource(R.string.trf_protein_info, it.proteinRangeG.first, it.proteinRangeG.last) },
                tag = "fuel_protein",
            )
            ProgressRow(
                label = stringResource(R.string.trf_carbs),
                value = totals.carbs, target = t?.carbsG?.toDouble() ?: 0.0,
                valueText = if (t != null) stringResource(R.string.trf_progress_g, totals.carbs.roundToInt(), t.carbsG)
                    else stringResource(R.string.trf_value_g, totals.carbs.roundToInt()),
                supporting = t?.let {
                    listOf(stringResource(R.string.trf_carbs_per_kg, formatNumber(it.carbsPerKg)) + " · " + dayLoadLabel(it.dayLoad),
                        remainingText(totals.carbs, it.carbsG)).joinToString(" · ")
                },
                infoTitle = stringResource(R.string.trf_carbs_info_title),
                info = t?.let { stringResource(R.string.trf_carbs_info, dayLoadLabel(it.dayLoad), formatNumber(it.carbsPerKg)) },
                tag = "fuel_carbs",
            )
            val share = totals.fatEnergyShare
            ProgressRow(
                label = stringResource(R.string.trf_fat),
                value = totals.fat, target = 0.0,
                valueText = stringResource(R.string.trf_value_g, totals.fat.roundToInt()),
                supporting = share?.let {
                    stringResource(R.string.trf_fat_share, (it * 100).roundToInt(),
                        (MacroTotals.FAT_SHARE_MIN * 100).roundToInt(), (MacroTotals.FAT_SHARE_MAX * 100).roundToInt())
                },
                info = stringResource(R.string.trf_fat_info),
                tag = "fuel_fat",
            )
            val waterTarget = t?.waterMl
            val units = state.profile.units
            // Stored in ml; fl oz in US units.
            val volume = FuelUnits.unitFor(units, drink = true)
            ProgressRow(
                label = stringResource(R.string.trf_water),
                value = state.waterMl.toDouble(), target = (waterTarget ?: 0).toDouble(),
                valueText = if (waterTarget != null) {
                    stringResource(R.string.trf_progress_volume, inputText(state.waterMl.toDouble(), volume),
                        amountText(waterTarget.toDouble(), volume))
                } else amountText(state.waterMl.toDouble(), volume),
                tag = "fuel_water",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FuelUnits.waterPresetsMl(units).forEachIndexed { i, ml ->
                    FilledTonalButton(onClick = { onAddWater(ml) }, modifier = Modifier.testTag("fuel_water_$ml")) {
                        if (i == 0) { Icon(Icons.Default.LocalDrink, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)) }
                        Text(stringResource(R.string.trf_water_add, amountText(ml.toDouble(), volume)))
                    }
                }
            }
            if (state.water.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    state.water.forEach { w ->
                        val label = amountText(w.ml.toDouble(), volume)
                        val desc = stringResource(R.string.trf_water_entry_desc, label)
                        AssistChip(
                            onClick = { onRemoveWater(w) },
                            label = { Text(label) },
                            leadingIcon = { Icon(Icons.Default.LocalDrink, null, Modifier.size(16.dp)) },
                            modifier = Modifier.semantics { contentDescription = desc }.testTag("fuel_water_entry_${w.id}"),
                        )
                    }
                }
            }
        }
    }
}

/** "noch 61 g" until a target is met, then "Ziel erreicht" – never a red "over". */
@Composable
private fun remainingText(value: Double, target: Int): String {
    val left = target - value.roundToInt()
    return if (left > 0) stringResource(R.string.trf_to_go, left) else stringResource(R.string.trf_reached)
}

/** Grams for display: one decimal below 10 g, whole grams above. */
fun grams(value: Double): String = formatNumber(value, if (value < 10) 1 else 0)

/** "470 kcal", or "≈ 470 kcal" when (part of) it comes from the macros. */
@Composable
private fun kcalText(kcal: Double, estimated: Boolean): String =
    stringResource(if (estimated) R.string.trf_kcal_estimated else R.string.trf_kcal_value, kcal.roundToInt())

/** "P 32 · K 85 · F 14 g", plus kcal when calories are shown. */
@Composable
fun macrosText(protein: Double?, carbs: Double?, fat: Double?, energy: MacroTotals.Energy?, showCalories: Boolean): String {
    val macros = stringResource(R.string.trf_entry_macros_g,
        protein?.let(::grams) ?: "–", carbs?.let(::grams) ?: "–", fat?.let(::grams) ?: "–")
    return if (showCalories && energy != null) macros + " · " + kcalText(energy.kcal, energy.estimated) else macros
}

@Composable
private fun MealCard(
    meal: Meal, totals: MacroTotals, entries: List<FoodLogEntry>, showCalories: Boolean,
    units: UnitSystem, isDrink: (FoodLogEntry) -> Boolean, onEdit: (FoodLogEntry) -> Unit, onDelete: (FoodLogEntry) -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("fuel_meal_${meal.name.lowercase()}")) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(mealLabel(meal), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(macrosText(totals.protein, totals.carbs, totals.fat,
                    MacroTotals.Energy(totals.kcal, totals.kcalEstimated).takeIf { totals.entries > 0 }, showCalories),
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("fuel_meal_total_${meal.name.lowercase()}"))
            }
            entries.forEach { entry ->
                EntryRow(entry, showCalories, FuelUnits.unitFor(units, isDrink(entry)), onEdit = { onEdit(entry) }, onDelete = { onDelete(entry) })
            }
        }
    }
}

@Composable
private fun ProgressRow(
    label: String,
    value: Double,
    target: Double,
    valueText: String,
    tag: String,
    supporting: String? = null,
    info: String? = null,
    infoTitle: String = label,
) {
    Column(Modifier.padding(top = 8.dp).testTag(tag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodySmall)
            if (info != null) InfoButton(infoTitle, info)
        }
        if (target > 0) {
            LinearProgressIndicator(
                progress = { (value / target).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                // Passing a target is fine; there is no "over" state to warn about.
                color = CruxCoachDesign.colors.positive,
            )
        }
        if (supporting != null) {
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(entry: FoodLogEntry, showCalories: Boolean, unit: FuelUnits.Amount, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val amount = when {
        entry.amountG != null -> amountText(entry.amountG!!, unit)
        entry.portions != null && entry.portions != 1.0 -> stringResource(R.string.trf_entry_portions, formatNumber(entry.portions!!))
        else -> null
    }
    val macros = macrosText(entry.proteinG, entry.carbsG, entry.fatG, MacroTotals.energy(entry), showCalories)
    ListItem(
        headlineContent = { Text(entry.name) },
        supportingContent = { Text(listOfNotNull(amount, macros).joinToString(" · ")) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("fuel_entry_menu_${entry.id}")) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.trf_entry_menu, entry.name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.trf_entry_edit)) },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menu = false; onEdit() },
                        modifier = Modifier.testTag("fuel_entry_edit_${entry.id}"),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tr_action_delete)) },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; onDelete() },
                        modifier = Modifier.testTag("fuel_entry_delete_${entry.id}"),
                    )
                }
            }
        },
        modifier = Modifier.combinedClickable(
            onClickLabel = stringResource(R.string.trf_entry_edit),
            onLongClickLabel = stringResource(R.string.trf_entry_menu, entry.name),
            onClick = onEdit, onLongClick = { menu = true },
        ).testTag("fuel_entry_${entry.id}"),
    )
}

/** The week's iron, calcium and vitamin D next to EFSA reference values – an estimate, no red states. */
@Composable
internal fun MicroWeekCard(summary: MicroWatch.Summary, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().testTag("fuel_micros")) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trf_micro_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                InfoButton(stringResource(R.string.trf_micro_title), stringResource(R.string.trf_micro_info))
            }
            summary.items.forEach { item ->
                val (label, unit) = when (item.nutrient) {
                    MicroWatch.Nutrient.IRON -> R.string.trf_micro_iron to "mg"
                    MicroWatch.Nutrient.CALCIUM -> R.string.trf_micro_calcium to "mg"
                    MicroWatch.Nutrient.VITAMIN_D -> R.string.trf_micro_vitamin_d to "µg"
                }
                ProgressRow(
                    label = stringResource(label),
                    value = item.perDay, target = item.reference,
                    valueText = stringResource(R.string.trf_micro_value, formatNumber(item.perDay, if (item.reference < 100) 1 else 0),
                        formatNumber(item.reference, 0), unit),
                    tag = "fuel_micro_${item.nutrient.name.lowercase()}",
                )
            }
            Text(
                pluralStringResource(R.plurals.trf_micro_coverage, summary.loggedDays, (summary.coverage * 100).roundToInt(), summary.loggedDays),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

