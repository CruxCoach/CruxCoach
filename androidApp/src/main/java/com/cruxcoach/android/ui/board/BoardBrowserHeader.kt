package com.cruxcoach.android.ui.board

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant

internal data class BoardBrowserHeaderContext(
    val title: String,
    val subtitle: String,
)

internal fun boardBrowserHeaderContext(
    boardBrand: String,
    layoutId: Int,
    boardSize: BoardSize?,
    angle: Int,
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
        ?.takeIf(String::isNotBlank)
    return BoardBrowserHeaderContext(
        title = title,
        subtitle = listOfNotNull(size, "$angle°").joinToString(" · "),
    )
}

/**
 * Variant B: the board context gets a quiet title row; the four primary jobs
 * become explicit, comfortably tappable chips underneath it.
 */
@Composable
internal fun BoardBrowserHeader(
    context: BoardBrowserHeaderContext,
    isBleConnected: Boolean,
    onBluetooth: () -> Unit,
    onFilter: () -> Unit,
    onLogbook: () -> Unit,
    onLists: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .testTag("board_browser_home"),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.board_browser_title),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = context.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = context.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.testTag("board_settings_button"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.cd_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderChip(
                    icon = if (isBleConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                    label = if (isBleConnected) R.string.board_browser_connected else R.string.board_browser_connect,
                    contentDescription = R.string.cd_bluetooth,
                    tag = "board_ble_button",
                    emphasized = true,
                    tint = if (isBleConnected) SuccessGreen else OrangeAccent,
                    onClick = onBluetooth,
                )
                Spacer(Modifier.width(4.dp))
                HeaderChip(
                    icon = Icons.Default.Tune,
                    label = R.string.board_browser_nav_filter,
                    contentDescription = R.string.cd_filter,
                    tag = "board_filter_toggle",
                    onClick = onFilter,
                )
                Spacer(Modifier.width(4.dp))
                HeaderChip(
                    icon = Icons.Default.Book,
                    label = R.string.board_browser_nav_logbook,
                    contentDescription = R.string.board_logbook_title,
                    tag = "board_logbook_icon",
                    onClick = onLogbook,
                )
                Spacer(Modifier.width(4.dp))
                HeaderChip(
                    icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                    label = R.string.board_browser_nav_lists,
                    contentDescription = R.string.board_lists_title,
                    tag = "board_lists_button",
                    onClick = onLists,
                )
            }
        }
    }
}

@Composable
private fun HeaderChip(
    icon: ImageVector,
    @StringRes label: Int,
    @StringRes contentDescription: Int,
    tag: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        color = if (emphasized) {
            tint.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .height(40.dp)
            .testTag(tag)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(contentDescription),
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = stringResource(label),
                color = tint,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
