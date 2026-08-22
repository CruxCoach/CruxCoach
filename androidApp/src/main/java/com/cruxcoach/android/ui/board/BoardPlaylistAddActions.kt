package com.cruxcoach.android.ui.board

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.OrangeAccent

/**
 * Putting a climb on the group's list, where the climb is.
 *
 * A plain tap means one thing and only one thing: the end of the list. That is
 * the answer nine times out of ten, and it is the one that cannot go wrong —
 * nothing anybody is climbing moves, nothing jumps the queue. The second
 * option is real and worth having, so it lives one deliberate tap away behind
 * the arrow rather than sharing the button with the first.
 *
 * Two equally-weighted buttons were the previous shape and the problem with
 * them was not space: it was that both mutated a shared order and neither said
 * which. A split button makes the common case unambiguous and keeps the
 * uncommon one findable.
 *
 * Long-press opens the same menu with haptics — an accelerant for people who
 * know it is there, never the only way in, and never a shortcut that mutates
 * the shared order without showing what it is about to do.
 *
 * Neither option touches the wall. The line underneath is live canonical state
 * rather than a toast about the tap that just happened, so it is still true a
 * moment later and answers the question somebody arriving already had.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val addToEnd = stringResource(R.string.board_playlist_add_to_end)
    val moreOptions = stringResource(R.string.board_playlist_add_more_options)

    if (queued > 0) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            modifier = modifier.fillMaxWidth().testTag("boarddetail_already_queued"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer6()
                Text(
                    pluralStringResource(R.plurals.board_playlist_already_queued, queued, queued),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Column(modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = OrangeAccent,
            contentColor = DarkBackground,
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .combinedClickable(
                            enabled = enabled,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = androidx.compose.material3.ripple(),
                            onClick = { viewModel.append(climbUuid, angle) },
                            onLongClick = {
                                // The accelerant, not the only route: the arrow
                                // beside it opens the identical menu.
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                menuOpen = true
                            },
                        )
                        .semantics {
                            role = Role.Button
                            contentDescription = addToEnd
                        }
                        .testTag("boarddetail_add_to_board_playlist"),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                        Spacer6()
                        Text(addToEnd, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge)
                    }
                }
                // A real divider, so the two halves read as two targets rather
                // than one button with a decoration on the end.
                Box(
                    Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .align(Alignment.CenterVertically)
                        .let { it },
                ) {
                    Surface(color = DarkBackground.copy(alpha = 0.28f)) {
                        Box(Modifier.width(1.dp).height(28.dp))
                    }
                }
                Box {
                    Box(
                        modifier = Modifier
                            .width(46.dp)
                            .height(44.dp)
                            .combinedClickable(
                                enabled = enabled,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = androidx.compose.material3.ripple(),
                                onClick = { menuOpen = true },
                            )
                            .semantics {
                                role = Role.Button
                                contentDescription = moreOptions
                            }
                            .testTag("boarddetail_add_options"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(addToEnd) },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                viewModel.append(climbUuid, angle)
                            },
                            modifier = Modifier.testTag("boarddetail_add_menu_end"),
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.board_playlist_add_next)) },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                viewModel.appendAsNext(climbUuid, angle)
                            },
                            modifier = Modifier.testTag("boarddetail_add_menu_next"),
                        )
                    }
                }
            }
        }
        // Shown once, then never again: a hint that keeps reappearing is an
        // instruction nobody read the first time and everybody resents by the
        // fifth.
        val hintSeen by viewModel.addOptionsHintSeen.collectAsStateWithLifecycle()
        if (!hintSeen) {
            LaunchedEffect(Unit) { viewModel.markAddOptionsHintSeen() }
            Text(
                stringResource(R.string.board_playlist_add_options_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp).testTag("boarddetail_add_options_hint"),
            )
        }
    }
}

@Composable
private fun Spacer6() {
    Box(Modifier.width(6.dp))
}
