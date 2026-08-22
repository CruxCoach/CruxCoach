package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.OrangeAccent

/**
 * "Add" and "Add as next", where the climb actually is.
 *
 * The two questions somebody has in front of an open climb are "put this on
 * the list" and "put this on the list *now*", and until now the second had no
 * answer at all — you added to the end and then went and dragged it up. Both
 * are prominent rather than hidden behind an overflow, because on a board that
 * a group is sharing this is the main thing a climb page is for.
 *
 * Neither of them touches the wall. Adding a climb to the group's list is not
 * a claim on the board somebody else may be climbing on; the lamp on the list
 * and in the player is the only thing that projects.
 *
 * The line underneath says how many times the climb is on the list right now.
 * That is live canonical state rather than a toast about the tap that just
 * happened, so it is still true a moment later and it also answers the
 * question somebody arriving at the page already had.
 */
@Composable
fun BoardPlaylistAddActions(
    climbUuid: String,
    angle: Int,
    modifier: Modifier = Modifier,
    /**
     * False while the page is still resolving which climb it is showing.
     * Disabled rather than hidden: a swipe to an uncached climb keeps the
     * previous climb in state for a beat, and a row that vanishes and comes
     * back is both a layout jump and a tap that lands on the wrong climb.
     */
    enabled: Boolean = true,
    viewModel: BoardPlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.available) return

    val queued = state.rows.count {
        it.climbUuid.equals(climbUuid, ignoreCase = true) && it.angle == angle
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = { viewModel.append(climbUuid, angle) },
                enabled = enabled,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                modifier = Modifier.weight(1f).testTag("boarddetail_add_to_board_playlist"),
            ) {
                Icon(Icons.Default.Add, contentDescription = null,
                    tint = DarkBackground, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.board_playlist_add), color = DarkBackground,
                    fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = { viewModel.appendAsNext(climbUuid, angle) },
                enabled = enabled,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).testTag("boarddetail_add_next_board_playlist"),
            ) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.board_playlist_add_next))
            }
        }
        if (queued > 0) {
            Text(
                pluralStringResource(R.plurals.board_playlist_already_queued, queued, queued),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
