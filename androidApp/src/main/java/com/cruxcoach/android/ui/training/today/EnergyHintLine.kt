package com.cruxcoach.android.ui.training.today

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.training.formatNumber
import com.cruxcoach.athlete.logic.EnergyBeforeTraining
import com.cruxcoach.athlete.model.AthleteProfile
import kotlin.math.roundToInt

/**
 * Little energy lately before maximal finger work (holistic audit P2-17): one supportive line with the numbers,
 * and "lighter" as one tap while today is not already lighter. Never automatic, never locked.
 */
@Composable
internal fun EnergyHintLine(hint: EnergyBeforeTraining.Hint, profile: AthleteProfile, lighterChosen: Boolean, onLighter: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("today_energy_hint"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Restaurant, null, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(energyHintText(hint, profile), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (!lighterChosen) {
            TextButton(onClick = onLighter, modifier = Modifier.testTag("today_energy_hint_lighter")) {
                Text(stringResource(R.string.trt_level_easier))
            }
        }
    }
}

/**
 * The line's text: calories only while they are shown, no numbers at all in "hide numbers" mode. It names what
 * was logged and what helps – never a verdict on the athlete.
 */
@Composable
internal fun energyHintText(hint: EnergyBeforeTraining.Hint, profile: AthleteProfile): String {
    val r = hint.report
    val hide = profile.hideBodyNumbers
    return when (hint.basis) {
        EnergyBeforeTraining.Basis.DEFICIT -> {
            val intake = r.avgIntakeKcal
            val need = r.avgNeedKcal
            val share = r.deficitShare
            when {
                hide -> stringResource(R.string.trt_energy_hint_deficit_plain)
                profile.showCalories && intake != null && need != null ->
                    stringResource(R.string.trt_energy_hint_deficit, r.comparedDays, intake, need)
                share != null -> stringResource(R.string.trt_energy_hint_deficit_share, r.comparedDays, (share * 100).roundToInt())
                else -> stringResource(R.string.trt_energy_hint_deficit_plain)
            }
        }
        EnergyBeforeTraining.Basis.LOW_CARBS -> {
            val perKg = r.lowCarbPerKg
            val target = r.lowCarbTargetPerKg
            if (hide || perKg == null || target == null) stringResource(R.string.trt_energy_hint_carbs_plain)
            else stringResource(R.string.trt_energy_hint_carbs, r.lowCarbDays, formatNumber(perKg),
                if (target.start == target.endInclusive) formatNumber(target.start)
                else formatNumber(target.start) + "–" + formatNumber(target.endInclusive))
        }
    }
}
