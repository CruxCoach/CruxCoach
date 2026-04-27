package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.data.repository.BoardSize

@Composable
internal fun BoardModelSelectionDialog(
    productSizes: List<BoardSize>,
    selectedId: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    onNavigateToSync: (() -> Unit)? = null
) {
    var currentSelection by remember { mutableIntStateOf(selectedId) }
    val isEmpty = productSizes.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isEmpty) stringResource(R.string.board_model_dialog_title_missing)
                else stringResource(R.string.board_model_dialog_title_pick),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (isEmpty) {
                Text(
                    if (onNavigateToSync != null)
                        stringResource(R.string.board_model_dialog_body_missing_with_sync)
                    else
                        stringResource(R.string.board_model_dialog_body_missing_inline),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        productSizes.forEach { size ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = currentSelection == size.id.toInt(),
                                        onClick = { currentSelection = size.id.toInt() },
                                        role = Role.RadioButton
                                    )
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = currentSelection == size.id.toInt(),
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = OrangeAccent
                                    )
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = size.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.board_model_dialog_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            when {
                isEmpty && onNavigateToSync != null -> Button(
                    onClick = {
                        onDismiss()
                        onNavigateToSync.invoke()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        stringResource(R.string.board_model_dialog_start_sync),
                        fontWeight = FontWeight.Bold,
                    )
                }
                isEmpty -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.board_model_dialog_close))
                }
                else -> Button(
                    onClick = { onConfirm(currentSelection) },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        stringResource(R.string.board_model_dialog_confirm),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        dismissButton = if (isEmpty && onNavigateToSync == null) null else {
            {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.board_model_dialog_cancel))
                }
            }
        }
    )
}
