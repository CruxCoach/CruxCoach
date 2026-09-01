package com.cruxcoach.android.ui.board

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.ArrowDropDown
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
    )
}

/**
 * Compact browser bar: the active board is the picker entry point, while the
 * existing destinations remain one-tap icon actions without a second label row.
 */
@Composable
internal fun BoardBrowserHeader(
    context: BoardBrowserHeaderContext,
    isBleConnected: Boolean,
    onBoardPicker: () -> Unit,
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
            .height(60.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clickable(onClick = onBoardPicker)
                    .testTag("board_browser_board_picker")
                    .padding(start = 8.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
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
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = context.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (context.subtitle.isNotEmpty()) {
                        Text(
                            text = context.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = stringResource(R.string.board_browser_change_board),
                    tint = OrangeAccent,
                    modifier = Modifier.size(18.dp),
                )
            }
            HeaderAction(
                icon = if (isBleConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                contentDescription = R.string.cd_bluetooth,
                tag = "board_ble_button",
                tint = if (isBleConnected) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onBluetooth,
            )
            HeaderAction(
                icon = Icons.Default.Tune,
                contentDescription = R.string.cd_filter,
                tag = "board_filter_toggle",
                onClick = onFilter,
            )
            HeaderAction(
                icon = Icons.Default.Book,
                contentDescription = R.string.board_logbook_title,
                tag = "board_logbook_icon",
                onClick = onLogbook,
            )
            HeaderAction(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                contentDescription = R.string.board_lists_title,
                tag = "board_lists_button",
                onClick = onLists,
            )
            HeaderAction(
                icon = Icons.Default.Settings,
                contentDescription = R.string.cd_settings,
                tag = "board_settings_button",
                onClick = onSettings,
            )
        }
    }
}

@Composable
private fun HeaderAction(
    icon: ImageVector,
    @StringRes contentDescription: Int,
    tag: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .width(44.dp)
            .testTag(tag),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(contentDescription),
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}
