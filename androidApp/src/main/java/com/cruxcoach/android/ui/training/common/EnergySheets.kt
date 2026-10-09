package com.cruxcoach.android.ui.training.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.energyWarningText
import com.cruxcoach.android.ui.training.formatMass
import com.cruxcoach.android.ui.training.formatNumber
import com.cruxcoach.android.ui.training.parseDecimal
import com.cruxcoach.athlete.logic.EnergyBalance
import com.cruxcoach.athlete.logic.EnergyReport
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.logic.WeightPlan
import com.cruxcoach.athlete.model.AthleteGoal
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.EverydayActivity
import com.cruxcoach.athlete.model.Sex
import kotlin.math.roundToInt

/*
 * Energy in the open (owner 2026-10-09): the estimated need with its parts,
 * and the weight-loss goal with its calorie target. Nothing is refused; what
 * argues against a choice is named next to it with its numbers.
 */

/** Plausible heights in cm, as in the settings. */
val PLAUSIBLE_HEIGHT_CM = 100.0..250.0

/**
 * "Energie heute": resting energy, everyday life, training, the need, and
 * while losing weight the deficit and calorie target. Missing height, birth
 * year or everyday activity are entered right here.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EnergySheet(
    profile: AthleteProfile,
    need: EnergyBalance.Need?,
    weightKg: Double?,
    heightCm: Double?,
    plan: WeightPlan.Plan?,
    eatenKcal: Double?,
    onDismiss: () -> Unit,
    onEditGoal: () -> Unit,
    onLogWeight: (Double) -> Unit,
    onLogHeight: (Double) -> Unit,
    onUpdateProfile: ((AthleteProfile) -> AthleteProfile) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("energy_sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.trn_sheet_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (weightKg == null) {
                Text(stringResource(R.string.trn_needs_weight), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                NumberEntry(stringResource(R.string.tru_weight_title), Units.massUnit(profile.units), "energy_weight",
                    toCanonical = { Units.massFromDisplay(it, profile.units) }, valid = { it in PLAUSIBLE_WEIGHT_KG }, onSave = onLogWeight)
            }
            if (heightCm == null) {
                Text(stringResource(R.string.trn_needs_height), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                NumberEntry(stringResource(R.string.trn_height_label, Units.lengthUnit(profile.units)), Units.lengthUnit(profile.units), "energy_height",
                    toCanonical = { Units.lengthFromDisplay(it, profile.units) }, valid = { it in PLAUSIBLE_HEIGHT_CM }, onSave = onLogHeight)
            }
            if (need != null) {
                Spacer(Modifier.height(12.dp))
                KcalRow(stringResource(R.string.trn_resting), need.restingKcal.toString(), "energy_resting")
                KcalRow(stringResource(R.string.trn_everyday), "+" + need.everydayKcal, "energy_everyday")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EverydayActivity.entries.forEach { e ->
                        FilterChip(selected = profile.everydayActivity == e, leadingIcon = com.cruxcoach.android.ui.training.common.chipCheck(profile.everydayActivity == e), onClick = { onUpdateProfile { it.copy(everydayActivity = e) } },
                            label = { Text(everydayLabel(e)) }, modifier = Modifier.testTag("everyday_${e.name.lowercase()}"))
                    }
                }
                KcalRow(stringResource(R.string.trn_training),
                    if (need.trainingKcal > 0) "+" + need.trainingKcal else stringResource(R.string.trn_no_training), "energy_training", unit = need.trainingKcal > 0)
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                KcalRow(stringResource(R.string.trn_need), need.totalKcal.toString(), "energy_need", bold = true)
                if (plan != null && plan.targetKcal != null) {
                    KcalRow(stringResource(R.string.trn_deficit_line, formatMass(plan.paceKgPerWeek, profile.units)),
                        "−" + plan.dailyDeficitKcal, "energy_deficit")
                    KcalRow(stringResource(R.string.trn_target), plan.targetKcal.toString(), "energy_target", bold = true)
                }
                eatenKcal?.let { KcalRow(stringResource(R.string.trn_eaten), it.roundToInt().toString(), "energy_eaten") }
                if (need.ageAssumed) {
                    Text(stringResource(R.string.trn_age_assumed, EnergyBalance.age(profile, java.time.LocalDate.now().year)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp))
                    val thisYear = java.time.LocalDate.now().year
                    NumberEntry(stringResource(R.string.trn_birth_year_label), null, "energy_birth_year", decimals = false,
                        toCanonical = { it }, valid = { it.toInt().toDouble() == it && it.toInt() in (thisYear - 100)..(thisYear - 8) },
                        onSave = { y -> onUpdateProfile { p -> p.copy(birthYear = y.toInt()) } })
                }
                if (need.sexAssumed) {
                    Text(stringResource(R.string.trn_sex_assumed), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(Sex.FEMALE to R.string.tra_sex_female, Sex.MALE to R.string.tra_sex_male).forEach { (s, label) ->
                            FilterChip(selected = profile.sex == s, leadingIcon = com.cruxcoach.android.ui.training.common.chipCheck(profile.sex == s), onClick = { onUpdateProfile { it.copy(sex = s) } },
                                label = { Text(stringResource(label)) }, modifier = Modifier.testTag("energy_sex_${s.name.lowercase()}"))
                        }
                    }
                }
                Text(stringResource(R.string.trn_estimate_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
            OutlinedButton(onClick = onEditGoal, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("energy_goal")) {
                Text(stringResource(if (profile.goal == AthleteGoal.LOSE_WEIGHT) R.string.trn_edit_goal else R.string.trn_set_goal))
            }
        }
    }
}

/**
 * "Abnehmen": optional target weight and the pace in 0.1 kg steps, with the
 * resulting calorie target, the weeks to the target and every warning that
 * applies – shown live while choosing, never blocking the choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeightGoalSheet(
    profile: AthleteProfile,
    weightKg: Double?,
    heightCm: Double?,
    need: EnergyBalance.Need?,
    onDismiss: () -> Unit,
    onSave: (lose: Boolean, targetKg: Double?, paceKg: Double) -> Unit,
    onLogWeight: (Double) -> Unit,
    onLogHeight: (Double) -> Unit,
) {
    val units = profile.units
    val losing = profile.goal == AthleteGoal.LOSE_WEIGHT
    var targetText by rememberSaveable {
        mutableStateOf(profile.targetWeightKg?.let { formatNumber(Units.massToDisplay(it, units)) } ?: "")
    }
    var pace by rememberSaveable { mutableStateOf(WeightPlan.clampPace(profile.weeklyLossKg)) }
    val targetKg = parseDecimal(targetText)?.let { Units.massFromDisplay(it, units) }
    val targetValid = targetText.isBlank() || (targetKg != null && targetKg in PLAUSIBLE_WEIGHT_KG)
    val plan = weightKg?.let { WeightPlan.plan(pace, targetKg?.takeIf { targetValid }, it, heightCm, profile.sex, need) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("goal_sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trn_goal_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                InfoButton(stringResource(R.string.trn_goal_title), stringResource(R.string.trn_goal_info))
            }
            if (weightKg == null) {
                Text(stringResource(R.string.trn_needs_weight), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                NumberEntry(stringResource(R.string.tru_weight_title), Units.massUnit(units), "goal_weight",
                    toCanonical = { Units.massFromDisplay(it, units) }, valid = { it in PLAUSIBLE_WEIGHT_KG }, onSave = onLogWeight)
            }
            OutlinedTextField(
                value = targetText, onValueChange = { targetText = it }, singleLine = true, isError = !targetValid,
                label = { Text(stringResource(R.string.trn_goal_target)) },
                suffix = { Text(Units.massUnit(units)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("goal_target_input"),
            )
            Text(stringResource(R.string.trn_goal_pace), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                FilledTonalIconButton(onClick = { pace = WeightPlan.clampPace(pace - WeightPlan.PACE_STEP_KG) },
                    enabled = pace > WeightPlan.MIN_PACE_KG + 1e-9, modifier = Modifier.size(48.dp).testTag("goal_pace_minus")) {
                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.trn_goal_pace_minus))
                }
                Text(stringResource(R.string.trn_goal_pace_value, formatMass(pace, units)), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(horizontal = 12.dp).testTag("goal_pace"),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                FilledTonalIconButton(onClick = { pace = WeightPlan.clampPace(pace + WeightPlan.PACE_STEP_KG) },
                    enabled = pace < WeightPlan.MAX_PACE_KG - 1e-9, modifier = Modifier.size(48.dp).testTag("goal_pace_plus")) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.trn_goal_pace_plus))
                }
            }
            plan?.let { p ->
                val share = formatNumber(p.paceShare * 100)
                Text(
                    if (profile.showCalories) stringResource(R.string.trn_goal_pace_share, share, (pace * EnergyBalance.KCAL_PER_KG / 7).roundToInt())
                    else stringResource(R.string.trn_goal_pace_share_plain, share),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CruxCoachDesign.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Column(Modifier.padding(12.dp).testTag("goal_result")) {
                        val targetKcal = p.targetKcal
                        val needKcal = p.needKcal
                        if (p.targetReached) {
                            Text(stringResource(R.string.trn_goal_reached), style = MaterialTheme.typography.bodyMedium)
                        } else if (targetKcal != null && needKcal != null && profile.showCalories) {
                            Text(stringResource(R.string.trn_goal_target_today, targetKcal), style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.testTag("goal_target_kcal"))
                            Text(stringResource(R.string.trn_goal_target_math, needKcal, p.dailyDeficitKcal), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        p.weeksToTarget?.takeIf { it > 0 }?.let {
                            Text(stringResource(R.string.trn_goal_weeks, it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        }
                        if (heightCm == null) {
                            Text(stringResource(R.string.trn_needs_height), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                            NumberEntry(stringResource(R.string.trn_height_label, Units.lengthUnit(units)), Units.lengthUnit(units), "goal_height",
                                toCanonical = { Units.lengthFromDisplay(it, units) }, valid = { it in PLAUSIBLE_HEIGHT_CM }, onSave = onLogHeight)
                        }
                    }
                }
                // The same reasons as on the energy card, live while choosing.
                val preview = EnergyReport(signals = p.warnings, plan = p)
                p.warnings.forEach { w ->
                    Row(Modifier.padding(top = 8.dp).testTag("goal_warning_${w.name.lowercase()}")) {
                        Icon(Icons.Default.Warning, null, tint = CruxCoachDesign.colors.caution, modifier = Modifier.size(18.dp).padding(top = 2.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(energyWarningText(w, preview, profile), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Button(onClick = { onSave(true, targetKg?.takeIf { targetValid }, pace) }, enabled = targetValid && weightKg != null,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).heightIn(min = 52.dp).testTag("goal_save")) {
                Text(stringResource(R.string.trn_goal_save))
            }
            if (losing) {
                TextButton(onClick = { onSave(false, profile.targetWeightKg, profile.weeklyLossKg) },
                    modifier = Modifier.fillMaxWidth().testTag("goal_stop")) {
                    Text(stringResource(R.string.trn_goal_stop))
                }
            }
        }
    }
}

@Composable
fun everydayLabel(e: EverydayActivity): String = stringResource(when (e) {
    EverydayActivity.SEATED -> R.string.trn_everyday_seated
    EverydayActivity.ON_FEET -> R.string.trn_everyday_on_feet
    EverydayActivity.PHYSICAL -> R.string.trn_everyday_physical
})

@Composable
private fun KcalRow(label: String, value: String, tag: String, bold: Boolean = false, unit: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(tag)) {
        Text(label, style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(if (unit) stringResource(R.string.trn_kcal_signed, value) else value,
            style = if (bold) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.SemiBold else null)
    }
}

/** One missing value entered in place: field, unit, "Speichern". */
@Composable
internal fun NumberEntry(
    label: String,
    suffix: String?,
    tag: String,
    decimals: Boolean = true,
    toCanonical: (Double) -> Double,
    valid: (Double) -> Boolean,
    onSave: (Double) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val value = parseDecimal(text)?.let(toCanonical)
    val ok = value != null && valid(value)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text(label) },
            suffix = suffix?.let { s -> { Text(s) } },
            keyboardOptions = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number),
            modifier = Modifier.weight(1f).testTag("${tag}_input"))
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = { value?.let(onSave); text = "" }, enabled = ok, modifier = Modifier.testTag("${tag}_save")) {
            Text(stringResource(R.string.tr_action_save))
        }
    }
}

/** Short summary of the goal for settings rows: "0,5 kg pro Woche · heute etwa 1950 kcal · Ziel 65 kg". */
@Composable
fun weightGoalSummary(profile: AthleteProfile, plan: WeightPlan.Plan?): String {
    val pace = formatMass(WeightPlan.clampPace(profile.weeklyLossKg), profile.units)
    val target = plan?.targetKcal
    val head = if (profile.showCalories && target != null) stringResource(R.string.trn_goal_summary, pace, target)
        else stringResource(R.string.trn_goal_summary_plain, pace)
    val goal = profile.targetWeightKg?.takeIf { !profile.hideBodyNumbers }?.let { stringResource(R.string.trn_goal_summary_target, formatMass(it, profile.units)) }
    return listOfNotNull(head, goal).joinToString(" · ")
}

/** The energy target of a day: the need, minus the plan's deficit while losing weight. */
fun energyTarget(need: EnergyBalance.Need?, plan: WeightPlan.Plan?): Int? = need?.let { it.totalKcal - (plan?.dailyDeficitKcal ?: 0) }
