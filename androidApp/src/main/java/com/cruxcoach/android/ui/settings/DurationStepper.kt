package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.ui.theme.OrangeAccent

/**
 * Two-column minute + second stepper for fine-tuning a duration in
 * seconds. Surfaces next to the FilterChip preset row in both the
 * BLE auto-disconnect and rest-timer settings so users can dial in
 * values the presets don't cover (e.g. 1m 47s) without bouncing
 * through a full Material TimePicker dialog.
 *
 * - Tap [+]/[−] once: adjust by 1 unit (minute or second).
 * - Clamps to `[minSeconds, maxSeconds]`; the seconds half wraps at
 *   0/59 but pure overflow/underflow is a no-op.
 * - Purely driven by [seconds] + [onChange] — keeping it stateless
 *   means the caller can share state with the chip row.
 *
 * @param contentDescriptionDecMinute a11y label for the "−1 min"
 *   button (in the two local languages we support).
 */
@Composable
internal fun DurationStepper(
    seconds: Int,
    onChange: (Int) -> Unit,
    minSeconds: Int = 0,
    maxSeconds: Int = 60 * 60,
    minuteLabel: String,
    secondLabel: String,
    contentDescriptionDecMinute: String = "−1",
    contentDescriptionIncMinute: String = "+1",
    contentDescriptionDecSecond: String = "−1",
    contentDescriptionIncSecond: String = "+1",
) {
    val clamped = seconds.coerceIn(minSeconds, maxSeconds)
    val minutes = clamped / 60
    val secs = clamped % 60

    fun commit(newTotal: Int) = onChange(newTotal.coerceIn(minSeconds, maxSeconds))

    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperColumn(
            label = minuteLabel,
            value = minutes,
            onDec = { commit(clamped - 60) },
            onInc = { commit(clamped + 60) },
            decEnabled = clamped - 60 >= minSeconds,
            incEnabled = clamped + 60 <= maxSeconds,
            decContentDescription = contentDescriptionDecMinute,
            incContentDescription = contentDescriptionIncMinute,
        )
        StepperColumn(
            label = secondLabel,
            value = secs,
            onDec = { commit(clamped - 1) },
            onInc = { commit(clamped + 1) },
            decEnabled = clamped - 1 >= minSeconds,
            incEnabled = clamped + 1 <= maxSeconds,
            decContentDescription = contentDescriptionDecSecond,
            incContentDescription = contentDescriptionIncSecond,
        )
    }
}

@Composable
private fun StepperColumn(
    label: String,
    value: Int,
    onDec: () -> Unit,
    onInc: () -> Unit,
    decEnabled: Boolean,
    incEnabled: Boolean,
    decContentDescription: String,
    incContentDescription: String,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onDec,
                enabled = decEnabled,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = decContentDescription,
                    tint = if (decEnabled) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
            Text(
                value.toString().padStart(2, '0'),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.widthIn(min = 32.dp),
                textAlign = TextAlign.Center,
            )
            IconButton(
                onClick = onInc,
                enabled = incEnabled,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = incContentDescription,
                    tint = if (incEnabled) OrangeAccent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
        }
    }
}
