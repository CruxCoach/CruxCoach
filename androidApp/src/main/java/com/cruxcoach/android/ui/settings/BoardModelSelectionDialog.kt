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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.data.repository.BoardSize

@Composable
internal fun BoardModelSelectionDialog(
    productSizes: List<BoardSize>,
    selectedId: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var currentSelection by remember { mutableIntStateOf(selectedId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Welches Kilter Board hast du?",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
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
                    "Die Auswahl bestimmt welche Griffe und LEDs angezeigt werden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(currentSelection) },
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Bestätigen", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}
