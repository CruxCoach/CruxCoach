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
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.android.ui.training.body.shortLabel
import com.cruxcoach.athlete.logic.RedsSignal
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.HydrationEntry
import com.cruxcoach.athlete.model.Meal
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun FuelScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: FuelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var quickAdd by remember { mutableStateOf(false) }
    var foodsOpen by remember { mutableStateOf(false) }
    var amountFor by remember { mutableStateOf<FoodItem?>(null) }
    var createFood by remember { mutableStateOf(false) }
    var copyConfirm by remember { mutableStateOf(false) }
    val defaultMeal = remember { FuelViewModel.mealForHour(java.time.LocalTime.now().hour) }
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
            !state.profile.fuelEnabled || !state.profile.fuelIntroAccepted ->
                FuelIntro(Modifier.padding(padding), onEnable = viewModel::enableFuel, onLater = onBack)
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag("fuel_list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { DayHeader(state, viewModel::previousDay, viewModel::nextDay, viewModel::goToday) }
                if (state.redsSignals.isNotEmpty()) item { FuelSupportCard(state.redsSignals) }
                item { TargetsCard(state, onAddWater = viewModel::addWater, onRemoveWater = ::deleteWater) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { quickAdd = true }, modifier = Modifier.testTag("fuel_quick_add")) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.trf_quick_add))
                        }
                        OutlinedButton(onClick = { foodsOpen = true }, modifier = Modifier.testTag("fuel_my_foods")) {
                            Icon(Icons.Default.Bookmarks, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.trf_my_foods))
                        }
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
                Meal.entries.forEach { meal ->
                    val inMeal = state.entries.filter { it.meal == meal }
                    if (inMeal.isNotEmpty()) {
                        item(key = "meal_${meal.name}") { SectionTitle(mealLabel(meal)) }
                        items(inMeal, key = { it.id }) { entry -> EntryRow(entry, onDelete = { deleteEntry(entry) }) }
                    }
                }
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
        )
    }
    amountFor?.let { item ->
        AmountDialog(
            item = item,
            initialMeal = defaultMeal,
            onDismiss = { amountFor = null },
            onConfirm = { meal, portions, grams -> viewModel.addFood(item, meal, portions, grams); amountFor = null },
        )
    }
    if (createFood) {
        CreateFoodDialog(
            onDismiss = { createFood = false },
            onSave = { name, brand, per100, servingG, label ->
                viewModel.createFood(name, brand, per100, servingG, label)
                createFood = false
                foodsOpen = true
            },
        )
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

// ── Intro (opt-in) ───────────────────────────────────────────────────

@Composable
private fun FuelIntro(modifier: Modifier, onEnable: () -> Unit, onLater: () -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).testTag("fuel_intro"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Restaurant, null, Modifier.size(48.dp), tint = CruxCoachDesign.colors.brandAccent)
        Text(stringResource(R.string.trf_intro_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.trf_intro_p1), style = MaterialTheme.typography.bodyLarge)
        IntroPoint(Icons.Default.FitnessCenter, stringResource(R.string.trf_intro_protein))
        IntroPoint(Icons.Default.Terrain, stringResource(R.string.trf_intro_carbs))
        IntroPoint(Icons.Default.Info, stringResource(R.string.trf_intro_no_budget))
        IntroPoint(Icons.Default.VisibilityOff, stringResource(R.string.trf_intro_hidden))
        IntroPoint(Icons.Default.Lock, stringResource(R.string.trf_intro_private))
        Spacer(Modifier.height(8.dp))
        Button(onClick = onEnable, modifier = Modifier.fillMaxWidth().testTag("fuel_enable")) {
            Text(stringResource(R.string.trf_intro_enable))
        }
        TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth().testTag("fuel_later")) {
            Text(stringResource(R.string.trf_intro_later))
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
private fun TargetsCard(state: FuelState, onAddWater: (Int) -> Unit, onRemoveWater: (HydrationEntry) -> Unit) {
    val t = state.targets
    Card(Modifier.fillMaxWidth().testTag("fuel_targets")) {
        Column(Modifier.padding(16.dp)) {
            if (t == null) {
                Text(stringResource(R.string.trf_needs_weight), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("fuel_needs_weight"))
            } else {
                ProgressRow(
                    label = stringResource(R.string.trf_protein),
                    value = state.protein, target = t.proteinG.toDouble(),
                    valueText = stringResource(R.string.trf_progress_g, state.protein.roundToInt(), t.proteinG),
                    info = stringResource(R.string.trf_protein_info, t.proteinRangeG.first, t.proteinRangeG.last),
                    tag = "fuel_protein",
                )
                ProgressRow(
                    label = stringResource(R.string.trf_carbs),
                    value = state.carbs, target = t.carbsG.toDouble(),
                    valueText = stringResource(R.string.trf_progress_g, state.carbs.roundToInt(), t.carbsG),
                    supporting = stringResource(R.string.trf_carbs_per_kg, formatNumber(t.carbsPerKg)),
                    infoTitle = stringResource(R.string.trf_carbs_info_title),
                    info = stringResource(R.string.trf_carbs_info, dayLoadLabel(t.dayLoad), formatNumber(t.carbsPerKg)),
                    tag = "fuel_carbs",
                )
            }
            val waterTarget = t?.waterMl
            ProgressRow(
                label = stringResource(R.string.trf_water),
                value = state.waterMl.toDouble(), target = (waterTarget ?: 0).toDouble(),
                valueText = if (waterTarget != null) stringResource(R.string.trf_progress_ml, state.waterMl, waterTarget) else "${state.waterMl} ml",
                tag = "fuel_water",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilledTonalButton(onClick = { onAddWater(250) }, modifier = Modifier.testTag("fuel_water_250")) {
                    Icon(Icons.Default.LocalDrink, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.trf_water_250))
                }
                FilledTonalButton(onClick = { onAddWater(500) }, modifier = Modifier.testTag("fuel_water_500")) {
                    Text(stringResource(R.string.trf_water_500))
                }
            }
            if (state.water.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    state.water.forEach { w ->
                        val desc = stringResource(R.string.trf_water_entry_desc, w.ml)
                        AssistChip(
                            onClick = { onRemoveWater(w) },
                            label = { Text("${w.ml} ml") },
                            leadingIcon = { Icon(Icons.Default.LocalDrink, null, Modifier.size(16.dp)) },
                            modifier = Modifier.semantics { contentDescription = desc }.testTag("fuel_water_entry_${w.id}"),
                        )
                    }
                }
            }
            if (state.profile.showCalories) {
                Text(stringResource(R.string.trf_kcal_total, state.kcal.roundToInt()), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp).testTag("fuel_kcal"))
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
private fun EntryRow(entry: FoodLogEntry, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val amount = when {
        entry.amountG != null -> stringResource(R.string.trf_entry_grams, formatNumber(entry.amountG!!))
        entry.portions != null && entry.portions != 1.0 -> stringResource(R.string.trf_entry_portions, formatNumber(entry.portions!!))
        else -> null
    }
    val macros = if (entry.proteinG != null || entry.carbsG != null || entry.fatG != null) {
        stringResource(R.string.trf_entry_macros,
            entry.proteinG?.let { formatNumber(it) } ?: "–",
            entry.carbsG?.let { formatNumber(it) } ?: "–",
            entry.fatG?.let { formatNumber(it) } ?: "–")
    } else null
    ListItem(
        headlineContent = { Text(entry.name) },
        supportingContent = {
            val parts = listOfNotNull(amount, macros)
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "))
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("fuel_entry_menu_${entry.id}")) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.trf_entry_menu, entry.name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tr_action_delete)) },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; onDelete() },
                        modifier = Modifier.testTag("fuel_entry_delete_${entry.id}"),
                    )
                }
            }
        },
        modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true }).testTag("fuel_entry_${entry.id}"),
    )
}

@Composable
private fun FuelSupportCard(signals: List<RedsSignal>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CruxCoachDesign.colors.cautionContainer,
            contentColor = CruxCoachDesign.colors.onCautionContainer),
        modifier = Modifier.fillMaxWidth().testTag("fuel_reds"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Favorite, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.trf_reds_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                InfoButton(stringResource(R.string.trf_reds_title), stringResource(R.string.trf_reds_info))
            }
            Text(
                stringResource(if (RedsSignal.LOW_CARBS_ON_TRAINING_DAYS in signals) R.string.trf_reds_low_carbs else R.string.trf_reds_other),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
