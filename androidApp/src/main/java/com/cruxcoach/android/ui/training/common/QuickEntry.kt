package com.cruxcoach.android.ui.training.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.equipmentLabel
import com.cruxcoach.android.ui.training.formatMass
import com.cruxcoach.android.ui.training.formatNumber
import com.cruxcoach.android.ui.training.parseDecimal
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.roundToInt

/*
 * Small inputs that every hint can open in place ("Kein Hinweis ohne Handlung"):
 * a missing body weight or equipment profile is asked right where the app
 * needs it, never by sending the athlete to look for it in the settings.
 */

/** A tick on a selected filter chip: the tint alone is too faint to read on the dark theme (device test 2026-10-09). */
fun chipCheck(selected: Boolean): (@Composable () -> Unit)? =
    if (selected) { { Icon(Icons.Default.Check, null, Modifier.size(FilterChipDefaults.IconSize)) } } else null

/** Body weights the app accepts, in kg. */
val PLAUSIBLE_WEIGHT_KG = 20.0..300.0

/**
 * "Gewicht heute" as a bottom sheet: one big number, −/+ in 0.1 kg (0.2 lb)
 * steps around the last value, and why it is asked. Saving with the prefilled
 * value is one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeightSheet(
    units: UnitSystem,
    /** Last known weight (trend), prefilled when the numbers are not hidden. */
    lastKg: Double?,
    hideNumbers: Boolean,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
    /** Why the weight is asked here, e.g. "für deine Protein- und Kohlenhydratziele". */
    reason: String? = null,
) {
    val step = if (units == UnitSystem.IMPERIAL) 0.2 else 0.1
    val start = lastKg?.takeIf { !hideNumbers }?.let { Units.massToDisplay(it, units) }
    var input by rememberSaveable { mutableStateOf(start?.let { formatNumber(it, 1) } ?: "") }
    val display = parseDecimal(input)
    val kg = display?.let { Units.massFromDisplay(it, units) }
    val valid = kg != null && kg in PLAUSIBLE_WEIGHT_KG
    fun nudge(by: Double) {
        val base = display ?: start ?: (if (units == UnitSystem.IMPERIAL) 150.0 else 70.0)
        input = formatNumber(((base + by) * 10).roundToInt() / 10.0, 1)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("weight_sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.tru_weight_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            reason?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp)) {
                FilledTonalIconButton(onClick = { nudge(-step) }, modifier = Modifier.size(56.dp).testTag("weight_minus")) {
                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.tru_weight_less))
                }
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.Bold),
                    suffix = { Text(Units.massUnit(units), style = MaterialTheme.typography.titleMedium) },
                    placeholder = { Text(if (units == UnitSystem.IMPERIAL) "150" else "70", style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                    // "Fertig" on the keyboard saves, like the button.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (valid) kg?.let(onSave) }),
                    modifier = Modifier.width(170.dp).padding(horizontal = 12.dp).testTag("weight_input"),
                )
                FilledTonalIconButton(onClick = { nudge(step) }, modifier = Modifier.size(56.dp).testTag("weight_plus")) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.tru_weight_more))
                }
            }
            if (lastKg != null && !hideNumbers) {
                Text(stringResource(R.string.tru_weight_last, formatMass(lastKg, units)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            Button(onClick = { kg?.let(onSave) }, enabled = valid,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 52.dp).testTag("weight_save")) {
                Text(stringResource(R.string.tr_action_save))
            }
        }
    }
}

internal val PRESET_HOME = setOf(EquipmentV2.HANGBOARD, EquipmentV2.PULL_UP_BAR, EquipmentV2.BANDS, EquipmentV2.DUMBBELL)
internal val PRESET_GYM = setOf(EquipmentV2.HANGBOARD, EquipmentV2.PULL_UP_BAR, EquipmentV2.RINGS, EquipmentV2.DUMBBELL,
    EquipmentV2.KETTLEBELL, EquipmentV2.BARBELL, EquipmentV2.PLATES, EquipmentV2.BANDS, EquipmentV2.BENCH, EquipmentV2.BOX,
    EquipmentV2.CABLE, EquipmentV2.WALL, EquipmentV2.BOARD, EquipmentV2.CAMPUS_BOARD, EquipmentV2.FOAM_ROLLER, EquipmentV2.DIP_BARS)
internal val PRESET_TRAVEL = setOf(EquipmentV2.BANDS)

/** Equipment the athlete always has (floor, mat); not offered as a choice. */
internal val ALWAYS_THERE = setOf(EquipmentV2.NONE, EquipmentV2.MAT)

/**
 * Where you train (presets) and then what you have, as wrapping chips. The
 * same editor sits in the settings and in the sheet that Today's checklist opens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EquipmentEditor(selected: Set<EquipmentV2>, onChange: (Set<EquipmentV2>) -> Unit) {
    Text(stringResource(R.string.tru_equipment_where), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        listOf(R.string.tra_preset_home to PRESET_HOME, R.string.tra_preset_gym to PRESET_GYM, R.string.tra_preset_travel to PRESET_TRAVEL)
            .forEachIndexed { i, (label, preset) ->
                val active = selected - ALWAYS_THERE == preset
                FilterChip(selected = active, onClick = { onChange(preset + ALWAYS_THERE) }, label = { Text(stringResource(label)) },
                    leadingIcon = chipCheck(active),
                    modifier = Modifier.testTag(listOf("preset_home", "preset_gym", "preset_travel")[i]))
            }
    }
    Text(stringResource(R.string.tru_equipment_what), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        EquipmentV2.entries.filter { it !in ALWAYS_THERE }.forEach { e ->
            val on = e in selected
            FilterChip(selected = on, onClick = { onChange(if (on) selected - e else selected + e + ALWAYS_THERE) },
                leadingIcon = chipCheck(on),
                label = { Text(equipmentLabel(e)) }, modifier = Modifier.testTag("equipment_${e.name.lowercase()}"))
        }
    }
}

/** The equipment editor as a sheet with one "Fertig"; changes are a draft until then. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquipmentSheet(initial: Set<EquipmentV2>, onDismiss: () -> Unit, onSave: (Set<EquipmentV2>) -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("equipment_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.tru_setup_equipment), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.tru_equipment_why), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { EquipmentEditor(draft) { draft = it } }
            Button(onClick = { onSave(draft + ALWAYS_THERE) }, enabled = (draft - ALWAYS_THERE).isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 52.dp).testTag("equipment_save")) {
                Text(stringResource(R.string.tru_done))
            }
        }
    }
}

/**
 * A ring that fills towards a target, with the value in the middle. Passing
 * the target is fine – the ring just stays full, never turns red.
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    stroke: Dp = 8.dp,
    color: Color = CruxCoachDesign.colors.positive,
    // Visible on any card in both themes (surfaceVariant vanished on light cards).
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    content: @Composable BoxScope.() -> Unit = {},
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(trackColor, -90f, 360f, false, topLeft, arcSize, style = Stroke(w))
            if (animated > 0f) drawArc(color, -90f, 360f * animated, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/**
 * A hint that something is missing, always with the action that fixes it
 * right here. Neutral, not an error: a task, not a warning (design.md).
 */
@Composable
fun NeedsDataCard(
    icon: ImageVector,
    title: String,
    text: String,
    action: String,
    tag: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = CruxCoachDesign.colors.brandAccent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FilledTonalButton(onClick = onAction, modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp).testTag("${tag}_action")) {
            Text(action)
        }
    }
}

/**
 * A tile with an icon, a label, one big value and a line below: Today's day
 * tiles and the Progress hub use the same one. A missing value shows its
 * action in the brand colour instead ("Eintragen").
 */
@Composable
fun ValueTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    label: String,
    value: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier,
    supporting: String? = null,
    valueIsAction: Boolean = false,
    progress: Float? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Card(onClick = onClick, modifier = modifier.heightIn(min = 112.dp).testTag(tag)) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 24.dp)) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f), maxLines = 1)
                trailing?.invoke()
            }
            Column(Modifier.padding(end = 10.dp)) {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1,
                    color = if (valueIsAction) CruxCoachDesign.colors.brandAccent else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 6.dp))
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = tint, drawStopIndicator = {},
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                supporting?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
