package com.cruxcoach.android.ui.board.games

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.rgb332ToComposeColor
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.games.Knot
import com.cruxcoach.domain.board.games.KnotColour
import com.cruxcoach.domain.board.games.KnotSpacing
import kotlin.math.roundToInt
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
        // More players than fit: only once the wall is full.
        if (knot.climbers == knot.maxClimbers) {
            Text(
                stringResource(R.string.knot_rounds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("knot_rounds"),
            )
        }
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
    KnotSpacingPicker(knot, actions)
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
            TextButton(
                onClick = { actions.setLevel(1) },
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.heightIn(min = 48.dp).testTag("knot_level_1"),
            ) {
                Text(stringResource(R.string.knot_steep_action))
            }
        }
        // The action under its sentence, lined up with it: beside it, the sentence would shrink to a column.
        Column(Modifier.fillMaxWidth().testTag("knot_steep")) {
            text(Modifier)
            action()
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

/** How far apart the spots are; a spacing the wall can't keep playable for this many climbers is greyed out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KnotSpacingPicker(knot: KnotState, actions: WallGamesActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.knot_spacing), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KnotSpacing.entries.forEach { spacing ->
                FilterChip(
                    selected = spacing == knot.spacing,
                    onClick = { actions.setKnotSpacing(spacing) },
                    enabled = spacing in knot.spacings,
                    label = { Text(stringResource(spacing.labelRes)) },
                    colors = chipColors(),
                    modifier = Modifier.testTag("knot_spacing_${spacing.name.lowercase()}"),
                )
            }
        }
        Text(
            stringResource(R.string.knot_spacing_cm, (knot.spacing.handsM * 100).roundToInt()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val greyed = KnotSpacing.entries.filter { it !in knot.spacings }
        if (greyed.isNotEmpty()) {
            Text(
                pluralStringResource(
                    R.plurals.knot_spacing_unavailable,
                    knot.climbers,
                    greyed.map { stringResource(it.labelRes) }.joinToString(", "),
                    knot.climbers,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("knot_spacing_unavailable"),
            )
        }
    }
}

private val KnotSpacing.labelRes: Int
    get() = when (this) {
        KnotSpacing.CLOSE -> R.string.knot_spacing_close
        KnotSpacing.MEDIUM -> R.string.knot_spacing_medium
        KnotSpacing.WIDE -> R.string.knot_spacing_wide
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
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.knot_by_tap), style = MaterialTheme.typography.bodyLarge)
            if (knot.byTap) {
                Text(
                    stringResource(R.string.knot_by_tap_on),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
        if (run.phase == KnotPhase.RUNNING || (run.phase == KnotPhase.FINISHED && run.climbers.size > 1)) {
            KnotClimbers(state.knot, actions)
        }
        // Where everyone starts; once the calls run, the wall itself shows the field and the screen keeps the call.
        if (run.phase == KnotPhase.POSITIONS) GameBoardPreview(state)
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
    Text(
        stringResource(R.string.knot_call_number, call.number),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("knot_call_number"),
    )
    // The calls start with the player, so the screen does too; solo there is only one.
    if (run.climbers.size > 1) {
        BigText(shownName(knot, call.climber), maxSize = 44.dp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("knot_call_who"))
    }
    BigText(stringResource(call.limb.labelRes).capitalised(), maxSize = 60.dp, modifier = Modifier.testTag("knot_call_limb"))
    // Dot and word as one group, centred together.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ColourDot(call.colour, 36)
        Box(Modifier.weight(1f, fill = false)) {
            BigText(stringResource(call.colour.labelRes).capitalised(), maxSize = 60.dp, fill = false, modifier = Modifier.testTag("knot_call_colour"))
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

/**
 * Who is still in: whoever comes off taps their own name; a wrong tap is undone by tapping the name again. Solo there
 * is one button. The player called now stands out. After the round, the names without numbers, winner first.
 */
@Composable
private fun KnotClimbers(knot: KnotState, actions: WallGamesActions) {
    val run = knot.run
    val playing = run.phase == KnotPhase.RUNNING
    if (playing && run.climbers.size == 1) {
        // Solo, the fall is the one action that counts.
        Button(
            onClick = { actions.knotOut(0) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("knot_out_0"),
        ) { Text(stringResource(R.string.knot_fell), style = MaterialTheme.typography.titleMedium) }
        return
    }
    Column(Modifier.fillMaxWidth().testTag("knot_climbers"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (playing) {
            Text(stringResource(R.string.knot_tap_name), style = MaterialTheme.typography.titleSmall)
        }
        val order = if (playing) run.climbers.indices.toList()
        else run.climbers.indices.sortedWith(compareByDescending<Int> { it == run.winner }.thenByDescending { run.climbers[it].held })
        order.forEach { index ->
            val climber = run.climbers[index]
            val name = if (playing) shownName(knot, index) else climberName(LocalContext.current, knot.names, index, numbered = false)
            // Until the next call, a fresh Out can be undone: the row says so.
            val undo = playing && climber.out && climber.outAtCall == run.call?.number
            val status = when {
                undo -> stringResource(R.string.knot_undo)
                climber.out -> pluralStringResource(R.plurals.knot_out_after, climber.held, climber.held)
                else -> pluralStringResource(R.plurals.knot_held_short, climber.held, climber.held)
            }
            val called = playing && run.call?.climber == index && !climber.out
            // The name is what you tap: it keeps its room, the count is short; at the largest font they stack.
            val compact = compactLayout()
            val row: @Composable () -> Unit = {
                val nameText: @Composable (Modifier) -> Unit = { modifier ->
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (run.winner == index || called) FontWeight.Bold else FontWeight.Normal,
                        textDecoration = if (climber.out) TextDecoration.LineThrough else null,
                        maxLines = if (compact) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = modifier,
                    )
                }
                val statusText: @Composable (Modifier) -> Unit = { modifier ->
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (undo) CruxCoachDesign.colors.brandAccent else LocalContentColor.current,
                        fontWeight = if (undo) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        modifier = modifier,
                    )
                }
                if (compact) {
                    Column(Modifier.fillMaxWidth()) {
                        nameText(Modifier)
                        statusText(Modifier)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        nameText(Modifier.weight(1f))
                        statusText(Modifier.padding(start = 8.dp))
                    }
                }
            }
            if (playing) {
                OutlinedButton(
                    onClick = { actions.knotOut(index) },
                    colors = if (called) ButtonDefaults.outlinedButtonColors(containerColor = CruxCoachDesign.colors.brandAccentContainer)
                    else ButtonDefaults.outlinedButtonColors(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("knot_out_$index"),
                ) {
                    val colour = if (climber.out) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    CompositionLocalProvider(LocalContentColor provides colour) { row() }
                }
            } else {
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 24.dp, vertical = 8.dp).testTag("knot_climber_$index")) {
                    row()
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
        // A long name wraps instead of shrinking to nothing.
        winner != null -> Text(
            stringResource(R.string.knot_wins, climberName(LocalContext.current, knot.names, winner, numbered = false)),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("knot_result"),
        )
        else -> Text(
            stringResource(R.string.knot_stopped),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("knot_result"),
        )
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
            // Outlined: one stray tap must not look like the thing to do.
            OutlinedButton(onClick = actions::stop, modifier = slot.heightIn(min = 48.dp).testTag("wall_games_stop")) {
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
