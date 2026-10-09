package com.cruxcoach.android.ui.training

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.athlete.logic.EnergyReport
import com.cruxcoach.athlete.logic.RedsSignal
import com.cruxcoach.athlete.logic.WeightPlan
import com.cruxcoach.athlete.model.AthleteProfile
import kotlin.math.roundToInt

/** Signals that come from the weight-loss plan; the card offers to adjust it. */
val PLAN_SIGNALS = setOf(RedsSignal.FAST_PACE, RedsSignal.LARGE_DEFICIT, RedsSignal.LOW_TARGET_BMI)

/**
 * The energy card (RED-S guard, FEAT-067/068): the same title, reasons and
 * explanation wherever it appears – Today, Nutrition, Body and the weekly
 * review. Each reason carries its numbers; nothing is locked. Where the app
 * can open the weight-loss goal, a plan warning comes with "adjust".
 */
@Composable
fun EnergyCareCard(
    report: EnergyReport,
    profile: AthleteProfile,
    tag: String,
    modifier: Modifier = Modifier,
    onAdjustGoal: (() -> Unit)? = null,
) {
    if (report.signals.isEmpty()) return
    val adjust = onAdjustGoal?.takeIf { report.signals.any { it in PLAN_SIGNALS } }
    Card(
        colors = CardDefaults.cardColors(containerColor = CruxCoachDesign.colors.cautionContainer,
            contentColor = CruxCoachDesign.colors.onCautionContainer),
        modifier = modifier.fillMaxWidth().testTag(tag),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = if (adjust != null) 4.dp else 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Favorite, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.trt_reds_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                InfoButton(stringResource(R.string.trt_reds_title), stringResource(R.string.trt_reds_info))
            }
            report.signals.forEach { s ->
                Text("• " + energyWarningText(s, report, profile), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 8.dp, top = 2.dp).testTag("${tag}_${s.name.lowercase()}"))
            }
            if (adjust != null) {
                TextButton(onClick = adjust, modifier = Modifier.align(Alignment.End).testTag("${tag}_adjust")) {
                    Text(stringResource(R.string.trn_adjust_goal))
                }
            }
        }
    }
}

/**
 * One reason with its numbers. Body numbers stay hidden in "hide numbers"
 * mode and calories when they are switched off; the reason itself is always named.
 */
@Composable
fun energyWarningText(signal: RedsSignal, report: EnergyReport, profile: AthleteProfile): String {
    val hide = profile.hideBodyNumbers
    val plan = report.plan
    return when (signal) {
        RedsSignal.LOW_BMI -> {
            val bmi = report.bmi
            if (hide || bmi == null) stringResource(R.string.trt_reds_low_bmi)
            else stringResource(R.string.trn_w_low_bmi, formatNumber(bmi), formatNumber(report.bmiThreshold))
        }
        RedsSignal.RAPID_LOSS -> {
            val share = report.weeklyRateShare
            val rate = report.weeklyRateKg
            val month = report.fourWeekShare
            when {
                hide -> stringResource(R.string.trt_reds_rapid_loss)
                share != null && rate != null && share < -0.01 ->
                    stringResource(R.string.trn_w_rapid_week, formatMass(-rate, profile.units), formatNumber(-share * 100))
                month != null -> stringResource(R.string.trn_w_rapid_month, formatNumber(-month * 100))
                else -> stringResource(R.string.trt_reds_rapid_loss)
            }
        }
        RedsSignal.HIGH_DEFICIT -> {
            val share = ((report.deficitShare ?: 0.0) * 100).roundToInt()
            val intake = report.avgIntakeKcal
            val need = report.avgNeedKcal
            if (profile.showCalories && intake != null && need != null)
                stringResource(R.string.trn_w_high_deficit, report.comparedDays, intake, share, need)
            else stringResource(R.string.trn_w_high_deficit_plain, report.comparedDays, share)
        }
        RedsSignal.LOW_CARBS_ON_TRAINING_DAYS -> stringResource(R.string.trt_reds_low_carbs)
        RedsSignal.FAST_PACE ->
            if (hide || plan == null) stringResource(R.string.trn_w_fast_pace_plain)
            else stringResource(R.string.trn_w_fast_pace, formatMass(plan.paceKgPerWeek, profile.units), formatNumber(plan.paceShare * 100))
        RedsSignal.LARGE_DEFICIT -> {
            val target = plan?.targetKcal
            val resting = plan?.restingKcal
            if (plan != null && plan.belowResting && profile.showCalories && target != null && resting != null)
                stringResource(R.string.trn_w_below_resting, target, resting)
            else stringResource(R.string.trn_w_large_deficit, ((plan?.deficitShare ?: WeightPlan.LARGE_DEFICIT_SHARE) * 100).roundToInt())
        }
        RedsSignal.LOW_TARGET_BMI -> {
            val bmi = plan?.targetBmi
            if (hide || bmi == null) stringResource(R.string.trn_w_low_target_bmi_plain)
            else stringResource(R.string.trn_w_low_target_bmi, formatNumber(bmi), formatNumber(plan.bmiThreshold))
        }
    }
}
