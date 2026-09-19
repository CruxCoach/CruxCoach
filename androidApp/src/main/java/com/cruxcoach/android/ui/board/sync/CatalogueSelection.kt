package com.cruxcoach.android.ui.board.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.domain.board.BoardBrand

/** One selection language throughout setup and catalogue settings. Taps only edit a draft. */
@Composable
internal fun CatalogueSelectionRows(selectedBrands: Set<BoardBrand>, onToggleBrand: (BoardBrand) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoardBrand.entries.filter { it.isInteractive }.forEach { brand ->
            val checked = brand in selectedBrands
            Surface(
                color = if (checked) OrangeAccent.copy(alpha = 0.10f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .testTag("board_selection_${brand.wireValue}")
                        .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggleBrand(brand) })
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(brand.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Icon(if (checked) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = null, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Composable
internal fun CatalogueSelectionDialog(
    selectedBrands: Set<BoardBrand>,
    isSyncing: Boolean,
    onToggleBrand: (BoardBrand) -> Unit,
    onToggleSelectAll: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val allSelected = selectedBrands.containsAll(BoardBrand.entries.filter { it.isInteractive })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_catalogues_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onToggleSelectAll, modifier = Modifier.weight(1f, fill = false).testTag("catalogue_toggle_all")) {
                        Text(stringResource(if (allSelected) R.string.setup_deselect_all else R.string.cd_select_all))
                    }
                    InfoButton(stringResource(R.string.setup_catalogues_title), stringResource(R.string.catalogue_selection_help))
                }
                CatalogueSelectionRows(selectedBrands, onToggleBrand)
                Text(stringResource(if (isSyncing) R.string.catalogue_queue_hint else R.string.catalogue_confirm_hint),
                    style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, modifier = Modifier.testTag("catalogue_confirm")) {
                Text(stringResource(R.string.catalogue_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
