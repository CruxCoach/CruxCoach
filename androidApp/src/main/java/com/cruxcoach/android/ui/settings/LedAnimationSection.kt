package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.led.LedAnimation
import com.cruxcoach.android.led.LedAnimationRegistry
import com.cruxcoach.android.ui.theme.OrangeAccent

/**
 * The LED animation setting, kept to one row on the Settings screen.
 *
 * Five options with a hint each took up more of the settings list than the whole
 * LED colour section above it, so the options live in a bottom sheet — the same
 * container [LedColorSection] uses for its picker — and the row only reports the
 * current choice. The two-line-per-option layout is what makes the hint worth
 * reading, so the hints moved into the sheet with the options rather than being
 * dropped.
 *
 * Everything comes from [LedAnimationRegistry.ALL] and there is no `when (id)` in
 * here, so adding an animation means touching [com.cruxcoach.android.led] and two
 * strings; this screen picks it up on its own.
 */
@Composable
internal fun LedAnimationSection(
    selectedId: String,
    kilterBoardConnected: Boolean,
    onSelect: (String) -> Unit,
) {
    Text(
        stringResource(R.string.settings_led_anim_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    val selected = LedAnimationRegistry.byId(selectedId)
    var showPicker by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_led_anim_row")
            .clickable { showPicker = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(selected.labelResId),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = { showPicker = true },
            modifier = Modifier.testTag("settings_led_anim_change")
        ) {
            Text(stringResource(R.string.settings_led_anim_change), color = OrangeAccent)
        }
    }

    if (!kilterBoardConnected) {
        // Informational, not a gate: the choice is stored either way and takes
        // effect at the wall, because `LedAnimationEngine` only ever pushes frames
        // while the connected board can take them. Blocking the options instead
        // would make the setting impossible to look at away from the gym.
        Text(
            stringResource(R.string.settings_led_anim_kilter_only),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_led_anim_kilter_only")
                .padding(top = 4.dp)
        )
    }

    if (showPicker) {
        LedAnimationPickerSheet(
            selectedId = selectedId,
            onSelect = {
                onSelect(it)
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }
}

/**
 * The option list, in a sheet. Tapping an option applies it and closes — there is
 * nothing else to configure, so a confirm button would only add a step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LedAnimationPickerSheet(
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.settings_led_anim_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LedAnimationRegistry.ALL.forEach { animation ->
                AnimationOption(
                    animation = animation,
                    selected = animation.id == selectedId,
                    onSelect = { onSelect(animation.id) }
                )
            }
        }
    }
}

@Composable
private fun AnimationOption(
    animation: LedAnimation,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_led_anim_${animation.id}")
            .clickable { onSelect() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(animation.labelResId),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(animation.hintResId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = OrangeAccent)
        )
    }
}
