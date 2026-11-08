package com.cruxcoach.android.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.cruxcoach.android.R
import com.cruxcoach.android.led.LedAnimation
import com.cruxcoach.android.led.LedAnimationEngine
import com.cruxcoach.android.led.LedAnimationRegistry
import com.cruxcoach.android.led.LedAnimationService
import com.cruxcoach.android.led.LedEffectStatus
import com.cruxcoach.android.led.LedEffectsState
import com.cruxcoach.android.led.NoneAnimation
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.theme.SuccessGreen

/** Minutes an effect may run on one climb, for the texts. */
private val effectMinutes = (LedAnimationEngine.MAX_EFFECT_MS / 60_000L).toInt()

/**
 * The "LED effects" card on the board settings page: what the effect is doing
 * right now, the effects (a tap applies at once, so the wall is the preview) and
 * whether the effect may keep running in other apps. The effects themselves look
 * and move exactly as contributed; there is deliberately no tempo control.
 */
@Composable
internal fun LedEffectsSection(
    state: LedEffectsState,
    onSelect: (String) -> Unit,
    onKeepRunningChange: (Boolean) -> Unit,
    onStrobeNoticeSeen: () -> Unit,
    onConnect: () -> Unit,
) {
    SettingsInfoHeading(title = stringResource(R.string.settings_led_anim_title), description = ledEffectsInfo())
    LedEffectStatusLine(state.status, onConnect)
    LedEffectChooser(state, onSelect, onStrobeNoticeSeen)
    LedEffectBackgroundRow(state.keepRunningInBackground, onKeepRunningChange)
}

/**
 * The card's one explanation, behind its info symbol: what happens at the wall,
 * each effect, what pauses it, the time limit and running in other apps.
 */
@Composable
private fun ledEffectsInfo(): String {
    val effects = mutableListOf(stringResource(R.string.settings_led_effects_info_effects))
    for (effect in LedAnimationRegistry.ALL.sortedBy { it.photosensitive }) {
        if (effect === NoneAnimation) continue
        effects += stringResource(
            R.string.settings_led_effects_info_effect,
            stringResource(effect.labelResId),
            stringResource(effect.hintResId),
        )
    }
    return listOf(
        stringResource(R.string.settings_led_effects_info_board),
        effects.joinToString("\n"),
        pluralStringResource(R.plurals.settings_led_effects_info, effectMinutes, effectMinutes),
    ).joinToString("\n\n")
}

/**
 * One line saying what the chosen effect is doing at the wall, with its action
 * where there is one: connect when no board is, and on the climb itself send it
 * again once the effect has ended ([onSendAgain]). Nothing for "None".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LedEffectStatusLine(
    status: LedEffectStatus,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    onSendAgain: (() -> Unit)? = null,
) {
    val text = when (status) {
        LedEffectStatus.OFF -> return
        LedEffectStatus.RUNNING -> R.string.led_effect_status_running
        LedEffectStatus.WAITING_FOR_CLIMB -> R.string.led_effect_status_waiting
        LedEffectStatus.NO_BOARD -> R.string.led_effect_status_no_board
        LedEffectStatus.UNSUPPORTED_BOARD -> R.string.led_effect_status_unsupported
        LedEffectStatus.PAUSED -> R.string.led_effect_status_paused
        LedEffectStatus.ENDED -> R.string.led_effect_status_ended
        LedEffectStatus.BACKGROUND -> R.string.led_effect_status_background
    }
    val running = status == LedEffectStatus.RUNNING
    // The action sits at the end of the line while both fit, and below the words
    // at large font sizes instead of squeezing them.
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.bodyMedium,
            color = if (running) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (running) FontWeight.SemiBold else null,
            modifier = Modifier.testTag("led_effects_status"),
        )
        val action = when {
            status == LedEffectStatus.NO_BOARD -> R.string.board_browser_connect to onConnect
            status == LedEffectStatus.ENDED && onSendAgain != null -> R.string.led_effect_restart to onSendAgain
            else -> null
        }
        action?.let { (label, onClick) ->
            TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag("led_effects_status_action")) {
                Text(stringResource(label))
            }
        }
    }
}

/**
 * The effects as chips; what each one does is in the card's info. Flashing
 * effects carry a flash mark at the end, also when chosen, and ask once before their
 * first use — and every time while Android's "Remove animations" is on, which is
 * a warning, not a lock.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LedEffectChooser(
    state: LedEffectsState,
    onSelect: (String) -> Unit,
    onStrobeNoticeSeen: () -> Unit,
) {
    var pendingStrobe by rememberSaveable { mutableStateOf<String?>(null) }
    val flashingLabel = stringResource(R.string.settings_led_effects_flashing)
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Gentle effects first, the ones that flash last (a stable sort keeps the rest in order).
        LedAnimationRegistry.ALL.sortedBy { it.photosensitive }.forEach { effect ->
            val selected = effect.id == state.effectId ||
                (effect === NoneAnimation && !LedAnimationRegistry.isKnown(state.effectId))
            val label = stringResource(effect.labelResId)
            FilterChip(
                selected = selected,
                onClick = {
                    when {
                        selected -> Unit
                        effect.photosensitive && (!state.strobeNoticeSeen || state.systemAnimationsRemoved) ->
                            pendingStrobe = effect.id
                        else -> onSelect(effect.id)
                    }
                },
                label = { Text(label) },
                leadingIcon = if (selected) {
                    { Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
                trailingIcon = if (effect.photosensitive) {
                    { Icon(Icons.Outlined.FlashOn, null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("led_effect_${effect.id}")
                    .then(
                        if (effect.photosensitive) {
                            Modifier.semantics { contentDescription = "$label, $flashingLabel" }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
    val chosen: LedAnimation = LedAnimationRegistry.byId(state.effectId)
    if (chosen.photosensitive && state.systemAnimationsRemoved) {
        Row(Modifier.fillMaxWidth().testTag("led_effects_remove_animations_note")) {
            Icon(
                Icons.Default.Warning,
                null,
                tint = CruxCoachDesign.colors.caution,
                modifier = Modifier.size(18.dp).padding(top = 1.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.settings_led_effects_remove_animations_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    pendingStrobe?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingStrobe = null },
            title = { Text(stringResource(R.string.led_strobe_notice_title)) },
            text = {
                // One short paragraph, so the risk stays in sight at large fonts; with
                // "Remove animations" on it also says why it asks again.
                val label = stringResource(LedAnimationRegistry.byId(id).labelResId)
                Text(
                    if (state.systemAnimationsRemoved) {
                        stringResource(R.string.led_strobe_notice_remove_animations, label)
                    } else {
                        stringResource(R.string.led_strobe_notice_text, label)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingStrobe = null
                        onStrobeNoticeSeen()
                        onSelect(id)
                    },
                    modifier = Modifier.testTag("led_strobe_notice_confirm"),
                ) { Text(stringResource(R.string.led_strobe_notice_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingStrobe = null }) {
                    Text(stringResource(R.string.led_strobe_notice_cancel))
                }
            },
        )
    }
}

/**
 * "Keep running in other apps". Only with visible notifications, so that Stop is
 * always within reach: turning it on asks for the permission where Android has
 * one, and while notifications are off the row says so and offers the settings.
 */
@Composable
private fun LedEffectBackgroundRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var notificationsOk by remember { mutableStateOf(LedAnimationService.notificationsVisible(context)) }
    LifecycleResumeEffect(Unit) {
        notificationsOk = LedAnimationService.notificationsVisible(context)
        onPauseOrDispose { }
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        asked = true
        notificationsOk = LedAnimationService.notificationsVisible(context)
        if (granted && notificationsOk) onCheckedChange(true)
    }
    // The details are in the card's info. The switch shows the setting as it is, also
    // while notifications are off, so a tap can always turn it off.
    SettingsToggleRow(
        title = stringResource(R.string.settings_led_effects_background),
        description = "",
        checked = checked,
        onCheckedChange = { on ->
            when {
                !on -> onCheckedChange(false)
                notificationsOk -> onCheckedChange(true)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked ->
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> asked = true
            }
        },
        modifier = Modifier.testTag("led_effects_background"),
    )
    if (checked && notificationsOk) {
        // The consequence for others at a shared wall belongs next to the switch, not only in the info.
        Text(
            stringResource(R.string.settings_led_effects_background_on),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("led_effects_background_on"),
        )
    }
    if (!notificationsOk && (checked || asked)) {
        Text(
            stringResource(R.string.settings_led_effects_needs_notifications),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = {
                // Some builds lack the per-app notification page; the app's own
                // settings page is the next best place, and neither may crash.
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.recoverCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            modifier = Modifier.testTag("led_effects_allow_notifications"),
        ) { Text(stringResource(R.string.settings_led_effects_allow_notifications)) }
    }
}

/**
 * The climb menu's quick pick: the status and the effects in a sheet, so an
 * effect can be changed or turned off right where the climb is lit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LedEffectQuickSheet(
    state: LedEffectsState,
    onSelect: (String) -> Unit,
    onStrobeNoticeSeen: () -> Unit,
    onConnect: () -> Unit,
    onSendAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Fully open: at large font sizes the choice is taller than half the
    // screen, and a half-open sheet would cut it off with "Done" out of reach.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .testTag("led_effect_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsInfoHeading(title = stringResource(R.string.settings_led_anim_title), description = ledEffectsInfo())
            LedEffectStatusLine(state.status, onConnect, onSendAgain = onSendAgain)
            LedEffectChooser(state, onSelect, onStrobeNoticeSeen)
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("led_effect_sheet_done")) {
                Text(stringResource(R.string.action_done))
            }
        }
    }
}
