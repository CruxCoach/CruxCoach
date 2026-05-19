package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.data.repository.BoardSize

@Composable
internal fun BoardModelSelectionDialog(
    productSizes: List<BoardSize>,
    frequency: Map<Int, Long> = emptyMap(),
    selectedId: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
    onNavigateToSync: (() -> Unit)? = null,
    onFindViaGym: (() -> Unit)? = null,
) {
    var currentSelection by remember { mutableIntStateOf(selectedId) }
    val isEmpty = productSizes.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            // Heading + (right, same line) the "don't know? find your
            // gym" entry — the single place this action lives.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isEmpty) stringResource(R.string.board_model_dialog_title_missing)
                    else stringResource(R.string.board_model_dialog_title_pick),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (onFindViaGym != null && !isEmpty) {
                    TextButton(
                        onClick = onFindViaGym,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.settings_board_find_via_gym),
                            color = OrangeAccent,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
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
                // In-dialog Original/Homewall segment — shown only when
                // the list spans both products (so the post-sync inline
                // picker, which passes a single-product list, is
                // unchanged). Replaces the old standalone layout chip.
                val products = remember(productSizes) {
                    productSizes.map { it.productId.toInt() }.distinct()
                }
                var segProduct by remember {
                    mutableIntStateOf(
                        productSizes.firstOrNull { it.id.toInt() == selectedId }
                            ?.productId?.toInt()
                            ?: products.firstOrNull()
                            ?: BoardConstants.KILTER_PRODUCT_ID
                    )
                }
                val shown = productSizes
                    .filter { products.size <= 1 || it.productId.toInt() == segProduct }
                    .sortedByDescending { frequency[it.id.toInt()] ?: 0L }
                Column {
                    if (products.size > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = segProduct == BoardConstants.KILTER_PRODUCT_ID,
                                onClick = { segProduct = BoardConstants.KILTER_PRODUCT_ID },
                                label = { Text(stringResource(R.string.settings_board_layout_original)) },
                            )
                            FilterChip(
                                selected = segProduct == BoardConstants.KILTER_HOMEWALL_PRODUCT_ID,
                                onClick = { segProduct = BoardConstants.KILTER_HOMEWALL_PRODUCT_ID },
                                label = { Text(stringResource(R.string.settings_board_layout_homewall)) },
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Column(
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        shown.forEach { size ->
                            val picked = currentSelection == size.id.toInt()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (picked) OrangeAccent.copy(alpha = 0.12f)
                                        else Color.Transparent
                                    )
                                    .selectable(
                                        selected = picked,
                                        onClick = { currentSelection = size.id.toInt() },
                                        role = Role.RadioButton
                                    )
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = picked,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = OrangeAccent
                                    )
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = BoardConstants.sizeLabel(size.id, size.name),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (picked) OrangeAccent else Color.Unspecified,
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
