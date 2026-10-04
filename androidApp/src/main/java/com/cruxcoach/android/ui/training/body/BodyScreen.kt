package com.cruxcoach.android.ui.training.body

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.*
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.MetricGroup
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun BodyScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: BodyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // null = closed; Pair(entry being edited or null for new, default metric)
    var dialog by remember { mutableStateOf<Pair<BodyMeasurement?, BodyMetric>?>(null) }
    val deletedText = stringResource(R.string.trb_deleted)
    val undoText = stringResource(R.string.trb_undo)

    fun deleteWithUndo(entry: BodyMeasurement) {
        viewModel.delete(entry)
        scope.launch {
            if (snackbar.showSnackbar(deletedText, undoText, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                viewModel.restore(entry)
            }
        }
    }

    TrainingScaffold(
        title = stringResource(R.string.tr_nav_body),
        onBack = onBack,
        actions = {
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("body_settings")) {
                Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.tr_action_settings))
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { dialog = null to BodyMetric.WEIGHT }, modifier = Modifier.testTag("body_add")) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.trb_add))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@TrainingScaffold
        }
        val units = state.profile.units
        val hide = state.profile.hideBodyNumbers
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).testTag("body_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        ) {
            if (state.redsSignals.isNotEmpty()) item { SupportCard() }
            item { Column { WeightSection(state, onRange = viewModel::setRange) } }
            item {
                SectionTitle(stringResource(R.string.trb_measurements)) {
                    TextButton(onClick = { dialog = null to BodyMetric.HEIGHT }, modifier = Modifier.testTag("body_add_measurement")) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.trb_add_measurement))
                    }
                }
            }
            item { ApeIndexRow(state.apeIndexCm, units) }
            val summaries = state.summaries.filter { BodyMetric.fromKey(it.metricKey)?.group != MetricGroup.WEIGHT }
            if (summaries.isEmpty()) item { EmptyHint(stringResource(R.string.trb_no_measurements)) }
            MetricGroup.entries.filter { it != MetricGroup.WEIGHT }.forEach { group ->
                val inGroup = summaries.filter { BodyMetric.fromKey(it.metricKey)?.group == group }
                items(inGroup, key = { "sum_" + it.metricKey }) { summary ->
                    SummaryRow(summary, units, hidden = isHidden(summary.metricKey, hide)) {
                        dialog = summary.latest to (BodyMetric.fromKey(summary.metricKey) ?: BodyMetric.WEIGHT)
                    }
                }
            }
            item { Column { StrengthSection(state) } }
            if (state.recent.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.trb_recent)) }
                items(state.recent, key = { "rec_" + it.day + it.metric }) { entry ->
                    RecentRow(entry, units, hidden = isHidden(entry.metric, hide)) {
                        dialog = entry to (BodyMetric.fromKey(entry.metric) ?: BodyMetric.WEIGHT)
                    }
                }
            }
            item {
                Text(stringResource(R.string.trb_settings_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
            }
        }
    }

    dialog?.let { (entry, defaultMetric) ->
        MeasurementDialog(
            initial = entry,
            defaultMetric = defaultMetric,
            units = state.profile.units,
            today = state.today ?: LocalDate.parse(java.time.LocalDate.now().toString()),
            onSave = { key, day, value, unit -> viewModel.save(key, day, value, unit, entry); dialog = null },
            onDelete = entry?.let { e -> { deleteWithUndo(e); dialog = null } },
            onDismiss = { dialog = null },
        )
    }
}

/** With "hide numbers" on, everything body-image related stays hidden; build data (height, span) does not. */
private fun isHidden(metricKey: String, hideNumbers: Boolean): Boolean {
    if (!hideNumbers) return false
    return BodyMetric.fromKey(metricKey)?.group != MetricGroup.ANTHROPOMETRY
}

private fun humanize(key: String) = key.replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun labelFor(metricKey: String): String = BodyMetric.fromKey(metricKey)?.let { metricLabel(it) } ?: humanize(metricKey)

@Composable
private fun formatValue(value: Double, unit: String, units: UnitSystem, signed: Boolean = false): String = when (unit) {
    "kg" -> formatMass(value, units, signed)
    "cm" -> if (signed && value > 0) "+" + formatLength(value, units) else formatLength(value, units)
    "%" -> (if (signed && value > 0) "+" else "") + formatNumber(value) + " %"
    else -> (if (signed && value > 0) "+" else "") + formatNumber(value) + " " + unit
}

// ── Weight ───────────────────────────────────────────────────────────

@Composable
private fun WeightSection(state: BodyState, onRange: (BodyRange) -> Unit) {
    val units = state.profile.units
    val hide = state.profile.hideBodyNumbers
    val rate = state.weeklyRateKg
    val direction = stringResource(when {
        rate == null -> R.string.trb_direction_unknown
        abs(rate) < 0.1 -> R.string.trb_direction_flat
        rate > 0 -> R.string.trb_direction_up
        else -> R.string.trb_direction_down
    })
    SectionTitle(stringResource(R.string.tr_metric_weight)) {
        InfoButton(stringResource(R.string.trb_trend_info_title), stringResource(R.string.trb_trend_info_text))
    }
    val trend = state.weight.lastOrNull()?.trend
    if (trend == null) {
        EmptyHint(stringResource(R.string.trb_no_weight))
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hide) {
            StatTile(stringResource(R.string.trb_tile_trend), direction, Modifier.weight(1f))
        } else {
            StatTile(stringResource(R.string.trb_tile_trend), formatMass(trend, units), Modifier.weight(1f), supporting = direction)
            StatTile(stringResource(R.string.trb_tile_rate),
                rate?.let { stringResource(R.string.trb_rate_value, formatMass(it, units, signed = true)) } ?: "–",
                Modifier.weight(1f))
        }
        StatTile(stringResource(R.string.trb_tile_entries), state.weight.size.toString(), Modifier.weight(0.7f))
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BodyRange.entries.forEach { r ->
            FilterChip(
                selected = state.range == r,
                onClick = { onRange(r) },
                label = { Text(stringResource(when (r) {
                    BodyRange.M1 -> R.string.trb_range_1m
                    BodyRange.M3 -> R.string.trb_range_3m
                    BodyRange.M6 -> R.string.trb_range_6m
                    BodyRange.Y1 -> R.string.trb_range_1y
                    BodyRange.ALL -> R.string.trb_range_all
                })) },
                modifier = Modifier.testTag("body_range_${r.name.lowercase()}"),
            )
        }
    }
    val visible = state.visibleWeight
    val description = if (visible.isEmpty()) "" else if (hide) {
        stringResource(R.string.trb_chart_description_hidden, visible.first().day.shortLabel(), visible.last().day.shortLabel(), direction)
    } else {
        stringResource(R.string.trb_chart_description, visible.first().day.shortLabel(), visible.last().day.shortLabel(),
            formatMass(visible.first().trend, units), formatMass(visible.last().trend, units))
    }
    Spacer(Modifier.height(8.dp))
    TrendChart(visible, hideNumbers = hide, units = units, description = description, modifier = Modifier.fillMaxWidth())
}

// ── Measurements ─────────────────────────────────────────────────────

@Composable
private fun ApeIndexRow(apeCm: Double?, units: UnitSystem) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("body_ape_index")) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.trb_ape_index), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (apeCm != null) {
                    val v = Units.lengthToDisplay(apeCm, units)
                    val text = (if (v > 0) "+" else "") + formatNumber(v) + " " + Units.lengthUnit(units)
                    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                } else {
                    Text(stringResource(R.string.trb_ape_missing), style = MaterialTheme.typography.bodySmall)
                }
            }
            InfoButton(stringResource(R.string.trb_ape_index), stringResource(R.string.trb_ape_info_text))
        }
    }
}

@Composable
private fun SummaryRow(summary: MetricSummary, units: UnitSystem, hidden: Boolean, onClick: () -> Unit) {
    val latest = summary.latest
    ListItem(
        headlineContent = { Text(labelFor(summary.metricKey)) },
        supportingContent = {
            val parts = buildList {
                add(LocalDate.parse(latest.day).shortLabel())
                if (!hidden) summary.delta?.takeIf { abs(it) > 1e-9 }?.let {
                    add(stringResource(R.string.trb_delta, formatValue(it, latest.unit, units, signed = true)))
                }
            }
            Text(parts.joinToString(" · "))
        },
        trailingContent = {
            Text(if (hidden) stringResource(R.string.trb_hidden_value) else formatValue(latest.value, latest.unit, units),
                style = MaterialTheme.typography.titleMedium)
        },
        modifier = Modifier.clickable(onClick = onClick).testTag("body_summary_${summary.metricKey}"),
    )
}

@Composable
private fun RecentRow(entry: BodyMeasurement, units: UnitSystem, hidden: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(labelFor(entry.metric)) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(LocalDate.parse(entry.day).shortLabel())
                if (entry.source == BodyMeasurement.SOURCE_LEGACY) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.trb_legacy_badge),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        },
        trailingContent = {
            Text(if (hidden) stringResource(R.string.trb_hidden_value) else formatValue(entry.value, entry.unit, units))
        },
        modifier = Modifier.clickable(onClick = onClick).testTag("body_recent_${entry.metric}_${entry.day}"),
    )
}

// ── Strength to weight ───────────────────────────────────────────────

@Composable
private fun StrengthSection(state: BodyState) {
    SectionTitle(stringResource(R.string.trb_strength_title)) {
        InfoButton(stringResource(R.string.trb_strength_title), stringResource(R.string.trb_strength_info_text))
    }
    Text(stringResource(R.string.trb_strength_subtitle), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.strength.isEmpty()) {
        EmptyHint(stringResource(R.string.trb_strength_empty))
        return
    }
    val palette = listOf(CruxCoachDesign.colors.brandAccent, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
    val bySlug = state.strength.groupBy { it.slug }
    val colors: Map<String, Color> = BodyViewModel.STRENGTH_SLUGS.mapIndexed { i, slug -> slug to palette[i % palette.size] }.toMap()
    val language = catalogLanguage()
    Spacer(Modifier.height(8.dp))
    StrengthChart(bySlug, colors, stringResource(R.string.trb_strength_chart_description), Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    bySlug.forEach { (slug, points) ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(colors[slug] ?: Color.Gray))
            Spacer(Modifier.width(8.dp))
            Text(state.strengthDefs[slug]?.name(language) ?: humanize(slug.substringAfter('.')),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.trb_strength_best, points.maxOf { it.percentBodyweight }.roundToInt()),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SupportCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = CruxCoachDesign.colors.cautionContainer,
            contentColor = CruxCoachDesign.colors.onCautionContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("body_reds"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Favorite, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.trb_reds_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                InfoButton(stringResource(R.string.trb_reds_title), stringResource(R.string.trb_reds_info))
            }
            Text(stringResource(R.string.trb_reds_text), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ── Entry dialog ─────────────────────────────────────────────────────

private fun plausible(metricKey: String, canonical: Double): Boolean = when (BodyMetric.fromKey(metricKey)) {
    BodyMetric.WEIGHT -> canonical in 20.0..300.0
    BodyMetric.BODY_FAT -> canonical in 2.0..70.0
    BodyMetric.HEIGHT -> canonical in 100.0..250.0
    BodyMetric.ARM_SPAN -> canonical in 100.0..270.0
    null -> canonical > 0 && canonical < 10_000
    else -> canonical in 5.0..200.0
}

private fun toCanonical(value: Double, unit: String, units: UnitSystem): Double = when (unit) {
    "kg" -> Units.massFromDisplay(value, units)
    "cm" -> Units.lengthFromDisplay(value, units)
    else -> value
}

private fun toDisplay(value: Double, unit: String, units: UnitSystem): Double = when (unit) {
    "kg" -> Units.massToDisplay(value, units)
    "cm" -> Units.lengthToDisplay(value, units)
    else -> value
}

private fun displayUnit(unit: String, units: UnitSystem): String = when (unit) {
    "kg" -> Units.massUnit(units)
    "cm" -> Units.lengthUnit(units)
    else -> unit
}

private fun LocalDate.utcMillis(): Long = java.time.LocalDate.parse(toString()).toEpochDay() * 86_400_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasurementDialog(
    initial: BodyMeasurement?,
    defaultMetric: BodyMetric,
    units: UnitSystem,
    today: LocalDate,
    onSave: (metricKey: String, day: LocalDate, canonicalValue: Double, unit: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var metricKey by rememberSaveable { mutableStateOf(initial?.metric ?: defaultMetric.key) }
    val unit = initial?.unit ?: BodyMetric.fromKey(metricKey)?.unit ?: "kg"
    var text by rememberSaveable {
        mutableStateOf(initial?.let { formatNumber(toDisplay(it.value, it.unit, units), 2) } ?: "")
    }
    var day by rememberSaveable { mutableStateOf(initial?.day ?: today.toString()) }
    var metricMenu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val canonical = parseDecimal(text)?.let { toCanonical(it, unit, units) }
    val valid = canonical != null && plausible(metricKey, canonical)

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("body_entry_dialog"),
        title = { Text(stringResource(if (initial == null) R.string.trb_dialog_add_title else R.string.trb_dialog_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedButton(
                        onClick = { if (initial == null) metricMenu = true },
                        enabled = initial == null,
                        modifier = Modifier.fillMaxWidth().testTag("body_entry_metric"),
                    ) {
                        Text(labelFor(metricKey), modifier = Modifier.weight(1f))
                        if (initial == null) Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.trb_field_metric))
                    }
                    DropdownMenu(expanded = metricMenu, onDismissRequest = { metricMenu = false }) {
                        BodyMetric.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(metricLabel(m)) },
                                onClick = { metricKey = m.key; metricMenu = false },
                                modifier = Modifier.testTag("body_metric_${m.key}"),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.trb_field_value, displayUnit(unit, units))) },
                    singleLine = true,
                    isError = text.isNotBlank() && !valid,
                    supportingText = { if (text.isNotBlank() && !valid) Text(stringResource(R.string.trb_value_invalid)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("body_entry_value"),
                )
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().testTag("body_entry_date")) {
                    Icon(Icons.Default.CalendarMonth, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.trb_field_date, LocalDate.parse(day).shortLabel()))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { canonical?.let { onSave(metricKey, LocalDate.parse(day), it, unit) } },
                enabled = valid,
                modifier = Modifier.testTag("body_entry_save"),
            ) { Text(stringResource(R.string.tr_action_save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.testTag("body_entry_delete")) {
                        Text(stringResource(R.string.tr_action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.tr_action_cancel)) }
            }
        },
    )

    if (picking) {
        val todayMillis = today.utcMillis()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.parse(day).utcMillis(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
                override fun isSelectableYear(year: Int) = year <= java.time.LocalDate.now().year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        day = java.time.LocalDate.ofEpochDay(ms / 86_400_000L).toString()
                    }
                    picking = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
