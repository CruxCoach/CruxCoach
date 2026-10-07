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
import com.cruxcoach.athlete.logic.RedsSignal

/**
 * The energy-availability card (RED-S guard, FEAT-067/068): the same title,
 * reasons and explanation wherever it appears – Today, Nutrition, Body and
 * the weekly review. It names what CruxCoach noticed and never diagnoses.
 */
@Composable
fun EnergyCareCard(signals: List<RedsSignal>, tag: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CruxCoachDesign.colors.cautionContainer,
            contentColor = CruxCoachDesign.colors.onCautionContainer),
        modifier = modifier.fillMaxWidth().testTag(tag),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Favorite, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.trt_reds_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                InfoButton(stringResource(R.string.trt_reds_title), stringResource(R.string.trt_reds_info))
            }
            signals.filter { it != RedsSignal.LOSS_GOAL_PAUSED }.forEach { s ->
                Text("• " + stringResource(when (s) {
                    RedsSignal.LOW_BMI -> R.string.trt_reds_low_bmi
                    RedsSignal.RAPID_LOSS -> R.string.trt_reds_rapid_loss
                    RedsSignal.LOW_CARBS_ON_TRAINING_DAYS -> R.string.trt_reds_low_carbs
                    RedsSignal.LOSS_GOAL_PAUSED -> R.string.trt_reds_goal_paused
                }), style = MaterialTheme.typography.bodyMedium)
            }
            if (RedsSignal.LOSS_GOAL_PAUSED in signals) {
                Text(stringResource(R.string.trt_reds_goal_paused), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
