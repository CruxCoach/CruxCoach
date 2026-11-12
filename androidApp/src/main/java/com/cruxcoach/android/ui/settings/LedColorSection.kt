package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.data.LedHoldColors
import androidx.compose.ui.platform.LocalContext
import com.cruxcoach.android.ui.theme.ColorFamily
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.RGB332_PALETTE
import com.cruxcoach.android.ui.theme.RGB332_PALETTE_BY_FAMILY
import com.cruxcoach.android.ui.theme.rgb332ColorName
import com.cruxcoach.android.ui.theme.rgb332ToComposeColor
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.HoldRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LedColorSection(
    ledColors: LedHoldColors,
    onColorChange: (roleId: Int, colorByte: Int) -> Unit,
    onResetColors: () -> Unit,
    onKilterColors: () -> Unit
) {
    SettingsInfoHeading(
        title = stringResource(R.string.settings_led_title),
        description = stringResource(R.string.settings_led_desc),
    )

    // Track which role's picker sheet is open
    var sheetTarget by remember { mutableStateOf<Triple<Int, String, Int>?>(null) }

    val roles = listOf(
        Triple(HoldRole.START, stringResource(R.string.settings_led_role_start), ledColors.start),
        Triple(HoldRole.HAND, stringResource(R.string.settings_led_role_hand), ledColors.hand),
        Triple(HoldRole.FINISH, stringResource(R.string.settings_led_role_top), ledColors.finish),
        Triple(HoldRole.FOOT, stringResource(R.string.settings_led_role_foot), ledColors.foot)
    )

    roles.forEach { (roleId, label, currentByte) ->
        LedColorRow(
            label = label,
            currentByte = currentByte,
            onClick = { sheetTarget = Triple(roleId, label, currentByte) }
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(
            onClick = onResetColors,
            modifier = Modifier.testTag("settings_led_reset")
        ) {
            Text(stringResource(R.string.settings_led_reset_cruxcoach), color = OrangeAccent)
        }
        TextButton(
            onClick = onKilterColors,
            modifier = Modifier.testTag("settings_led_reset_kilter")
        ) {
            Text(stringResource(R.string.settings_led_reset_kilter), color = OrangeAccent)
        }
    }

    sheetTarget?.let { (roleId, label, currentByte) ->
        ColorPickerBottomSheet(
            roleLabel = label,
            currentByte = currentByte,
            onColorSelected = { colorByte ->
                onColorChange(roleId, colorByte)
                sheetTarget = null
            },
            onDismiss = { sheetTarget = null }
        )
    }
}

@Composable
private fun LedColorRow(
    label: String,
    currentByte: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                rgb332ColorName(LocalContext.current, currentByte),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(rgb332ToComposeColor(currentByte))
                    .border(2.dp, OrangeAccent.copy(alpha = 0.5f), CircleShape)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ColorPickerBottomSheet(
    roleLabel: String,
    currentByte: Int,
    onColorSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    allowedColors: Set<Int>? = null,
    extraColors: List<Int> = emptyList(),
    extraLabel: String? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LedColorPalette(roleLabel, currentByte, onColorSelected, allowedColors, extraColors, extraLabel)
    }
}

/** One color circle: named for screen readers, with a check mark while it is the current color. */
@Composable
private fun ColorSwatch(color: Color, name: String, isSelected: Boolean, onClick: () -> Unit) {
    val checkTint = if (color.luminance() > 0.5f) Color.Black else Color.White
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (isSelected) {
                    Modifier.border(
                        3.dp,
                        MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
                } else {
                    Modifier.border(
                        1.dp,
                        Color.White.copy(alpha = 0.3f),
                        CircleShape
                    )
                }
            )
            .clickable(onClick = onClick)
            // A bare colour circle announces nothing. Merge, so the name and
            // the selected state land on the clickable node itself instead of
            // a sibling the reader never focuses.
            .semantics(mergeDescendants = true) {
                contentDescription = name
                this.selected = isSelected
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                // The merged parent already announces the colour and that it
                // is selected; a second description would split the node.
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = checkTint
            )
        }
    }
}

/** Shared palette content, independently testable from the platform sheet window. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LedColorPalette(
    roleLabel: String,
    currentByte: Int,
    onColorSelected: (Int) -> Unit,
    allowedColors: Set<Int>? = null,
    /** Colors without a named swatch (e.g. from a PaintBox photo), shown first under [extraLabel]. */
    extraColors: List<Int> = emptyList(),
    extraLabel: String? = null,
) {
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.settings_led_pick_color, roleLabel),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (extraColors.isNotEmpty() && extraLabel != null) {
                Text(
                    extraLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    extraColors.forEachIndexed { index, byte ->
                        val display = rgb332ToComposeColor(byte)
                        // No name of its own: number it and say which named color it resembles.
                        val similar = RGB332_PALETTE.minBy {
                            val r = it.displayColor.red - display.red
                            val g = it.displayColor.green - display.green
                            val b = it.displayColor.blue - display.blue
                            r * r + g * g + b * b
                        }
                        ColorSwatch(
                            display,
                            stringResource(R.string.paintbox_color_picture, index + 1, stringResource(similar.nameResId)),
                            byte == currentByte,
                        ) { onColorSelected(byte) }
                    }
                }
            }

            ColorFamily.entries.forEach { family ->
                val colors = RGB332_PALETTE_BY_FAMILY[family]
                    ?.filter { allowedColors == null || it.byte in allowedColors }
                    ?.takeIf { it.isNotEmpty() } ?: return@forEach

                Text(
                    stringResource(family.labelResId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    colors.forEach { color ->
                        ColorSwatch(
                            color.displayColor,
                            rgb332ColorName(LocalContext.current, color.byte),
                            color.byte == currentByte,
                        ) { onColorSelected(color.byte) }
                    }
                }
            }
        }
    }
}
