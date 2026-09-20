package com.cruxcoach.android.ui.board

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.ui.onboarding.*
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant

internal data class BoardBrowserHeaderContext(
    val title: String,
    val subtitle: String,
    val family: String = title.substringBefore(" "),
)

internal fun boardBrowserHeaderContext(
    boardBrand: String,
    layoutId: Int,
    boardSize: BoardSize?,
): BoardBrowserHeaderContext {
    val brand = BoardBrand.fromWire(boardBrand)
    val title = when (brand) {
        BoardBrand.KILTER -> if (layoutId == BoardConstants.KILTER_HOMEWALL_LAYOUT) {
            "Kilter Homewall"
        } else {
            "Kilter Original"
        }
        BoardBrand.MOONBOARD ->
            MoonBoardVariant.fromLayoutId(layoutId.toLong())?.displayName ?: brand.displayName
        else -> BoardConstants.auroraVariant(brand, layoutId)?.displayName ?: brand.displayName
    }
    val size = boardSize
        ?.let { BoardConstants.sizeLabel(it.id, it.name, it.boardBrand) }
        ?.removePrefix("Homewall ")
        ?.takeIf(String::isNotBlank)
    return BoardBrowserHeaderContext(
        title = title,
        subtitle = size.orEmpty(),
        family = brand.displayName,
    )
}

/** One toolbar row: only board details shrink, never the family or primary actions. */
@Composable
internal fun BoardBrowserHeader(
    context: BoardBrowserHeaderContext,
    isBleConnected: Boolean,
    angle: Int,
    onAngle: () -> Unit,
    onOpenMenu: () -> Unit,
    onBoardPicker: () -> Unit,
    onBluetooth: () -> Unit,
    onFilter: () -> Unit,
    onLogbook: () -> Unit = {},
    onLists: () -> Unit = {},
    onSettings: () -> Unit = {},
    logbookTour: Boolean = false,
    onSkipTour: () -> Unit = {},
    /** Reports whether the logbook action currently sits in the overflow menu, so the tour can
     *  point at where it actually is instead of hedging about narrow screens. */
    onLogbookPlacement: (inOverflow: Boolean) -> Unit = {},
) {
    val angleDescription = stringResource(R.string.board_angle_change, angle)
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 1.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val measurer = rememberTextMeasurer()
            val familyWidth = with(density) {
                measurer.measure(AnnotatedString(context.family), MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), softWrap = false).size.width.toDp()
            }
            // The picker only takes what its content needs (capped), so short names leave room for more direct actions.
            val preferredBoardWidth = with(density) {
                val titleWidth = measurer.measure(AnnotatedString(context.title), MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), softWrap = false).size.width
                val subtitleWidth = measurer.measure(AnnotatedString(context.subtitle), MaterialTheme.typography.labelSmall, softWrap = false).size.width
                maxOf(titleWidth, subtitleWidth).toDp()
            }.plus(22.dp).coerceIn(56.dp, 132.dp)
            val minimumBoardWidth = (familyWidth + 20.dp).coerceAtLeast(80.dp).coerceAtMost(preferredBoardWidth)
            val allFit = maxWidth >= 192.dp + minimumBoardWidth + 144.dp
            val directCount = if (allFit) 3 else ((maxWidth - 240.dp - minimumBoardWidth).value / 48f).toInt().coerceIn(0, 2)
            val boardWidth = (maxWidth - 192.dp - (directCount * 48).dp - if (allFit) 0.dp else 48.dp)
                .coerceAtMost(preferredBoardWidth).coerceAtLeast(1.dp)
            LaunchedEffect(directCount) { onLogbookPlacement(directCount == 0) }
            var overflowOpen by remember { mutableStateOf(false) }
            val actions = listOf(
                Triple(R.string.board_logbook_title, Icons.Default.Book, onLogbook),
                Triple(R.string.board_lists_title, Icons.AutoMirrored.Filled.FormatListBulleted, onLists),
                Triple(R.string.cd_settings, Icons.Default.Settings, onSettings),
            )
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = onOpenMenu)
                    .testTag("board_browser_home"), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.mipmap.ic_launcher_foreground), stringResource(R.string.cd_open_menu),
                        Modifier.size(30.dp).clip(CircleShape).background(Color.Black))
                }
                BoxWithConstraints(Modifier.width(boardWidth).heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onBoardPicker)
                    .testTag("board_browser_board_picker").tourTarget(TourTarget.BOARD)
                    .semantics(mergeDescendants = true) {
                        contentDescription = listOf(context.title, context.subtitle).filter { it.isNotBlank() }.joinToString(", ")
                    }.padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                    val measurer = rememberTextMeasurer()
                    val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    val available = with(LocalDensity.current) { (maxWidth - 12.dp).toPx() }.coerceAtLeast(1f)
                    val fullWidth = measurer.measure(AnnotatedString(context.title), style, softWrap = false).size.width
                    val title = if (fullWidth <= available) context.title else context.family
                    val familyWidth = measurer.measure(AnnotatedString(title), style, softWrap = false).size.width.coerceAtLeast(1)
                    val scale = (available / familyWidth).coerceAtMost(1f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = style, fontSize = style.fontSize * scale,
                                maxLines = 1, softWrap = false)
                            val detail = listOf(if (title == context.title) "" else context.title.removePrefix(context.family).trim(), context.subtitle)
                                .filter { it.isNotBlank() }.joinToString(" · ")
                            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Default.ArrowDropDown, null, Modifier.size(12.dp), tint = OrangeAccent)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onAngle, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.width(48.dp).heightIn(min = 48.dp).testTag("board_header_angle")
                        .tourTarget(TourTarget.ANGLE).semantics {
                            contentDescription = angleDescription
                        }) {
                    val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    val measured = rememberTextMeasurer().measure(AnnotatedString("$angle°"), style, softWrap = false).size.width.coerceAtLeast(1)
                    val available = with(LocalDensity.current) { 44.dp.toPx() }
                    Text("$angle°", style = style, fontSize = style.fontSize * (available / measured).coerceAtMost(1f),
                        maxLines = 1, softWrap = false)
                }
                IconButton(onClick = onBluetooth, modifier = Modifier.size(48.dp).testTag("board_ble_button")
                    .tourTarget(TourTarget.BLUETOOTH)) {
                    Icon(if (isBleConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                        stringResource(R.string.cd_bluetooth), tint = if (isBleConnected) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onFilter, modifier = Modifier.size(48.dp).testTag("board_filter_toggle")
                    .tourTarget(TourTarget.FILTER)) {
                    Icon(Icons.Default.Tune, stringResource(R.string.cd_filter))
                }
                actions.take(directCount).forEachIndexed { index, (label, icon, action) ->
                    IconButton(onClick = action, modifier = Modifier.size(48.dp)
                        .testTag("board_header_action_$index")
                        .then(if (index == 0) Modifier.tourTarget(TourTarget.MENU) else Modifier)) {
                        Icon(icon, stringResource(label))
                    }
                }
                if (!allFit) Box {
                    IconButton(onClick = { overflowOpen = true }, modifier = Modifier.size(48.dp)
                        .testTag("board_header_overflow")
                        .then(if (directCount == 0) Modifier.tourTarget(TourTarget.MENU) else Modifier)) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.action_more_options))
                    }
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        actions.drop(directCount).forEachIndexed { index, (label, icon, action) ->
                            // During the tour the menu is the spotlight's continuation: mark the
                            // one entry to tap the same way the highlighted button was marked.
                            val isTourTarget = logbookTour && directCount + index == 0
                            DropdownMenuItem(text = { Text(stringResource(label)) },
                                leadingIcon = { Icon(icon, null) },
                                enabled = !logbookTour || isTourTarget,
                                modifier = Modifier
                                    .testTag("board_header_overflow_action_${directCount + index}")
                                    .then(
                                        if (isTourTarget) {
                                            Modifier
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                                .border(2.dp, OrangeAccent, RoundedCornerShape(8.dp))
                                        } else {
                                            Modifier
                                        }
                                    ),
                                onClick = { overflowOpen = false; action() })
                        }
                        if (logbookTour) {
                            HorizontalDivider()
                            FilledTonalButton(onClick = { overflowOpen = false; onSkipTour() },
                                modifier = Modifier.padding(8.dp).fillMaxWidth().heightIn(min = 48.dp)) {
                                Icon(Icons.Default.Close, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.tour_skip))
                            }
                        }
                    }
                }
            }
        }
    }
}
