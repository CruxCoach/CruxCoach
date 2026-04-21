package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.WarningYellow
import com.cruxcoach.data.repository.ClimbList

@Composable
internal fun AddToListDialog(
    lists: List<ClimbList>,
    climbInListIds: Set<Long>,
    newListName: String,
    onToggleList: (Long) -> Unit,
    onNewListNameChanged: (String) -> Unit,
    onCreateAndAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.board_addtolist_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lists.forEach { list ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = climbInListIds.contains(list.id),
                            onCheckedChange = { onToggleList(list.id) },
                            colors = CheckboxDefaults.colors(checkedColor = OrangeAccent)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            if (list.isBuiltin) Icons.Default.Star else Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = null,
                            tint = if (list.isBuiltin) WarningYellow else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(list.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newListName,
                        onValueChange = onNewListNameChanged,
                        placeholder = { Text(stringResource(R.string.board_addtolist_new_list)) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = onCreateAndAdd,
                        enabled = newListName.isNotBlank()
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.cd_create),
                            tint = if (newListName.isNotBlank()) OrangeAccent
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (newListName.isNotBlank()) onCreateAndAdd()
                onDismiss()
            }) {
                Text(stringResource(R.string.board_addtolist_done), fontWeight = FontWeight.Bold, color = OrangeAccent)
            }
        }
    )
}
