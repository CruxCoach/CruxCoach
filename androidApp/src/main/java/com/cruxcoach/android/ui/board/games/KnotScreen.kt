package com.cruxcoach.android.ui.board.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Switch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.rgb332ToComposeColor
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.games.Knot
import com.cruxcoach.domain.board.games.KnotColour
import java.util.Locale

/** A climber's name as the screen shows it: as typed, or "Player n". */
@Composable
private fun shownName(knot: KnotState, climber: Int): String = climberName(LocalContext.current, knot.names, climber)

/** Sentence case for a limb or colour shown on its own ("Right hand", "Blue"). */
private fun String.capitalised(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }

// ── Settings ───────────────────────────────────────────────────────────

/** Knot before a round: safety first, then who plays, how fast and how the calls come. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KnotSettings(state: WallGamesState, actions: WallGamesActions) {
    val knot = state.knot
    if (!knot.safetySeen) KnotSafetyCard(onGotIt = actions::knotSafetySeen)
    // Without named colours, or without room for a field, there is nothing to set.
    val unavailable = when {
        knot.colours == null -> if (state.board?.brand == BoardBrand.SHINYWALL) R.string.knot_unsupported_shinywall else R.string.knot_unsupported
        knot.layout == null -> R.string.knot_no_field
        else -> null
    }
    if (unavailable != null) {
        Text(
            stringResource(unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("knot_unavailable"),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.knot_climbers), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Knot.CLIMBERS.forEach { count ->
                FilterChip(
                    selected = count == knot.climbers,
                    onClick = { actions.setKnotClimbers(count) },
                    enabled = count <= knot.maxClimbers,
                    label = { Text(count.toString()) },
                    colors = chipColors(),
                    modifier = Modifier.testTag("knot_climbers_$count"),
                )
            }
        }
        if (knot.maxClimbers < Knot.MAX_CLIMBERS) {
            Text(
                pluralStringResource(R.plurals.knot_board_fits, knot.maxClimbers, knot.maxClimbers),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.knot_rounds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("knot_rounds"),
        )
    }
    // Solo needs no name: the calls go to the one climber.
    if (knot.climbers > 1) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (0 until knot.climbers).forEach { climber ->
                OutlinedTextField(
                    value = knot.names[climber],
                    onValueChange = { actions.setKnotName(climber, it) },
                    label = { Text(stringResource(R.string.knot_player, climber + 1)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = if (climber < knot.climbers - 1) ImeAction.Next else ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("knot_name_$climber"),
                )
            }
        }
    }
    KnotByTap(knot, actions)
    // The level is the time per call: by tap there is none.
    if (knot.byTap) return
    LevelPicker(
        level = state.level,
        interval = stringResource(R.string.knot_call_time, WallGameFormat.seconds(Knot.callMs(state.level))),
        onLevel = actions::setLevel,
    )
    // A warning with its number and the way out at hand: the easiest level.
    val steep = knot.steepAngle(state.board)
    if (steep != null && state.level > 1) {
        val text: @Composable (Modifier) -> Unit = { modifier ->
            Text(stringResource(R.string.knot_steep, steep), style = MaterialTheme.typography.bodyMedium, modifier = modifier)
        }
        val action: @Composable () -> Unit = {
            TextButton(onClick = { actions.setLevel(1) }, modifier = Modifier.heightIn(min = 48.dp).testTag("knot_level_1")) {
                Text(stringResource(R.string.wall_games_level_1))
            }
        }
        if (compactLayout()) {
            Column(Modifier.fillMaxWidth().testTag("knot_steep")) {
                text(Modifier)
                action()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("knot_steep")) {
                text(Modifier.weight(1f))
                action()
            }
        }
    }
}

@Composable
private fun KnotSafetyCard(onGotIt: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("knot_safety")) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                stringResource(R.string.knot_safety_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.knot_safety_text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp, end = 8.dp),
            )
            TextButton(
                onClick = onGotIt,
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("knot_safety_ok"),
            ) { Text(stringResource(R.string.action_understood)) }
        }
    }
}

/** Without a timer, someone below the wall calls the next one when everyone is ready. */
@Composable
private fun KnotByTap(knot: KnotState, actions: WallGamesActions) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = knot.byTap, role = Role.Switch, onValueChange = actions::setKnotByTap)
            .testTag("knot_by_tap"),
    ) {
        Text(stringResource(R.string.knot_by_tap), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = knot.byTap, onCheckedChange = null)
    }
}

// ── A round ────────────────────────────────────────────────────────────

/** Knot from the start positions to the result: what to do now, who is still in, and the field. */
@Composable
internal fun KnotRunContent(state: WallGamesState, actions: WallGamesActions, modifier: Modifier = Modifier) {
    val run = state.knot.run
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("knot_run"),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (run.phase) {
                    KnotPhase.POSITIONS -> KnotPositions(state.knot)
                    KnotPhase.COUNTDOWN -> BigText(
                        run.countdown.toString(),
                        maxSize = 112.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("knot_countdown"),
                    )
                    KnotPhase.RUNNING -> KnotCallPanel(state.knot)
                    KnotPhase.FINISHED -> KnotResult(state.knot)
                    KnotPhase.IDLE -> Unit
                }
            }
        }
        if (run.phase == KnotPhase.COUNTDOWN || run.phase == KnotPhase.RUNNING ||
            (run.phase == KnotPhase.FINISHED && run.climbers.size > 1)
        ) {
            KnotClimbers(state.knot, actions)
        }
        GameBoardPreview(state)
    }
}

@Composable
private fun KnotPositions(knot: KnotState) {
    Text(
        stringResource(R.string.knot_positions),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
    )
    val field = knot.run.field
    if (knot.run.climbers.size <= 1 || field == null) {
        Text(stringResource(R.string.knot_positions_solo), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        return
    }
    Text(stringResource(R.string.knot_positions_solo), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    val parts = if (knot.run.climbers.size == 2) listOf(R.string.knot_part_left, R.string.knot_part_right)
    else listOf(R.string.knot_part_left, R.string.knot_part_middle, R.string.knot_part_right)
    knot.run.climbers.indices.forEach { climber ->
        Text(
            stringResource(R.string.knot_part, shownName(knot, climber), stringResource(parts[climber])),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("knot_part_$climber"),
        )
    }
}

/** The call in large type, readable from the wall: who, which hand or foot, which colour, and the time left. */
@Composable
private fun KnotCallPanel(knot: KnotState) {
    val run = knot.run
    val call = run.call ?: return
    val solo = run.climbers.size <= 1
    Text(
        if (solo) stringResource(R.string.knot_call_number, call.number)
        else stringResource(R.string.knot_call_who, call.number, shownName(knot, call.climber)),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("knot_call_who"),
    )
    BigText(stringResource(call.limb.labelRes).capitalised(), maxSize = 72.dp, modifier = Modifier.testTag("knot_call_limb"))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ColourDot(call.colour, 40)
        Box(Modifier.weight(1f, fill = false)) {
            BigText(stringResource(call.colour.labelRes).capitalised(), maxSize = 72.dp, modifier = Modifier.testTag("knot_call_colour"))
        }
    }
    if (!run.byTap) {
        LinearProgressIndicator(
            progress = { run.callLeftMs.toFloat() / run.callMs },
            color = OrangeAccent,
            modifier = Modifier.fillMaxWidth().testTag("knot_call_time"),
        )
    }
}

/** The colour as the wall shows it; white gets a rim so it shows on a light screen. */
@Composable
private fun ColourDot(colour: KnotColour, sizeDp: Int) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .background(rgb332ToComposeColor(colour.rgb332), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

/** Who is still in: whoever comes off taps their own name. Out climbers keep their count. */
@Composable
private fun KnotClimbers(knot: KnotState, actions: WallGamesActions) {
    val run = knot.run
    val playing = run.phase == KnotPhase.COUNTDOWN || run.phase == KnotPhase.RUNNING
    Column(Modifier.fillMaxWidth().testTag("knot_climbers"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (playing) {
            Text(
                stringResource(R.string.knot_tap_name),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        run.climbers.forEachIndexed { index, climber ->
            val name = shownName(knot, index)
            val held = pluralStringResource(R.plurals.knot_held, climber.held, climber.held) +
                if (climber.out) " · " + stringResource(R.string.knot_is_out) else ""
            val label: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (run.winner == index) FontWeight.Bold else FontWeight.Normal,
                        textDecoration = if (climber.out) TextDecoration.LineThrough else null,
                    )
                    Text(held, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (playing && !climber.out) {
                OutlinedButton(
                    onClick = { actions.knotOut(index) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("knot_out_$index"),
                ) { label() }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                        .testTag("knot_climber_$index"),
                ) {
                    val colour = if (climber.out) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    CompositionLocalProvider(LocalContentColor provides colour) { label() }
                }
            }
        }
    }
}

@Composable
private fun KnotResult(knot: KnotState) {
    val run = knot.run
    val solo = run.climbers.size <= 1
    val winner = run.winner
    when {
        solo -> {
            val held = run.climbers.firstOrNull()?.held ?: 0
            BigText(held.toString(), maxSize = 96.dp, modifier = Modifier.testTag("knot_result"))
            Text(
                pluralStringResource(R.plurals.knot_held_label, held),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        winner != null -> BigText(
            stringResource(R.string.knot_wins, climberName(LocalContext.current, knot.names, winner, numbered = false)),
            maxSize = 64.dp,
            modifier = Modifier.testTag("knot_result"),
        )
        else -> BigText(stringResource(R.string.knot_stopped), maxSize = 64.dp, modifier = Modifier.testTag("knot_result"))
    }
}

// ── Bottom bar ─────────────────────────────────────────────────────────

/** Knot's actions in the bottom bar, in [slot]s side by side or above each other. */
@Composable
internal fun KnotButtons(state: WallGamesState, actions: WallGamesActions, slot: Modifier) {
    when (state.knot.run.phase) {
        KnotPhase.IDLE -> Button(
            onClick = actions::start,
            enabled = state.canStart,
            modifier = slot.heightIn(min = 48.dp).testTag("wall_games_start"),
        ) { ButtonLabel(R.string.knot_light) }
        KnotPhase.POSITIONS -> {
            OutlinedButton(onClick = actions::stop, modifier = slot.heightIn(min = 48.dp).testTag("knot_cancel")) {
                ButtonLabel(R.string.action_cancel)
            }
            Button(onClick = actions::startKnotCalls, modifier = slot.heightIn(min = 48.dp).testTag("knot_go")) {
                ButtonLabel(R.string.wall_games_start)
            }
        }
        // Without a timer, the next call is the main action and stopping the side one.
        KnotPhase.COUNTDOWN, KnotPhase.RUNNING -> if (state.knot.run.byTap) {
            OutlinedButton(onClick = actions::stop, modifier = slot.heightIn(min = 48.dp).testTag("wall_games_stop")) {
                ButtonLabel(R.string.wall_games_stop)
            }
            Button(
                onClick = actions::knotNextCall,
                enabled = state.knot.run.phase == KnotPhase.RUNNING,
                modifier = slot.heightIn(min = 48.dp).testTag("knot_next"),
            ) { ButtonLabel(R.string.knot_next_call) }
        } else {
            Button(onClick = actions::stop, modifier = slot.heightIn(min = 48.dp).testTag("wall_games_stop")) {
                ButtonLabel(R.string.wall_games_stop)
            }
        }
        KnotPhase.FINISHED -> {
            OutlinedButton(onClick = actions::reset, modifier = slot.heightIn(min = 48.dp).testTag("wall_games_done")) {
                ButtonLabel(R.string.action_done)
            }
            Button(
                onClick = actions::start,
                enabled = state.canStart,
                modifier = slot.heightIn(min = 48.dp).testTag("wall_games_again"),
            ) { ButtonLabel(R.string.wall_games_play_again) }
        }
    }
}
