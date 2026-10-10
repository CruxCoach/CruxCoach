package com.cruxcoach.android.ui.training.fuel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.LocalDrink
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.cruxcoach.athlete.model.AthleteProfile
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
    tabBar: @Composable () -> Unit = {},
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
    var weightOpen by remember { mutableStateOf(false) }
    var energyOpen by remember { mutableStateOf(false) }
    var goalOpen by remember { mutableStateOf(false) }
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
    /** The meal that "+" or the add button chose; every way of adding starts with it. */
    var addMeal by remember { mutableStateOf(FuelViewModel.mealForHour(java.time.LocalTime.now().hour)) }
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
    val weightSavedText = stringResource(R.string.tru_weight_saved)

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
            snackbar.currentSnackbarData?.dismiss()
            if (snackbar.showSnackbar(waterRemovedText, undoText, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                viewModel.addWater(entry.ml)
            }
        }
    }

    fun openAdd(meal: Meal) { addMeal = meal; foodsOpen = true }

    TrainingScaffold(
        title = stringResource(R.string.tr_nav_fuel),
        onBack = onBack,
        actions = {
            // Once the intro card is gone, its full explanation stays one tap away in the app bar.
            if (!state.loading && state.profile.fuelIntroAccepted) InfoButton(stringResource(R.string.trf_intro_title), fuelGuideText())
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("fuel_settings")) {
                Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.tr_action_settings))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        // One way in for every kind of food: search, scan, describe, photo or plain values.
        floatingActionButton = {
            if (!state.loading) {
                ExtendedFloatingActionButton(
                    onClick = { openAdd(FuelViewModel.mealForHour(java.time.LocalTime.now().hour)) },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(R.string.tru_fuel_add)) },
                    containerColor = CruxCoachDesign.colors.brandAccent, contentColor = CruxCoachDesign.colors.onBrandAccent,
                    modifier = Modifier.testTag("fuel_my_foods"),
                )
            }
        },
        bottomBar = tabBar,
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag("fuel_list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Nutrition is always on (owner 2026-10-05); the guard rails are
                // explained once instead of behind an opt-in.
                if (!state.profile.fuelIntroAccepted) item { FuelIntroCard(onGotIt = viewModel::acceptIntro) }
                item { DayHeader(state, viewModel::previousDay, viewModel::nextDay, viewModel::goToday) }
                if (state.energy.signals.isNotEmpty()) item { EnergyCareCard(state.energy, state.profile, "fuel_reds", onAdjustGoal = { goalOpen = true }) }
                if (state.targets == null) {
                    item(key = "needs_weight") {
                        com.cruxcoach.android.ui.training.common.NeedsDataCard(
                            icon = Icons.Default.MonitorWeight,
                            title = stringResource(R.string.tru_fuel_targets_title),
                            text = stringResource(R.string.trf_needs_weight),
                            action = stringResource(R.string.tru_enter_weight),
                            tag = "fuel_needs_weight",
                            onAction = { weightOpen = true },
                        )
                    }
                }
                item(key = "summary") { MacroSummaryCard(state, onOpenEnergy = { energyOpen = true }, onUpdateProfile = viewModel::updateProfile) }
                item(key = "water") { WaterCard(state, onAddWater = viewModel::addWater, onRemoveWater = ::deleteWater) }
                if (state.entries.isEmpty() && state.previousDayCount > 0) {
                    item(key = "copy") { CopyPreviousCard(state.previousDayCount, onCopy = { copyConfirm = true }) }
                }
                // The four meals always, so "+" is where the food goes; training snacks only once used.
                val byMeal = MacroTotals.byMeal(state.entries).toMap()
                val meals = MAIN_MEALS + Meal.entries.filter { it !in MAIN_MEALS && byMeal.containsKey(it) }
                meals.forEach { meal ->
                    item(key = "meal_${meal.name}") {
                        MealCard(meal, byMeal[meal], state.entries.filter { it.meal == meal }, state.profile.showCalories, units, isDrink,
                            onAdd = { openAdd(meal) }, onEdit = { editing = it }, onDelete = ::deleteEntry)
                    }
                }
                if (state.entries.isNotEmpty() && state.previousDayCount > 0) {
                    item(key = "copy_link") {
                        TextButton(onClick = { copyConfirm = true }, modifier = Modifier.testTag("fuel_copy_previous")) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.trf_copy_previous))
                        }
                    }
                }
                // The week's micronutrients below the day: background, not the day's task.
                micros?.let { summary -> item(key = "micros") { MicroWeekCard(summary) } }
            }
        }
    }

    if (energyOpen) {
        com.cruxcoach.android.ui.training.common.EnergySheet(
            profile = state.profile, need = state.need, weightKg = state.bodyweightKg, heightCm = state.heightCm,
            plan = state.energy.plan.takeIf { state.isToday }, eatenKcal = MacroTotals.of(state.entries).takeIf { it.entries > 0 }?.kcal,
            onDismiss = { energyOpen = false },
            onEditGoal = { energyOpen = false; goalOpen = true },
            onLogWeight = viewModel::logWeight, onLogHeight = viewModel::logHeight, onUpdateProfile = viewModel::updateProfile,
        )
    }
    if (goalOpen) {
        com.cruxcoach.android.ui.training.common.WeightGoalSheet(
            profile = state.profile, weightKg = state.bodyweightKg, heightCm = state.heightCm,
            // The pace's calorie target is today's: the sheet always plans from today.
            need = state.need.takeIf { state.isToday },
            onDismiss = { goalOpen = false },
            onSave = viewModel::saveWeightGoal,
            onLogWeight = viewModel::logWeight, onLogHeight = viewModel::logHeight,
        )
    }
    if (weightOpen) {
        com.cruxcoach.android.ui.training.common.WeightSheet(
            units = units, lastKg = state.bodyweightKg, hideNumbers = state.profile.hideBodyNumbers,
            reason = stringResource(R.string.tru_weight_reason_fuel),
            onDismiss = { weightOpen = false },
            onSave = { kg -> viewModel.logWeight(kg); weightOpen = false; scope.launch { snackbar.showSnackbar(weightSavedText) } },
        )
    }
    if (quickAdd) {
        QuickAddSheet(
            initialMeal = addMeal,
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
            title = stringResource(R.string.tru_fuel_add),
            meal = addMeal,
            onMealChange = { addMeal = it },
            addActions = {
                FoodAddActions(
                    photoAvailable = photoState.support is com.cruxcoach.athlete.logic.VisionSupport.Supported && !photoState.tooSlow,
                    onScan = { scanning = true; foodsOpen = false },
                    onDescribe = { textOpen = true; foodsOpen = false },
                    onPhoto = { photoOpen = true; foodsOpen = false },
                    onPhotoExplain = { photoExplain = true },
                    onQuick = { quickAdd = true; foodsOpen = false },
                )
            },
        )
    }
    amountFor?.let { item ->
        AmountDialog(
            item = item,
            initialMeal = addMeal,
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
                onDelete = null,
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
            initialMeal = addMeal,
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
            initialMeal = addMeal,
            onDismiss = { photoOpen = false },
            onSaved = { photoOpen = false },
            viewModel = photoViewModel,
        )
    }
    if (textOpen) {
        FoodTextSheet(
            day = (state.day ?: state.today)?.toString().orEmpty(),
            initialMeal = addMeal,
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

/** Meals that always have a place in the day, each with its own "+". */
private val MAIN_MEALS = listOf(Meal.BREAKFAST, Meal.LUNCH, Meal.DINNER, Meal.SNACK)

/**
 * The ways to add food as four big tiles under the search field: scan,
 * describe, photo, plain values. Photo stays visible on phones that cannot
 * run the model and explains why instead.
 */
@Composable
internal fun FoodAddActions(
    photoAvailable: Boolean,
    onScan: () -> Unit,
    onDescribe: () -> Unit,
    onPhoto: () -> Unit,
    onPhotoExplain: () -> Unit,
    onQuick: () -> Unit,
) {
    val unavailable = stringResource(R.string.fvp_unavailable_short)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AddAction(Icons.Default.QrCodeScanner, stringResource(R.string.tru_add_scan), "fuel_scan_tile", onScan, Modifier.weight(1f))
        AddAction(Icons.Default.EditNote, stringResource(R.string.trf_describe), "fuel_describe", onDescribe, Modifier.weight(1f))
        AddAction(Icons.Default.PhotoCamera, stringResource(R.string.fvp_button), "fuel_photo", if (photoAvailable) onPhoto else onPhotoExplain,
            Modifier.weight(1f).semantics { if (!photoAvailable) stateDescription = unavailable }, dimmed = !photoAvailable)
        AddAction(Icons.Default.Bolt, stringResource(R.string.tru_add_quick), "fuel_quick_add", onQuick, Modifier.weight(1f))
    }
}

@Composable
private fun AddAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier,
    dimmed: Boolean = false,
) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.heightIn(min = 72.dp).testTag(tag)) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f) else CruxCoachDesign.colors.brandAccent)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(top = 4.dp),
                color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ── Intro (shown once) ───────────────────────────────────────────────

/** How nutrition works and its guard rails: in the intro card's info, later in the app bar's. */
@Composable
private fun fuelGuideText(): String = listOf(
    stringResource(R.string.trf_intro_p1), stringResource(R.string.trf_intro_protein), stringResource(R.string.trf_intro_carbs),
    stringResource(R.string.trf_intro_no_budget), stringResource(R.string.trf_intro_hidden), stringResource(R.string.trf_intro_private),
).joinToString("\n\n")

/** Short on the screen, the full guard rails behind the info icon. */
@Composable
private fun FuelIntroCard(onGotIt: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("fuel_intro")) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Restaurant, null, Modifier.size(24.dp), tint = CruxCoachDesign.colors.brandAccent)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.trf_intro_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                InfoButton(stringResource(R.string.trf_intro_title), fuelGuideText())
            }
            Text(stringResource(R.string.tru_fuel_intro_short), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp, end = 8.dp))
            TextButton(onClick = onGotIt, modifier = Modifier.align(Alignment.End).testTag("fuel_intro_ok")) {
                Text(stringResource(R.string.trf_intro_got_it))
            }
        }
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
                when {
                    state.isToday -> stringResource(R.string.trf_today)
                    state.day != null && state.today != null && state.day.toEpochDays() == state.today.toEpochDays() - 1 ->
                        stringResource(R.string.trf_yesterday)
                    else -> state.day?.shortLabel().orEmpty()
                },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("fuel_day_label"),
            )
            Text(dayLoadLabel(state.dayLoad), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!state.isToday) {
            TextButton(onClick = onToday) { Text(stringResource(R.string.trf_today)) }
        }
        IconButton(onClick = onNext, enabled = !state.isToday, modifier = Modifier.testTag("fuel_next_day")) {
            Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.trf_next_day))
        }
    }
}

/**
 * Energy, carbohydrates, protein and fat as rings that fill towards the day's
 * targets, in that order (owner 2026-10-10). Energy shows while calories are
 * shown. Every ring explains itself when tapped – energy its breakdown, the
 * others how their target comes about – so no info line hangs under the rings.
 */
@Composable
private fun MacroSummaryCard(state: FuelState, onOpenEnergy: () -> Unit, onUpdateProfile: ((AthleteProfile) -> AthleteProfile) -> Unit = {}) {
    val t = state.targets
    val totals = MacroTotals.of(state.entries)
    // Energy is a ring while calories are shown: eaten against the need,
    // or against the calorie target while losing weight (owner 2026-10-09).
    val showEnergy = state.profile.showCalories
    val rings = if (showEnergy) 4 else 3
    var explained by rememberSaveable { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth().testTag("fuel_targets")) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 12.dp)) {
            // The rings share the width; four still fit side by side on a narrow phone.
            val ringSize = ((maxWidth - 12.dp * rings) / rings).coerceIn(60.dp, 92.dp)
            Row(Modifier.fillMaxWidth()) {
                val cell = Modifier.weight(1f)
                if (showEnergy) {
                    val plan = state.energy.plan
                    EnergyRing(eaten = totals.kcal, estimated = totals.kcalEstimated,
                        target = com.cruxcoach.android.ui.training.common.energyTarget(state.need, plan, state.profile),
                        losing = plan != null && plan.dailyDeficitKcal > 0, size = ringSize, onClick = onOpenEnergy, modifier = cell)
                }
                MacroRing(label = stringResource(R.string.tru_carbs_short), value = totals.carbs, target = t?.carbsG,
                    color = CruxCoachDesign.colors.brandAccent, tag = "fuel_carbs", size = ringSize, modifier = cell,
                    onClick = { explained = "carbs" })
                MacroRing(label = stringResource(R.string.trf_protein), value = totals.protein, target = t?.proteinG,
                    color = CruxCoachDesign.colors.positive, tag = "fuel_protein", size = ringSize, modifier = cell,
                    onClick = { explained = "protein" })
                MacroRing(label = stringResource(R.string.trf_fat), value = totals.fat, target = t?.fatG,
                    color = FatColor, tag = "fuel_fat", size = ringSize, modifier = cell,
                    onClick = { explained = "fat" })
            }
        }
    }
    explained?.let { which ->
        val title = stringResource(when (which) {
            "carbs" -> R.string.tru_carbs_short
            "protein" -> R.string.trf_protein
            else -> R.string.trf_fat
        })
        val text = when (which) {
            "carbs" -> listOfNotNull(t?.let { stringResource(R.string.trf_carbs_info, dayLoadLabel(it.dayLoad), formatNumber(it.carbsPerKg)) })
            "protein" -> listOfNotNull(t?.let { stringResource(R.string.trf_protein_info, it.proteinRangeG.first, it.proteinRangeG.last) })
            else -> listOfNotNull(
                t?.let {
                    stringResource(if (it.fatFromEnergy) R.string.trf_fat_target_energy else R.string.trf_fat_target_weight,
                        it.fatRangeG.first, it.fatRangeG.last)
                },
                totals.fatEnergyShare?.let { stringResource(R.string.trf_fat_share_today, (it * 100).roundToInt()) },
                stringResource(R.string.trf_fat_info),
            )
        }.ifEmpty { listOf(stringResource(R.string.trf_needs_weight)) }
        // How the target comes about, and right under it the choice: recommended or an own target (owner 2026-10-10).
        val kind = when (which) {
            "carbs" -> com.cruxcoach.athlete.logic.FuelTargets.TargetKind.CARBS
            "protein" -> com.cruxcoach.athlete.logic.FuelTargets.TargetKind.PROTEIN
            else -> com.cruxcoach.athlete.logic.FuelTargets.TargetKind.FAT
        }
        val rec = t?.let { it.recommended ?: it }
        val recommended = when (kind) {
            com.cruxcoach.athlete.logic.FuelTargets.TargetKind.CARBS -> rec?.carbsG
            com.cruxcoach.athlete.logic.FuelTargets.TargetKind.PROTEIN -> rec?.proteinG
            else -> rec?.fatG
        }
        AlertDialog(
            modifier = Modifier.testTag("fuel_explain_$which"),
            onDismissRequest = { explained = null },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    com.cruxcoach.android.ui.common.InfoText(text.joinToString("\n\n"))
                    com.cruxcoach.android.ui.training.common.OwnTargetChoice(kind, state.profile, recommended, onChange = onUpdateProfile,
                        modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = { TextButton(onClick = { explained = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

/** A ring's label: one line, shrinking a little for long words like "Kohlenhydrate" on narrow phones. */
@Composable
private fun RingLabel(text: String) {
    androidx.compose.foundation.text.BasicText(
        text, maxLines = 1, softWrap = false,
        style = MaterialTheme.typography.labelLarge.copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 14.sp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 2.dp, end = 2.dp),
    )
}

/**
 * Inside a ring: the value big, the target small below, sized to the ring. A
 * small ring drops the unit from the target ("von 206"); the line under the
 * ring carries it ("noch 68 g").
 */
@Composable
private fun RingCenter(value: String, target: String, compactTarget: String, size: androidx.compose.ui.unit.Dp, valueTag: String? = null) {
    val compact = size < 84.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = size - 18.dp)) {
        Text(value, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, maxLines = 1, modifier = valueTag?.let { Modifier.testTag(it) } ?: Modifier)
        FitText(if (compact) compactTarget else target, MaterialTheme.typography.labelSmall, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One centred line that shrinks a little instead of wrapping or clipping. */
@Composable
private fun FitText(text: String, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicText(
        text, maxLines = 1, softWrap = false,
        style = style.copy(color = color, textAlign = TextAlign.Center),
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize),
        modifier = modifier,
    )
}

@Composable
private fun MacroRing(
    label: String,
    value: Double,
    target: Int?,
    color: androidx.compose.ui.graphics.Color,
    tag: String,
    size: androidx.compose.ui.unit.Dp = 92.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clip(CruxCoachDesign.shapes.medium)
            .clickable(onClickLabel = stringResource(R.string.action_show_info, label), role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .testTag(tag)) {
        com.cruxcoach.android.ui.training.common.ProgressRing(
            progress = if (target != null && target > 0) (value / target).toFloat() else 0f,
            size = size, stroke = if (size < 84.dp) 8.dp else 9.dp, color = color,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // A reached target gets its tick – a small reward, never a "too much".
                if (target != null && target > 0 && value >= target) {
                    Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.trf_reached), tint = color,
                        modifier = Modifier.size(if (size < 84.dp) 14.dp else 18.dp))
                }
                RingCenter("${value.roundToInt()}", if (target != null) stringResource(R.string.tru_of_g, target) else "g",
                    if (target != null) stringResource(R.string.tru_of_short, target) else "g", size)
            }
        }
        RingLabel(label)
        if (target != null) {
            FitText(remainingText(value, target), MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier.fillMaxWidth())
        }
    }
}

/**
 * Energy eaten against the need (or the calorie target while losing weight).
 * A tap opens how the need is made up. Above the target it says by how much,
 * in the normal text colour – a number, not a verdict.
 */
@Composable
private fun EnergyRing(
    eaten: Double,
    estimated: Boolean,
    target: Int?,
    losing: Boolean,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val value = eaten.roundToInt()
    val openText = stringResource(R.string.trn_open_energy)
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clip(CruxCoachDesign.shapes.medium)
            .clickable(onClickLabel = openText, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .testTag("fuel_energy")) {
        com.cruxcoach.android.ui.training.common.ProgressRing(
            progress = if (target != null && target > 0) (eaten / target).toFloat() else 0f,
            size = size, stroke = if (size < 84.dp) 8.dp else 9.dp, color = EnergyColor,
        ) {
            RingCenter((if (estimated) "≈" else "") + value, if (target != null) stringResource(R.string.trn_of_kcal, target) else "kcal",
                if (target != null) stringResource(R.string.tru_of_short, target) else "kcal",
                // Four digits need a step smaller than grams.
                size - 8.dp, valueTag = "fuel_kcal")
        }
        RingLabel(stringResource(R.string.trn_energy))
        FitText(
            when {
                target == null -> openText
                value < target -> stringResource(R.string.trn_to_go, target - value)
                losing && value > target -> stringResource(R.string.trn_over_target, value - target)
                losing -> stringResource(R.string.trn_target_met)
                else -> stringResource(R.string.trn_need_met)
            },
            MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant,
            Modifier.fillMaxWidth().testTag("fuel_energy_left"),
        )
    }
}

/** Energy's own ring colour, distinct from protein (green), carbs (accent) and water (blue). */
internal val EnergyColor = androidx.compose.ui.graphics.Color(0xFF9C6ADE)

/** Fat's ring colour: gold, next to energy (violet), carbs (orange) and protein (green). */
internal val FatColor = androidx.compose.ui.graphics.Color(0xFFD9A400)

/**
 * Water as glasses: tap the next empty one for another glass, the last full
 * one to take it back. The target comes from the body weight; without one,
 * eight glasses show.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaterCard(state: FuelState, onAddWater: (Int) -> Unit, onRemoveWater: (HydrationEntry) -> Unit) {
    val units = state.profile.units
    val volume = FuelUnits.unitFor(units, drink = true)
    val glass = FuelUnits.waterPresetsMl(units).first()
    val target = state.targets?.waterMl
    val glasses = ((target ?: glass * 8) + glass - 1) / glass
    val full = (state.waterMl / glass).coerceAtMost(glasses)
    val blue = com.cruxcoach.android.ui.training.today.WaterBlue
    val reached = target != null && state.waterMl >= target
    Card(Modifier.fillMaxWidth().testTag("fuel_water")) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WaterDrop, null, tint = blue, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.trf_water), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (reached) stringResource(R.string.tru_water_reached) else stringResource(R.string.tru_water_glass_hint, amountText(glass.toDouble(), volume)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (reached) CruxCoachDesign.colors.positive else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (reached) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.testTag("fuel_water_hint"),
                    )
                }
                Text(
                    if (target != null) stringResource(R.string.trf_progress_volume, inputText(state.waterMl.toDouble(), volume), amountText(target.toDouble(), volume))
                    else amountText(state.waterMl.toDouble(), volume),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("fuel_water_total"),
                )
            }
            val addText = stringResource(R.string.trf_water_add, amountText(glass.toDouble(), volume))
            val removeText = stringResource(R.string.tru_water_remove)
            // Ten glasses fit one row on a 360 dp phone; a bigger target wraps.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 8.dp)) {
                (0 until glasses).forEach { i ->
                    val filled = i < full
                    val isLast = filled && i == full - 1
                    val isNext = i == full
                    IconButton(
                        onClick = {
                            if (isLast) state.water.maxByOrNull { it.loggedAt }?.let(onRemoveWater) else if (!filled) onAddWater(glass)
                        },
                        enabled = isLast || !filled,
                        modifier = Modifier.size(32.dp).semantics {
                            contentDescription = when { isLast -> removeText; filled -> ""; else -> addText }
                        }.testTag(if (isNext) "fuel_water_$glass" else "fuel_glass_$i"),
                    ) {
                        Icon(
                            if (filled) Icons.Default.LocalDrink else Icons.Outlined.LocalDrink, null,
                            tint = if (filled) blue else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isNext) 0.9f else 0.4f),
                            modifier = Modifier.size(if (isNext) 28.dp else 24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyPreviousCard(count: Int, onCopy: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().testTag("fuel_copy_card")) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ContentCopy, null, tint = CruxCoachDesign.colors.brandAccent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tru_copy_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(pluralStringResource(R.plurals.tru_copy_text, count, count), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onCopy, modifier = Modifier.testTag("fuel_copy_previous")) { Text(stringResource(R.string.trf_copy_confirm)) }
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
    meal: Meal, totals: MacroTotals?, entries: List<FoodLogEntry>, showCalories: Boolean,
    units: UnitSystem, isDrink: (FoodLogEntry) -> Boolean,
    onAdd: () -> Unit, onEdit: (FoodLogEntry) -> Unit, onDelete: (FoodLogEntry) -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("fuel_meal_${meal.name.lowercase()}")) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(mealLabel(meal), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (totals != null && totals.entries > 0) {
                        Text(macrosText(totals.protein, totals.carbs, totals.fat,
                            MacroTotals.Energy(totals.kcal, totals.kcalEstimated), showCalories),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("fuel_meal_total_${meal.name.lowercase()}"))
                    }
                }
                val addLabel = stringResource(R.string.tru_meal_add, mealLabel(meal))
                FilledTonalIconButton(onClick = onAdd, modifier = Modifier.testTag("fuel_add_${meal.name.lowercase()}")) {
                    Icon(Icons.Default.Add, contentDescription = addLabel)
                }
            }
            entries.forEach { entry ->
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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
) {
    Column(Modifier.padding(top = 8.dp).testTag(tag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodySmall)
        }
        if (target > 0) {
            LinearProgressIndicator(
                progress = { (value / target).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                // Passing a target is fine; there is no "over" state to warn about.
                color = CruxCoachDesign.colors.positive,
                // No end dot: at 0 % it read like a reached point (device test 2026-10-09).
                drawStopIndicator = {},
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
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
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

