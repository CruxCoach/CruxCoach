package com.cruxcoach.android.ui.training.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.athlete.PainAction
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.bodymap.BodyArea
import com.cruxcoach.android.ui.training.bodymap.BodyMapPicker
import com.cruxcoach.android.ui.training.bodymap.toInjuryRegion
import com.cruxcoach.android.ui.training.injurySideLabel
import com.cruxcoach.android.ui.training.regionLabel
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.Side
import kotlin.math.roundToInt

/**
 * "Tut weh" during a set (MCI's pain sheet, climber version): where, which
 * side, how much, then swap the exercise, go lighter or end it — optionally
 * remembered as an injury. A filter, not a diagnosis: strong pain gets the
 * advice to stop and have it checked.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PainSheet(
    exerciseName: String,
    setSide: Side?,
    onDismiss: () -> Unit,
    onConfirm: (region: InjuryRegion, side: InjurySide?, severity: Int, action: PainAction, remember: Boolean) -> Unit,
) {
    var area by rememberSaveable { mutableStateOf<BodyArea?>(null) }
    var region by rememberSaveable { mutableStateOf<InjuryRegion?>(null) }
    var side by rememberSaveable {
        mutableStateOf(when (setSide) { Side.LEFT -> InjurySide.LEFT; Side.RIGHT -> InjurySide.RIGHT; null -> null })
    }
    var pain by rememberSaveable { mutableFloatStateOf(4f) }
    var action by rememberSaveable { mutableStateOf(PainAction.SWAP) }
    var rememberIt by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val severity = pain.roundToInt()
    val remember = rememberIt ?: (severity >= 5)

    // Fully open: the body map is the main control and must not sit under the navigation bar.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("player_pain_sheet")) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Healing, null, tint = CruxCoachDesign.colors.caution)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.trp2_pain_title), style = MaterialTheme.typography.titleLarge)
            }
            Text(exerciseName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.trp2_pain_region), style = MaterialTheme.typography.titleSmall)
            BodyMapPicker(
                selected = area,
                onSelect = { a -> area = a; a.toInjuryRegion()?.let { region = it } },
                onSide = { s -> s?.let { side = if (it == Side.LEFT) InjurySide.LEFT else InjurySide.RIGHT } },
                // No height cap: the map and its hint line must both fit.
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InjuryRegion.entries.forEach { r ->
                    FilterChip(selected = region == r, onClick = { region = r }, label = { Text(regionLabel(r)) },
                        modifier = Modifier.testTag("player_pain_region_${r.name.lowercase()}"))
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.trp2_pain_side), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InjurySide.entries.forEach { s ->
                    FilterChip(selected = side == s, onClick = { side = if (side == s) null else s }, label = { Text(injurySideLabel(s)) },
                        modifier = Modifier.testTag("player_pain_side_${s.name.lowercase()}"))
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.trp2_pain_level, severity), style = MaterialTheme.typography.titleSmall)
            Slider(value = pain, onValueChange = { pain = it }, valueRange = 0f..9f, steps = 8,
                modifier = Modifier.testTag("player_pain_level"))

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.trp2_pain_action), style = MaterialTheme.typography.titleSmall)
            PainAction.entries.forEach { a ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(role = Role.RadioButton) { action = a }.testTag("player_pain_action_${a.name.lowercase()}"),
                ) {
                    RadioButton(selected = action == a, onClick = { action = a })
                    Text(stringResource(when (a) {
                        PainAction.SWAP -> R.string.trp2_action_swap
                        PainAction.LIGHTER -> R.string.trp2_action_lighter
                        PainAction.END_EXERCISE -> R.string.trp2_action_end
                    }))
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { rememberIt = !remember }.testTag("player_pain_remember"),
            ) {
                Checkbox(checked = remember, onCheckedChange = { rememberIt = it })
                Text(stringResource(R.string.trp2_pain_remember), style = MaterialTheme.typography.bodyMedium)
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = CruxCoachDesign.colors.cautionContainer,
                    contentColor = CruxCoachDesign.colors.onCautionContainer),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.trp2_pain_medical), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }

            Button(
                onClick = { region?.let { onConfirm(it, side, severity, action, remember) } },
                enabled = region != null,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp).testTag("player_pain_confirm"),
            ) { Text(stringResource(R.string.trp2_pain_confirm)) }
        }
    }
}
