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
import androidx.compose.ui.platform.LocalDensity
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
import com.cruxcoach.android.ui.training.workouts.EditorStepper
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
        knot.layout == null && !knot.pending -> R.string.knot_no_field
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
        // Eight in one row: a stepper, as eight chips would take two rows.
        EditorStepper(
            label = stringResource(R.string.knot_climbers),
            value = knot.players,
            step = 1,
            min = Knot.PLAYERS.first,
            max = Knot.PLAYERS.last,
            tag = "knot_players",
            onChange = actions::setKnotPlayers,
        )
        // More players than the wall takes: the others wait in line.
        if (knot.players > knot.atWall) {
            Text(
                pluralStringResource(R.plurals.knot_line, knot.atWall, knot.atWall),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("knot_line"),
            )
        }
    }
    // Solo needs no name: the calls go to the one climber.
    if (knot.players > 1) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (0 until knot.players).forEach { climber ->
                OutlinedTextField(
                    value = knot.names[climber],
                    onValueChange = { actions.setKnotName(climber, it) },
                    label = { Text(stringResource(R.string.knot_player, climber + 1)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = if (climber < knot.players - 1) ImeAction.Next else ImeAction.Done,
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

/** How far the climbers reach for the colours; a spacing the wall can't keep playable for this many is greyed out. */
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
        // The reach the field really has, to the nearest 5 cm.
        val reachCm = ((knot.reachM ?: knot.spacing.reachM) * 20).roundToInt() * 5
        Text(
            stringResource(R.string.knot_spacing_cm, reachCm),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Two reasons a spacing is greyed out: no room for it, or no further reach than a closer one.
        if (knot.sameReach.isNotEmpty()) {
            Text(
                pluralStringResource(
                    R.plurals.knot_spacing_same,
                    knot.atWall,
                    knot.sameReach.sorted().map { stringResource(it.labelRes) }.joinToString(", "),
                    knot.atWall,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("knot_spacing_same"),
            )
        }
        val greyed = KnotSpacing.entries.filter { it !in knot.spacings && it !in knot.sameReach }
        if (greyed.isNotEmpty()) {
            Text(
                pluralStringResource(
                    R.plurals.knot_spacing_unavailable,
                    knot.atWall,
                    greyed.map { stringResource(it.labelRes) }.joinToString(", "),
                    knot.atWall,
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
        // The next in line, called to the wall: a notice of its own above the call, where everyone looks after the beep.
        val stepIn = run.stepIn
        if (stepIn != null && run.active) {
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("knot_step_in"),
                color = CruxCoachDesign.colors.brandAccentContainer,
                contentColor = CruxCoachDesign.colors.onBrandAccentContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                // Whose place: the one who came off it, so the newcomer knows where to start.
                val place = run.climbers[stepIn].part
                val before = run.climbers.indices.lastOrNull { it != stepIn && run.climbers[it].part == place && run.climbers[it].out }
                Text(
                    if (before != null) stringResource(R.string.knot_to_wall_for, shownName(state.knot, stepIn), shownName(state.knot, before))
                    else stringResource(R.string.knot_to_wall, shownName(state.knot, stepIn)),
                    // In dp like the call below it: at the largest font it would push the names off the screen.
                    style = with(LocalDensity.current) {
                        MaterialTheme.typography.titleLarge.copy(fontSize = STEP_IN_TEXT.toSp(), lineHeight = STEP_IN_LINE.toSp())
                    },
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
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
    val run = knot.run
    Text(stringResource(R.string.knot_positions_solo), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    if (run.climbers.size <= 1 || run.field == null) return
    // Who climbs where: by name for up to three parts, else in order from the left.
    val sides = when (run.parts) {
        2 -> listOf(R.string.knot_part_left, R.string.knot_part_right)
        3 -> listOf(R.string.knot_part_left, R.string.knot_part_middle, R.string.knot_part_right)
        else -> null
    }
    if (sides == null && run.parts > 1) {
        Text(stringResource(R.string.knot_from_left), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    run.onParts.forEachIndexed { part, climber ->
        if (climber == null) return@forEachIndexed
        Text(
            sides?.let { stringResource(R.string.knot_part, shownName(knot, climber), stringResource(it[part])) } ?: shownName(knot, climber),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("knot_part_$climber"),
        )
    }
    KnotLine(knot)
}

/** Who waits, in order: "Next up: 5 · Eve, 6 · Finn". */
@Composable
private fun KnotLine(knot: KnotState) {
    val line = knot.run.line
    if (line.isEmpty()) return
    val context = LocalContext.current
    Text(
        stringResource(R.string.knot_next_up, line.joinToString(", ") { climberName(context, knot.names, it) }),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag("knot_next_up"),
    )
}

/** The call in large type, readable from the wall: who, which hand or foot, which colour, and the time left. */
@Composable
private fun KnotCallPanel(knot: KnotState) {
    val run = knot.run
    val call = run.call ?: return
    // At the largest font the rest of the screen grows: the call gives up a little, so the names stay in view.
    val small = compactLayout()
    Text(
        stringResource(R.string.knot_call_number, call.number),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("knot_call_number"),
    )
    // The calls start with the player, so the screen does too; solo there is only one.
    if (run.climbers.size > 1) {
        BigText(shownName(knot, call.climber), maxSize = if (small) 36.dp else 44.dp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("knot_call_who"))
    }
    BigText(stringResource(call.limb.labelRes).capitalised(), maxSize = if (small) 48.dp else 60.dp, modifier = Modifier.testTag("knot_call_limb"))
    // Dot and word as one group, centred together.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ColourDot(call.colour, 36)
        Box(Modifier.weight(1f, fill = false)) {
            BigText(stringResource(call.colour.labelRes).capitalised(), maxSize = if (small) 48.dp else 60.dp, fill = false, modifier = Modifier.testTag("knot_call_colour"))
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
        // While playing, one button per part of the field, left to right, so a button stays where it is: the climber
        // there, or the last one off it. A fresh Out whose part the next in line took comes last until the next call,
        // so nothing moves when it appears.
        val order = if (playing) {
            val onParts = run.onParts.filterNotNull()
            onParts + run.climbers.indices.filter { it !in onParts && run.climbers[it].out && run.climbers[it].outAtCall == run.call?.number }
        } else {
            run.climbers.indices.sortedWith(compareByDescending<Int> { it == run.winner }.thenByDescending { run.climbers[it].held })
        }
        // From three parts on, the buttons go two to a row, so all of them and a fresh Out fit under the call without
        // scrolling. Not at the largest font: half a row breaks a name like "2 · Benedikt" into pieces, even 411 dp wide.
        val compact = compactLayout()
        val columns = if (playing && run.parts >= GRID_FROM && !compact) 2 else 1
        val buttons = order.map { index -> @Composable { modifier: Modifier -> KnotClimberRow(knot, index, playing, stacked = compact || columns > 1, actions, modifier) } }
        buttons.chunked(columns).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                pair.forEach { button -> button(Modifier.weight(1f)) }
                // A lone last button keeps its column's width.
                repeat(columns - pair.size) { Box(Modifier.weight(1f)) }
            }
        }
        if (playing) KnotLine(knot)
    }
}

/** The size of the call to the wall, whatever the font size. */
private val STEP_IN_TEXT = 22.dp
private val STEP_IN_LINE = 28.dp

/** From this many parts of the field on, two name buttons go in a row. */
private const val GRID_FROM = 3

/** One player's button while playing (tap when you come off), or their line in the result. */
@Composable
private fun KnotClimberRow(knot: KnotState, index: Int, playing: Boolean, stacked: Boolean, actions: WallGamesActions, modifier: Modifier) {
    val run = knot.run
    val climber = run.climbers[index]
    val name = if (playing) shownName(knot, index) else climberName(LocalContext.current, knot.names, index, numbered = false)
    // Until the next call, a fresh Out can be undone: the row says so.
    val undo = playing && climber.out && climber.outAtCall == run.call?.number
    // After a game with a line everyone came off: a ranking by calls held, nobody struck out.
    val struck = climber.out && (playing || !run.withLine)
    val status = when {
        undo -> stringResource(R.string.knot_undo)
        struck -> pluralStringResource(R.plurals.knot_out_after, climber.held, climber.held)
        else -> pluralStringResource(R.plurals.knot_held_short, climber.held, climber.held)
    }
    val called = playing && run.call?.climber == index && !climber.out
    // The name is what you tap: it keeps its room, the count is short; at the largest font or two to a row
    // they stack.
    val compact = stacked
    val row: @Composable () -> Unit = {
        val nameText: @Composable (Modifier) -> Unit = { modifier ->
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (run.winner == index || called) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (struck) TextDecoration.LineThrough else null,
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
            modifier = modifier.heightIn(min = 56.dp).testTag("knot_out_$index"),
        ) {
            val colour = if (climber.out) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            CompositionLocalProvider(LocalContentColor provides colour) { row() }
        }
    } else {
        Box(modifier.heightIn(min = 48.dp).padding(horizontal = 24.dp, vertical = 8.dp).testTag("knot_climber_$index")) {
            row()
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
        // With a line, the most calls held win; several with as many share first place.
        run.withLine && !run.stopped -> {
            val context = LocalContext.current
            val most = run.climbers.maxOf { it.held }
            val first = run.climbers.indices.filter { run.climbers[it].held == most }
            Text(
                stringResource(R.string.knot_tie, first.joinToString(", ") { climberName(context, knot.names, it, numbered = false) }),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("knot_result"),
            )
        }
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
                // Only once the call has blinked: a double tap mustn't skip the next call.
                enabled = state.knot.run.phase == KnotPhase.RUNNING && state.knot.run.tapReady,
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
