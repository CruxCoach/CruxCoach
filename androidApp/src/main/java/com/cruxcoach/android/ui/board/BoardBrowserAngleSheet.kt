package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.BoardBrand
import kotlin.math.roundToInt

internal fun browserAngleOptions(filter: BrowserFilterState): List<Int> =
    filter.angleChips.distinct().sorted().ifEmpty {
        if (BoardBrand.fromWire(filter.boardBrand) == BoardBrand.KILTER) (0..70 step 5).toList()
        else emptyList()
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun BoardBrowserAngleSheet(filter: BrowserFilterState, onAngle: (Int) -> Unit, onDismiss: () -> Unit) {
    val angles = browserAngleOptions(filter)
    var draftAngle by remember(filter.angle, filter.boardBrand, filter.layoutId) { mutableIntStateOf(filter.angle) }
    val finish = { if (draftAngle != filter.angle && draftAngle in angles) onAngle(draftAngle); onDismiss() }
    ModalBottomSheet(onDismissRequest = finish) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.board_filter_angle, draftAngle), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.board_angle_physical_hint), style = MaterialTheme.typography.bodyMedium)
            when {
                angles.isEmpty() -> Text(stringResource(R.string.board_angle_unavailable))
                angles.size <= 4 -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    angles.forEach { angle ->
                        FilterChip(selected = angle == draftAngle, onClick = { draftAngle = angle; onAngle(angle) },
                            label = { Text("$angle°") }, modifier = Modifier.heightIn(min = 48.dp).testTag("board_angle_$angle"))
                    }
                }
                else -> {
                    val index = BoardAnglePicker.sliderIndex(angles, draftAngle)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { draftAngle = angles[index - 1]; onAngle(draftAngle) }, enabled = index > 0,
                            modifier = Modifier.testTag("board_angle_less")) { Text("−") }
                        Slider(value = index.toFloat(), onValueChange = { draftAngle = angles[it.roundToInt()] },
                            onValueChangeFinished = { onAngle(draftAngle) },
                            valueRange = 0f..angles.lastIndex.toFloat(), steps = angles.size - 2,
                            modifier = Modifier.weight(1f).testTag("board_angle_slider"))
                        TextButton(onClick = { draftAngle = angles[index + 1]; onAngle(draftAngle) }, enabled = index < angles.lastIndex,
                            modifier = Modifier.testTag("board_angle_more")) { Text("+") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${angles.first()}°"); Text("${angles.last()}°")
                    }
                }
            }
            Button(onClick = finish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_done)) }
        }
    }
}
