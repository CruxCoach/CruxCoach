package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.*

/**
 * Action bar shown while nothing is running: one button for chance.
 * Hidden once a playlist is running (the [BleStatusArea] handles that).
 *
 * In any sorted list it is "Random" (the dice): it opens one climb picked by chance. In a list that is itself sorted
 * at random ([shuffleList]) the top of the list already is that pick, so the button deals the whole list again
 * instead: "Shuffle again". A climber asked for a way to get a new random order (developer chat, 2026-11-25);
 * the button for chance was already here.
 */
@Composable
internal fun SessionTimerBar(
    shuffleList: Boolean = false,
    onRandomClimb: () -> Unit = {},
    onReshuffle: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            onClick = if (shuffleList) onReshuffle else onRandomClimb,
            color = OrangeAccent.copy(alpha = 0.06f),
            modifier = Modifier.weight(1f).testTag(if (shuffleList) "board_reshuffle" else "board_random_climb")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    // The dice is one climb by chance, as on "add a random climb"; the crossed arrows are a new order.
                    if (shuffleList) Icons.Default.Shuffle else Icons.Default.Casino,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = OrangeAccent
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    stringResource(if (shuffleList) R.string.board_reshuffle else R.string.board_random_climb),
                    color = OrangeAccent,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SessionSummaryRow(label: String, value: String) {
    // The value moves below its label when both do not fit on one line.
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium,
             fontWeight = FontWeight.Bold)
    }
}

internal fun formatSessionTime(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
