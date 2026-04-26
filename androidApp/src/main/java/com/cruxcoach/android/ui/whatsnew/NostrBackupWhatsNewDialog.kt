package com.cruxcoach.android.ui.whatsnew

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.data.SyncInterval
import com.cruxcoach.android.nostr.NostrKeyStore
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.nostr.SignerMode
import com.cruxcoach.android.nostr.backup.BackupPreferences
import com.cruxcoach.android.nostr.backup.BackupSyncWorker
import com.cruxcoach.android.ui.common.BackupKeyWarningCard
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the FEAT-002 announcement dialog shown to users who upgrade into
 * 0.1.3 (or any later version, until they have acknowledged it). The
 * dialog ships with the toggle defaulting to off — confirming with the
 * toggle on enables encrypted cloud backup and creates a local key if the
 * upgrading user does not yet have one (mirrors the FRESH branch of
 * [com.cruxcoach.android.ui.onboarding.OnboardingViewModel.completeOnboarding]).
 *
 * Cadence: defaults to MANUAL but the user can pick DAILY/WEEKLY in the
 * dialog itself, so they don't have to find Settings to opt into a
 * schedule.
 */
@HiltViewModel
class NostrBackupWhatsNewViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val backupPreferences: BackupPreferences,
    private val keyStore: NostrKeyStore,
    private val signer: NostrSigner,
) : ViewModel() {

    data class State(
        val toggle: Boolean = false,
        /** Cadence picked while the dialog is open. Default MANUAL keeps
         *  the toggle-on flow free of background-job surprises; user can
         *  pick DAILY/WEEKLY here without going to Settings. */
        val frequency: SyncInterval = SyncInterval.MANUAL,
        val saving: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setToggle(value: Boolean) {
        _state.update { it.copy(toggle = value) }
    }

    fun setFrequency(interval: SyncInterval) {
        _state.update { it.copy(frequency = interval) }
    }

    /**
     * Persist the user's choice and dismiss. If the toggle is off, this
     * is a pure no-op besides the dismiss callback. If on, we create a
     * local Nostr key (only if one doesn't already exist), enable the
     * preference, and schedule the worker with the chosen cadence.
     */
    fun confirm(onDone: () -> Unit) {
        val target = _state.value.toggle
        if (!target) {
            onDone()
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            if (!keyStore.hasKey()) {
                keyStore.getOrCreateKeyPair()
                signer.switchToLocal()
            }
            backupPreferences.setBackupEnabled(true)
            BackupSyncWorker.schedule(
                appContext,
                enabled = true,
                interval = _state.value.frequency,
            )
            _state.update { it.copy(saving = false) }
            onDone()
        }
    }
}

@Composable
internal fun NostrBackupWhatsNewDialog(
    onDismiss: () -> Unit,
    onNavigateToKeyManagement: () -> Unit = {},
    vm: NostrBackupWhatsNewViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()

    AlertDialog(
        // Treat back / scrim taps the same as confirm: the user has
        // seen the toggle and we should respect whatever state they
        // left it in. No silent re-prompt next launch.
        onDismissRequest = {
            if (!state.saving) vm.confirm(onDismiss)
        },
        title = {
            Text(stringResource(R.string.whatsnew_nostr_backup_title))
        },
        text = {
            // Scrollable so the warning card + frequency picker don't get
            // clipped on smaller screens.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.whatsnew_nostr_backup_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.whatsnew_nostr_backup_toggle),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        checked = state.toggle,
                        enabled = !state.saving,
                        onCheckedChange = vm::setToggle,
                    )
                }
                if (state.toggle) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.whatsnew_nostr_backup_consent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    // Frequency picker — same UX as in onboarding so the
                    // user doesn't have to find Settings just to enable
                    // a daily/weekly schedule.
                    Text(
                        stringResource(R.string.onboarding_backup_frequency_label),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SyncInterval.entries.forEach { interval ->
                            FilterChip(
                                selected = state.frequency == interval,
                                onClick = { vm.setFrequency(interval) },
                                enabled = !state.saving,
                                label = { Text(stringResource(interval.labelRes)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    // Backup-key warning. The toggle just got enabled →
                    // remind the user that without a saved key the cloud
                    // backup is unrestorable. SignerMode is LOCAL because
                    // an Amber-backed key holder is unlikely to first
                    // encounter the FEAT-002 announcement (Amber-pair is
                    // a deliberate Settings flow that already warns the
                    // user about Amber's own backup). The "Open Account"
                    // button confirms the dialog and navigates so the
                    // user can copy their key in one trip.
                    BackupKeyWarningCard(
                        signerMode = SignerMode.LOCAL,
                        onOpenAccount = {
                            // confirm() persists toggle+freq, then
                            // dismisses → onDismiss → host navigates.
                            vm.confirm {
                                onDismiss()
                                onNavigateToKeyManagement()
                            }
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.confirm(onDismiss) },
                enabled = !state.saving,
            ) {
                Text(stringResource(R.string.whatsnew_done))
            }
        },
    )
}
