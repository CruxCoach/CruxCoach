package com.cruxcoach.android.ui.training.common

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.delay

/** How long typing pauses before a valid entry is kept. */
const val AUTOSAVE_DELAY_MS = 1_000L

/**
 * A text field that saves itself (owner 2026-10-10: settings in training and
 * nutrition have no save buttons). A valid entry is kept once typing pauses,
 * on the keyboard's "Fertig", when the field loses focus and when it leaves
 * the screen. [stored] is the saved value as text ("" when there is none);
 * [isValid] decides what may be saved, and an invalid entry is marked.
 */
@Composable
fun AutoSaveTextField(
    stored: String,
    isValid: (String) -> Boolean,
    onSave: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Decimal,
) {
    var text by rememberSaveable(stored) { mutableStateOf(stored) }
    // What was handed over last, so a pause, "Fertig" and leaving the field save it once.
    var handedOver by rememberSaveable(stored) { mutableStateOf(stored) }
    val pending = text.trim() != handedOver && isValid(text.trim())
    val latestPending by rememberUpdatedState(pending)
    val latestText by rememberUpdatedState(text.trim())
    val latestSave by rememberUpdatedState(onSave)
    fun commit() {
        if (!latestPending) return
        handedOver = latestText
        latestSave(latestText)
    }
    LaunchedEffect(text) {
        if (pending) {
            delay(AUTOSAVE_DELAY_MS)
            commit()
        }
    }
    DisposableEffect(Unit) { onDispose { commit() } }
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        isError = text.isNotBlank() && !isValid(text.trim()),
        label = { Text(label) },
        suffix = suffix?.let { s -> { Text(s) } },
        placeholder = placeholder?.let { s -> { Text(s) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
        modifier = modifier.onFocusChanged { if (!it.isFocused) commit() },
    )
}
