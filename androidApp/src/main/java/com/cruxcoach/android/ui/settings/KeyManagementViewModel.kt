package com.cruxcoach.android.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.data.NostrMessageRepository
import com.cruxcoach.android.data.SyncInterval
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.AmberIntegration
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrKeyStore
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.nostr.SignerMode
import com.cruxcoach.android.nostr.backup.BackupPreferences
import com.cruxcoach.android.nostr.backup.BackupSyncWorker
import com.cruxcoach.android.nostr.relaydiscovery.RelayListCache
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class KeyManagementState(
    val npubDisplay: String = "",
    val npubFull: String = "",
    val amberPubkeyDisplay: String? = null,
    val signerMode: SignerMode = SignerMode.LOCAL,
    val isAmberInstalled: Boolean = false,
    val keyBackedUp: Boolean = false,
    val showNsecWarningDialog: Boolean = false,
    val showAmberNotInstalledDialog: Boolean = false,
    val showAmberSuccessDialog: Boolean = false,
    val showNoSecurityDialog: Boolean = false,
    val isLoading: Boolean = true,
    val requireRestart: Boolean = false,
    val error: String? = null,
    val userMessage: String? = null
)

@HiltViewModel
class KeyManagementViewModel @Inject constructor(
    private val keyStore: NostrKeyStore,
    private val nostrSigner: NostrSigner,
    private val userPreferences: UserPreferences,
    private val messageRepository: NostrMessageRepository,
    private val backupPreferences: BackupPreferences,
    private val relayListCache: RelayListCache,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(KeyManagementState())
    val state: StateFlow<KeyManagementState> = _state.asStateFlow()

    init {
        loadKeyInfo()
    }

    private fun loadKeyInfo() {
        viewModelScope.launch {
            val initialState = withContext(Dispatchers.IO) {
                try {
                    val pubKeyRaw = nostrSigner.getPublicKeyHex()
                    val pubKeyHex = normalizeToHex(pubKeyRaw) ?: pubKeyRaw
                    val pubKeyBytes = pubKeyHex.hexToByteArray()
                    val npubFull = pubKeyBytes.toNpub()
                    val npubDisplay = formatNpubShort(npubFull)

                    val mode = nostrSigner.getStoredSignerMode()
                    val backedUp = userPreferences.keyBackedUp.first()
                    val amberInstalled = AmberIntegration.isInstalled(context)

                    val amberPubkeyDisplay = if (mode == SignerMode.AMBER) {
                        nostrSigner.getStoredAmberPubkey()?.let {
                            try {
                                val hex = normalizeToHex(it) ?: it
                                formatNpubShort(hex.hexToByteArray().toNpub())
                            } catch (e: Exception) {
                                null
                            }
                        }
                    } else null

                    KeyManagementState(
                        npubDisplay = npubDisplay,
                        npubFull = npubFull,
                        amberPubkeyDisplay = amberPubkeyDisplay,
                        signerMode = mode,
                        isAmberInstalled = amberInstalled,
                        keyBackedUp = backedUp,
                        isLoading = false
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load key info", e)
                    KeyManagementState(
                        isLoading = false,
                        error = context.getString(R.string.key_import_failed, e.message ?: "")
                    )
                }
            }
            _state.update { initialState }
        }
    }

    fun copyNpub() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("npub", _state.value.npubFull))
        _state.update { it.copy(userMessage = context.getString(R.string.key_public_key_copied)) }
    }

    // ── nsec copy flow ───────────────────────────────────────────

    fun requestNsecCopy() {
        _state.update { it.copy(showNsecWarningDialog = true) }
    }

    fun dismissNsecWarning() {
        _state.update { it.copy(showNsecWarningDialog = false) }
    }

    fun confirmNsecCopy() {
        _state.update { it.copy(showNsecWarningDialog = false) }
        val privKeyHex = keyStore.getPrivateKeyHex() ?: return
        val nsec = privKeyHex.hexToByteArray().toNsec()
        copySecretToClipboard(nsec)
    }

    /**
     * User-initiated "I've stored my key somewhere safe" flag flip.
     * Same UserPreferences.keyBackedUp flag the BackupKeyWarningCard
     * in Settings → Cloud-Backup queries — acknowledging here makes
     * both warnings disappear at once.
     */
    fun acknowledgeKeyBackup() {
        viewModelScope.launch {
            userPreferences.setKeyBackedUp(true)
            _state.update { it.copy(keyBackedUp = true) }
        }
    }

    // ── Amber flow ───────────────────────────────────────────────

    fun requestAmberSetup() {
        if (!_state.value.isAmberInstalled) {
            _state.update { it.copy(showAmberNotInstalledDialog = true) }
        }
        // When installed, the caller handles launching the Amber intent
    }

    fun dismissAmberNotInstalled() {
        _state.update { it.copy(showAmberNotInstalledDialog = false) }
    }

    fun onAmberLoginSuccess(pubkeyInput: String, packageName: String?) {
        viewModelScope.launch {
            val pubkeyHex = normalizeToHex(pubkeyInput) ?: run {
                _state.update { it.copy(error = context.getString(R.string.key_import_format_unknown)) }
                return@launch
            }
            val pkg = packageName ?: AmberIntegration.AMBER_PACKAGE
            // Cancel periodic backup before identity swap so the next
            // scheduled tick can't fire under the new pubkey while
            // BackupRepository.pipelineMutex still serializes any
            // in-flight run from the old identity.
            BackupSyncWorker.schedule(context, enabled = false, interval = SyncInterval.MANUAL)
            nostrSigner.saveAmberConfig(pubkeyHex, pkg)
            nostrSigner.switchToAmber(pubkeyHex, pkg, context.contentResolver)

            // Purge messages from previous identity and reset sync cursor
            withContext(Dispatchers.IO) {
                messageRepository.deleteForeignIdentityRows(pubkeyHex, NostrConfig.DEV_PUBKEY)
                userPreferences.setNostrSyncCursor(0L)
                // FEAT-002 state (wrapped dataKey, d-tag cache, previous
                // blob sha, timestamps) is identity-scoped — reset so the
                // new Amber pubkey doesn't publish under the old d-tag.
                backupPreferences.clearAllIdentityState()
                // FEAT-001 NIP-65 cache is global DataStore; stale relays
                // would route new pubkey's publishes to old identity's
                // relays until the 24h TTL ticks.
                relayListCache.clear()
            }

            val displayNpub = try {
                formatNpubShort(pubkeyHex.hexToByteArray().toNpub())
            } catch (e: Exception) {
                null
            }

            _state.update {
                it.copy(
                    signerMode = SignerMode.AMBER,
                    amberPubkeyDisplay = displayNpub,
                    showAmberSuccessDialog = true
                )
            }
        }
    }

    fun dismissAmberSuccess() {
        _state.update { it.copy(showAmberSuccessDialog = false) }
    }

    fun deleteLocalKeyAfterAmber() {
        keyStore.deleteKey()
        _state.update { it.copy(showAmberSuccessDialog = false) }
    }

    fun switchToLocalSigner() {
        viewModelScope.launch {
            // Cancel periodic backup before identity swap (see onAmberLoginSuccess).
            BackupSyncWorker.schedule(context, enabled = false, interval = SyncInterval.MANUAL)
            nostrSigner.clearAmberConfig()
            nostrSigner.switchToLocal()

            withContext(Dispatchers.IO) {
                val newPubkey = nostrSigner.getPublicKeyHex()
                messageRepository.deleteForeignIdentityRows(newPubkey, NostrConfig.DEV_PUBKEY)
                userPreferences.setNostrSyncCursor(0L)
                backupPreferences.clearAllIdentityState()
                relayListCache.clear()
            }

            _state.update { it.copy(requireRestart = true) }
        }
    }

    // ── No-security dialog ───────────────────────────────────────

    fun showNoSecurityDialog() {
        _state.update { it.copy(showNoSecurityDialog = true) }
    }

    fun dismissNoSecurityDialog() {
        _state.update { it.copy(showNoSecurityDialog = false) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissUserMessage() {
        _state.update { it.copy(userMessage = null) }
    }

    // ── Private helpers ──────────────────────────────────────────

    private fun copySecretToClipboard(secret: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("nostr_key", secret)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(
                if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE
                else "android.content.extra.IS_SENSITIVE",
                true
            )
        }
        clipboard.setPrimaryClip(clip)

        Executors.newSingleThreadScheduledExecutor().schedule({
            if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }, CLIPBOARD_CLEAR_DELAY_SECONDS, TimeUnit.SECONDS)

        _state.update {
            it.copy(
                userMessage = context.getString(
                    R.string.key_secret_copied_clipboard_clear,
                    CLIPBOARD_CLEAR_DELAY_SECONDS
                )
            )
        }
    }

    private fun formatNpubShort(npub: String): String =
        if (npub.length > 20) "${npub.take(12)}...${npub.takeLast(6)}" else npub

    /**
     * Converts an npub (bech32) to hex, or returns the input if already hex.
     * Returns null if the format is unrecognizable.
     */
    private fun normalizeToHex(input: String): String? {
        if (input.startsWith("npub1")) {
            return Nip19Parser.parseAll(input)
                .filterIsInstance<NPub>()
                .firstOrNull()?.hex
        }
        return input
    }

    companion object {
        private const val TAG = "KeyManagementViewModel"
        private const val CLIPBOARD_CLEAR_DELAY_SECONDS = 60L
    }
}
