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
    onDismiss: () -> Unit,
    onNavigateToSync: (() -> Unit)? = null
) {
    var currentSelection by remember { mutableIntStateOf(selectedId) }
    val isEmpty = productSizes.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isEmpty) "Board-Daten fehlen"
                else "Welches Kilter Board hast du?",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (isEmpty) {
                Text(
                    if (onNavigateToSync != null)
                        "Die Board-Datenbank ist noch nicht synchronisiert. " +
                            "Starte den Sync, danach kannst du dein Board-Modell auswählen."
                    else
                        "Die Board-Datenbank ist noch nicht synchronisiert. " +
                            "Starte den Sync unten auf diesem Screen, danach kannst du dein Board-Modell auswählen.",
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
                        "Die Auswahl bestimmt welche Griffe und LEDs angezeigt werden.",
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
                    Text("Sync starten", fontWeight = FontWeight.Bold)
                }
                isEmpty -> TextButton(onClick = onDismiss) { Text("Schließen") }
                else -> Button(
                    onClick = { onConfirm(currentSelection) },
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Bestätigen", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = if (isEmpty && onNavigateToSync == null) null else {
            { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
        }
    )
}
