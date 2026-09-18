package com.cruxcoach.android.ui.board

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
    activeFilterCount: Int = 0,
    onOpenMenu: () -> Unit,
    onBoardPicker: () -> Unit,
    onBluetooth: () -> Unit,
    onFilter: () -> Unit,
) {
    val angleDescription = stringResource(R.string.board_angle_change, angle)
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = onOpenMenu)
                .testTag("board_browser_home").tourTarget(TourTarget.MENU), contentAlignment = Alignment.Center) {
                Image(painterResource(R.mipmap.ic_launcher_foreground), stringResource(R.string.cd_open_menu),
                    Modifier.size(30.dp).clip(CircleShape).background(Color.Black))
            }
            BoxWithConstraints(Modifier.weight(1f).heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onBoardPicker)
                .testTag("board_browser_board_picker").tourTarget(TourTarget.BOARD)
                .semantics(mergeDescendants = true) {
                    contentDescription = listOf(context.title, context.subtitle).filter { it.isNotBlank() }.joinToString(", ")
                }.padding(horizontal = 4.dp)) {
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
            TextButton(onClick = onAngle, contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(60.dp).heightIn(min = 48.dp).testTag("board_header_angle")
                    .tourTarget(TourTarget.ANGLE).semantics {
                        contentDescription = angleDescription
                    }) {
                val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                val measured = rememberTextMeasurer().measure(AnnotatedString("$angle°"), style, softWrap = false).size.width.coerceAtLeast(1)
                val available = with(LocalDensity.current) { 52.dp.toPx() }
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
                BadgedBox(badge = { if (activeFilterCount > 0) Badge { Text(activeFilterCount.toString()) } }) {
                    Icon(Icons.Default.Tune, stringResource(R.string.cd_filter))
                }
            }
        }
    }
}
