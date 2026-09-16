package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.domain.board.BoardBrand

/** Shared accessible board selector. Destructive confirmations require a nonempty
 * selection; download preferences may explicitly disable every catalogue. */
@Composable
internal fun BoardMultiSelectDialog(
    title: String,
    message: String,
    note: String?,
    confirmLabel: String,
    confirmColor: Color,
    selectedBrands: Set<BoardBrand>,
    onToggleBrand: (BoardBrand) -> Unit,
    onToggleSelectAll: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    allowEmpty: Boolean = false,
) {
    val boards = remember { BoardBrand.entries.filter { it.isInteractive } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Board rows and explanatory copy must scroll on small screens.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .triStateToggleable(
                            state = when {
                                selectedBrands.containsAll(boards) -> ToggleableState.On
                                selectedBrands.isEmpty() -> ToggleableState.Off
                                else -> ToggleableState.Indeterminate
                            },
                            role = Role.Checkbox,
                            onClick = onToggleSelectAll,
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TriStateCheckbox(
                        state = when {
                            selectedBrands.containsAll(boards) -> ToggleableState.On
                            selectedBrands.isEmpty() -> ToggleableState.Off
                            else -> ToggleableState.Indeterminate
                        },
                        onClick = null,
                        colors = CheckboxDefaults.colors(checkedColor = confirmColor)
                    )
                    Text(
                        stringResource(R.string.settings_data_delete_select_all),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                boards.forEach { brand ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("board_selection_${brand.wireValue}")
                            .toggleable(
                                value = brand in selectedBrands,
                                role = Role.Checkbox,
                                onValueChange = { onToggleBrand(brand) },
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = brand in selectedBrands,
                            onCheckedChange = null,
                            colors = CheckboxDefaults.colors(checkedColor = confirmColor)
                        )
                        Text(brand.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                note?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = allowEmpty || selectedBrands.isNotEmpty()) {
                // No explicit color when disabled — it would override the
                // TextButton's dimmed disabled content color.
                if (allowEmpty || selectedBrands.isNotEmpty()) {
                    Text(confirmLabel, color = confirmColor)
                } else {
                    Text(confirmLabel)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
